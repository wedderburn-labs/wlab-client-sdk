package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.traccar.client.db.Database

class MigrationTest {

    @Test
    fun queuedPositionsSurviveTheUpgradeAndGainIds() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE Position (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL, longitude REAL, accuracy REAL, time INTEGER NOT NULL,
                altitude REAL, speed REAL, bearing REAL, battery INTEGER, charging INTEGER
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO Position(latitude, longitude, time) VALUES (-29.3, 27.4, 1000)", 0)
        driver.execute(null, "INSERT INTO Position(latitude, longitude, time) VALUES (-29.4, 27.5, 2000)", 0)

        Database.Schema.migrate(driver, 3, Database.Schema.version)

        val queue = DatabaseQueue(driver)
        val batch = queue.nextBatch(10)
        assertEquals(setOf(1000L, 2000L), batch.map { it.time }.toSet())
        assertEquals(2, batch.map { assertNotNull(it.id) }.toSet().size)
        assertEquals(null, batch.first().deviceId)
        assertEquals(2, queue.status().queued)
    }

    @Test
    fun schemaVersionIsFour() {
        assertEquals(4, Database.Schema.version)
    }
}
