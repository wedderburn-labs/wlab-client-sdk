package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.traccar.client.db.Database

class DatabaseQueue(driver: SqlDriver) : PositionQueue {

    private val queries = Database(driver).positionQueries
    private val mutex = Mutex()

    override suspend fun enqueue(position: Position) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.enqueue(
                    latitude = position.latitude,
                    longitude = position.longitude,
                    accuracy = position.accuracy,
                    time = position.time,
                    altitude = position.altitude,
                    speed = position.speed,
                    bearing = position.bearing,
                    battery = position.battery?.toLong(),
                    charging = position.charging?.toLong(),
                    uuid = position.id ?: newPositionId(),
                    device_id = position.deviceId,
                    alarm = position.alarm,
                    moving = position.moving?.toLong(),
                    extras = position.extras.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                )
            }
        }
    }

    override suspend fun peek(): Position? = withContext(Dispatchers.IO) {
        mutex.withLock {
            queries.peek(::toPosition).executeAsOneOrNull()
        }
    }

    override suspend fun removeFirst() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.removeFirst()
            }
        }
    }

    override suspend fun rejectWithoutDevice(reason: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.rejectWithoutDevice(reason)
            }
        }
    }

    override suspend fun nextBatch(limit: Int): List<Position> = withContext(Dispatchers.IO) {
        mutex.withLock {
            queries.nextBatch(limit.toLong(), ::toPosition).executeAsList()
        }
    }

    override suspend fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.removeByUuid(ids)
            }
        }
    }

    override suspend fun reject(reasons: Map<String, String>) {
        if (reasons.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.transaction {
                    reasons.forEach { (id, reason) -> queries.reject(reason = reason, uuid = id) }
                }
            }
        }
    }

    override suspend fun status(): QueueStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            QueueStatus(
                queued = queries.countQueued().executeAsOne().toInt(),
                rejected = queries.countRejected().executeAsOne().toInt(),
                queuedAlarms = queries.countQueuedAlarms().executeAsOne().toInt(),
                rejectedAlarms = queries.countRejectedAlarms().executeAsOne().toInt(),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun toPosition(
        latitude: Double?,
        longitude: Double?,
        accuracy: Double?,
        time: Long,
        altitude: Double?,
        speed: Double?,
        bearing: Double?,
        battery: Long?,
        charging: Long?,
        uuid: String?,
        deviceId: String?,
        alarm: String?,
        moving: Long?,
        extras: String?,
    ) = Position(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        time = time,
        altitude = altitude,
        speed = speed,
        bearing = bearing,
        battery = battery?.toInt(),
        charging = charging?.let { it != 0L },
        alarm = alarm,
        id = uuid,
        deviceId = deviceId,
        moving = moving?.let { it != 0L },
        extras = extras?.let { Json.decodeFromString<Map<String, String>>(it) }.orEmpty(),
    )

    private fun Boolean.toLong() = if (this) 1L else 0L
}

@OptIn(ExperimentalUuidApi::class)
internal fun newPositionId(): String = Uuid.random().toString()
