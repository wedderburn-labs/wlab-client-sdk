package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleFixTest {

    private val now = 1_762_800_000_000L

    @Test
    fun aFreshFixIsUsed() {
        assertFalse(isStaleFix(now - 2_000, now))
    }

    @Test
    fun aFixAMinuteOldIsStillUsed() {
        assertFalse(isStaleFix(now - STALE_FIX_MS, now))
    }

    @Test
    fun aCachedFixFromHoursAgoIsIgnored() {
        assertTrue(isStaleFix(now - 3 * 60 * 60 * 1000L, now))
    }

    @Test
    fun aFixStampedSlightlyAheadOfTheClockIsUsed() {
        assertFalse(isStaleFix(now + 500, now))
    }
}
