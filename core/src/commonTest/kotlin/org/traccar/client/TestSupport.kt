package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal expect fun testDriver(): SqlDriver

internal expect fun gunzip(data: ByteArray): ByteArray

internal fun position(id: String, time: Long = 1_000, alarm: String? = null) = Position(
    latitude = -29.31,
    longitude = 27.48,
    time = time,
    alarm = alarm,
    id = id,
    deviceId = "device",
)

/** Same rules as DatabaseQueue, without the IO dispatcher, so engine tests can use virtual time. */
internal class FakeQueue : PositionQueue {
    val rows = mutableListOf<Position>()
    val rejections = linkedMapOf<String, String>()
    private val live get() = rows.filter { it.id !in rejections }

    override suspend fun enqueue(position: Position) {
        rows += position.copy(id = position.id ?: newPositionId())
    }

    override suspend fun peek() = live.firstOrNull()

    override suspend fun removeFirst() {
        live.firstOrNull()?.let { rows.remove(it) }
    }

    override suspend fun nextBatch(limit: Int): List<Position> {
        val alarms = live.filter { it.alarm != null }.sortedByDescending { it.time }
        val others = live.filter { it.alarm == null }
        val newest = live.maxByOrNull { it.time }?.takeIf { it.alarm == null }
        return (alarms + listOfNotNull(newest) + others.filter { it !== newest }.sortedBy { it.time }).take(limit)
    }

    override suspend fun remove(ids: Collection<String>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun reject(reasons: Map<String, String>) {
        rejections += reasons
    }

    override suspend fun rejectWithoutDevice(reason: String) {
        live.filter { it.deviceId == null }.mapNotNull { it.id }.forEach { rejections[it] = reason }
    }

    override suspend fun status() = QueueStatus(
        queued = live.size,
        rejected = rejections.size,
        queuedAlarms = live.count { it.alarm != null },
        rejectedAlarms = rows.count { it.id in rejections && it.alarm != null },
    )
}

internal class FakeNetwork(online: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(online)
}

internal class FakeLocationSource(var fix: Position? = null) : LocationSource {
    override val positions: Flow<Position> = MutableSharedFlow(extraBufferCapacity = 64)
    val emitter get() = positions as MutableSharedFlow<Position>
    var hang = false

    override suspend fun fetchOnce(): Position? {
        if (hang) kotlinx.coroutines.awaitCancellation()
        return fix
    }
}

/** Records every batch, then answers with [respond]. By default accepts everything. */
internal class FakeBatchUploader(
    var respond: (List<Position>) -> BatchResult = { batch ->
        BatchResult.Confirmed(batch.mapNotNull { it.id }.toSet(), emptyMap())
    },
) : BatchUploader {
    val calls = mutableListOf<List<Position>>()

    override suspend fun upload(positions: List<Position>): BatchResult {
        calls += positions
        return respond(positions)
    }
}

internal class RecordingUploader : Uploader {
    val uploaded = mutableListOf<Position>()

    override suspend fun upload(position: Position): Boolean {
        uploaded += position
        return true
    }
}

/** Always picks the top of the range, so a jitter is exactly its bound. */
internal object MaxRandom : Random() {
    override fun nextBits(bitCount: Int) = 0
    override fun nextLong(until: Long) = until - 1
}
