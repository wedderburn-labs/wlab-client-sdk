package org.traccar.client

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class TrackerEngine internal constructor(
    private val stateStore: StateStore,
    private val queue: PositionQueue,
    private val network: NetworkMonitor,
    private val locationSource: LocationSource,
    signalSources: List<SignalSource>,
    private val processors: List<PositionProcessor>,
    private val uploader: Uploader,
    private val buffer: Boolean,
    scope: ComponentCoroutineScope,
    private val initialBackoff: Duration = 5.seconds,
    private val maxBackoff: Duration = 5.minutes,
    private val deviceId: String = "",
    private val batchUploader: BatchUploader? = null,
    private val batch: BatchConfig = BatchConfig(),
    private val random: Random = Random.Default,
) {
    private val mutex = Mutex()
    private val pipelineWakeUp = Channel<Unit>(Channel.CONFLATED)
    private val urgentWakeUp = Channel<Unit>(Channel.CONFLATED)
    private val heartbeatPositions = MutableSharedFlow<Position>(extraBufferCapacity = 4)

    init {
        scope.launch { signalSources.map { it.signals }.merge().collect { handle(it) } }
        scope.launch { pipelineLoop() }
        scope.launch {
            // A position must never go out under a device it wasn't recorded for.
            queue.rejectWithoutDevice(NO_DEVICE_REASON)
            if (batchUploader != null) batchSyncLoop(batchUploader) else syncLoop()
        }
    }

    /** Stores [position] and wakes the sender. Returns the position's client id. */
    internal suspend fun submit(position: Position): String {
        val stamped = stamp(position)
        queue.enqueue(stamped)
        if (stamped.alarm != null) urgentWakeUp.trySend(Unit)
        pipelineWakeUp.trySend(Unit)
        return checkNotNull(stamped.id)
    }

    private fun stamp(position: Position) = position.copy(
        id = position.id ?: newPositionId(),
        deviceId = position.deviceId ?: deviceId,
        moving = position.moving ?: (!position.heartbeat && !stateStore.state.value.paused),
    )

    suspend fun handle(signal: Signal) = mutex.withLock {
        when (signal) {
            Signal.StationaryEnter -> applyStationaryEnter()
            Signal.StationaryExit -> applyStationaryExit()
            Signal.HeartbeatTick -> applyHeartbeatTick()
        }
    }

    private suspend fun applyStationaryEnter() {
        val state = stateStore.state.value
        if (!state.enabled || state.paused) return
        Log.log("StationaryEnter: pausing")
        stateStore.update { it.copy(paused = true) }
    }

    private suspend fun applyStationaryExit() {
        val state = stateStore.state.value
        if (!state.enabled || !state.paused) return
        Log.log("StationaryExit: resuming")
        stateStore.update { it.copy(paused = false) }
    }

    private suspend fun applyHeartbeatTick() {
        val state = stateStore.state.value
        if (!state.enabled || !state.paused) return
        Log.log("HeartbeatTick")
        val position = locationSource.fetchOnce()
            ?: Position(time = Clock.System.now().toEpochMilliseconds())
        heartbeatPositions.emit(position.copy(heartbeat = true))
    }

    private suspend fun pipelineLoop() {
        merge(locationSource.positions, heartbeatPositions).collect { incoming ->
            if (!stateStore.state.value.enabled) return@collect
            var current: Position? = incoming
            for (processor in processors) {
                current = processor.process(current ?: break)
            }
            val final = current ?: return@collect
            if (buffer || batchUploader != null) {
                submit(final)
            } else {
                uploader.upload(final)
            }
        }
    }

    private suspend fun syncLoop() {
        var backoff = initialBackoff
        while (currentCoroutineContext().isActive) {
            val pending = queue.peek()
            when {
                pending == null -> pipelineWakeUp.receive()
                !network.isOnline.value -> {
                    Log.log("Offline, waiting for network")
                    network.isOnline.first { it }
                    Log.log("Network restored")
                }
                uploader.upload(pending) -> {
                    queue.removeFirst()
                    backoff = initialBackoff
                }
                else -> {
                    Log.log("Upload failed, retrying in $backoff")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(maxBackoff)
                }
            }
        }
    }

    private suspend fun batchSyncLoop(batchUploader: BatchUploader) {
        var backoff = initialBackoff
        var limit = batch.maxPositions
        var gap = true // a backlog at startup is a gap in coverage too

        suspend fun failed(urgent: Boolean, retryAfter: Duration?) {
            val base = if (urgent) backoff.coerceAtMost(URGENT_MAX_BACKOFF) else backoff
            val wait = retryAfter ?: (base + jitter(base))
            Log.log("Batch failed, retrying in $wait")
            holdOff(wait)
            backoff = (backoff * 2).coerceAtMost(maxBackoff)
        }

        while (currentCoroutineContext().isActive) {
            val pending = queue.nextBatch(limit)
            val urgent = pending.firstOrNull()?.alarm != null
            when {
                pending.isEmpty() -> {
                    gap = false
                    pipelineWakeUp.receive()
                    holdOff(batch.flushSeconds.seconds)
                }
                !network.isOnline.value -> {
                    Log.log("Offline, waiting for network")
                    network.isOnline.first { it }
                    Log.log("Network restored")
                    gap = true
                }
                gap && !urgent -> {
                    gap = false
                    holdOff(jitter(batch.jitterSeconds.seconds))
                }
                else -> {
                    gap = false
                    when (val result = batchUploader.upload(pending)) {
                        is BatchResult.Confirmed -> {
                            val sent = pending.mapNotNull { it.id }.toSet()
                            val accepted = result.accepted.intersect(sent)
                            val refused = result.rejected
                                .filterKeys { it in sent }
                                .filterValues { !it.startsWith(RETRY_REASON_PREFIX) }
                            queue.remove(accepted)
                            queue.reject(refused)
                            if (accepted.isEmpty() && refused.isEmpty()) {
                                failed(urgent, null)
                            } else {
                                backoff = initialBackoff
                                limit = batch.maxPositions
                            }
                        }
                        BatchResult.TooLarge -> if (pending.size > 1) {
                            limit = pending.size / 2
                            Log.log("Batch too large, trying $limit")
                        } else {
                            queue.reject(pending.mapNotNull { it.id }.associateWith { "too_large" })
                        }
                        is BatchResult.Failed -> failed(urgent, result.retryAfter)
                    }
                }
            }
        }
    }

    /** Waits for [duration], or until an alarm is queued. */
    private suspend fun holdOff(duration: Duration) {
        if (duration.isPositive()) withTimeoutOrNull(duration) { urgentWakeUp.receive() }
    }

    private fun jitter(upTo: Duration): Duration =
        if (upTo.isPositive()) random.nextLong(upTo.inWholeMilliseconds + 1).milliseconds else Duration.ZERO

    private companion object {
        val URGENT_MAX_BACKOFF = 30.seconds

        /** A rejection whose reason starts with this stays queued. All others are final. */
        const val RETRY_REASON_PREFIX = "retry"
        const val NO_DEVICE_REASON = "no_device"
    }
}
