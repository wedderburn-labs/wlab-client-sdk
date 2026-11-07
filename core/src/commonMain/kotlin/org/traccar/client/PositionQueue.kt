package org.traccar.client

interface PositionQueue {
    suspend fun enqueue(position: Position)
    suspend fun peek(): Position?
    suspend fun removeFirst()

    /** Up to [limit] positions: alarms, then the newest fix, then the backlog oldest to newest. */
    suspend fun nextBatch(limit: Int): List<Position>

    /** Deletes the positions with these ids. */
    suspend fun remove(ids: Collection<String>)

    /** Keeps these positions but stops sending them. Maps id to the server's reason. */
    suspend fun reject(reasons: Map<String, String>)

    /** Stops sending rows stored without a device, keeping them. Only an upgrade leaves any. */
    suspend fun rejectWithoutDevice(reason: String)

    suspend fun status(): QueueStatus
}

data class QueueStatus(
    /** Positions waiting to upload. */
    val queued: Int,
    /** Positions the server refused for good. They are kept, and never re-sent. */
    val rejected: Int,
    /** How many of [queued] carry an alarm. */
    val queuedAlarms: Int,
    /** How many of [rejected] carry an alarm. */
    val rejectedAlarms: Int,
)
