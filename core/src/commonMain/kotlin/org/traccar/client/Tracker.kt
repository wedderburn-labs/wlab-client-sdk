package org.traccar.client

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.dsl.koinApplication

internal val QUEUE_FIX_TIMEOUT = 10.seconds

class Tracker internal constructor(
    val config: Config,
    private val stateStore: StateStore,
    private val locationSource: LocationSource,
    private val batteryProcessor: PositionProcessor,
    private val uploader: Uploader,
    private val queue: PositionQueue,
    private val engine: TrackerEngine,
    private val componentScope: ComponentCoroutineScope,
) {
    val state: StateFlow<State> = stateStore.state

    suspend fun start() = sharedMutex.withLock {
        Log.log("Tracker start ${config.serverUrl} ${config.deviceId}")
        stateStore.update { it.copy(enabled = true) }
    }

    suspend fun stop() = sharedMutex.withLock {
        Log.log("Tracker stop")
        stateStore.update { it.copy(enabled = false, paused = false) }
    }

    suspend fun requestPosition(alarm: String? = null): Boolean {
        Log.log("Position requested${alarm?.let { " alarm=$it" } ?: ""}")
        val raw = locationSource.fetchOnce() ?: run {
            Log.log("Position request: no fix")
            return false
        }
        Log.log("Position fetched ${raw.latitude},${raw.longitude}")
        val processed = batteryProcessor.process(raw)?.copy(alarm = alarm) ?: return false
        return uploader.upload(processed)
    }

    /**
     * Takes one position and stores it in the queue, so it survives no signal and app restarts.
     * Returns its client id once it is safely stored. An alarm jumps the queue and skips the flush
     * delay and jitter. No fix within [QUEUE_FIX_TIMEOUT] does not stop it: the position goes with
     * the last known coordinates, or with none. Its time is always now, with the time of the
     * location itself in the `fixTime` extra.
     */
    suspend fun queuePosition(alarm: String? = null, extras: Map<String, String> = emptyMap()): String {
        Log.log("Position queued${alarm?.let { " alarm=$it" } ?: ""}")
        val now = Clock.System.now().toEpochMilliseconds()
        val fix = withTimeoutOrNull(QUEUE_FIX_TIMEOUT) { locationSource.fetchOnce() }
        val source = fix ?: stateStore.state.value.lastAcceptedLocation
        val located = source?.takeIf { it.latitude != null && it.longitude != null }
        val fixTime = located?.let { mapOf("fixTime" to Instant.fromEpochMilliseconds(it.time).toString()) }
        val position = (located ?: Position(time = now)).copy(time = now, battery = null, charging = null)
        val processed = batteryProcessor.process(position) ?: position
        return engine.submit(processed.copy(alarm = alarm, extras = fixTime.orEmpty() + extras))
    }

    suspend fun queueStatus(): QueueStatus = queue.status()

    suspend fun getLogs(): List<LogEntry> = Log.store?.all() ?: emptyList()

    suspend fun clearLogs() = Log.store?.clear()

    suspend fun updateConfig(newConfig: Config): Tracker = sharedMutex.withLock {
        componentScope.coroutineContext.job.cancelAndJoin()
        sharedTrackerInstance = null
        bootstrap(newConfig)
    }
}

private val sharedMutex = Mutex()
private var sharedTrackerInstance: Tracker? = null

suspend fun sharedTracker(): Tracker? = sharedMutex.withLock {
    sharedTrackerInstance?.let { return@withLock it }
    val koin = openKoin()
    val persisted = koin.get<ConfigStore>().load() ?: return@withLock null
    install(koin, persisted).also {
        Log.log("Tracker restored from saved settings, tracking ${if (it.state.value.enabled) "on" else "off"}")
    }
}

suspend fun sharedTracker(config: Config): Tracker = sharedMutex.withLock {
    sharedTrackerInstance?.let { return@withLock it }
    bootstrap(config)
}

private suspend fun bootstrap(config: Config): Tracker {
    val koin = openKoin()
    koin.get<ConfigStore>().save(config)
    return install(koin, config)
}

private suspend fun openKoin() = withContext(Dispatchers.IO) {
    val koin = koinApplication { modules(coreModule(), platformModule()) }.koin
    Log.store = LogStore(koin.get())
    koin
}

private suspend fun install(koin: Koin, config: Config): Tracker {
    val stateStore = StateStore.create(koin.get())
    koin.declare(stateStore)
    koin.declare(config)
    if (!config.location.stopDetection) {
        stateStore.update { it.copy(paused = false) }
    }
    koin.get<TrackerEngine>()
    return koin.get<Tracker>().also { sharedTrackerInstance = it }
}
