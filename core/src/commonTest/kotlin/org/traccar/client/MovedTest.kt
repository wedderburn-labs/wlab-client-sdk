package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovedTest {

    @Test
    fun aPreciseFixFarEnoughAwayCounts() {
        // About 330 m north.
        assertTrue(movedBeyond(-26.1145, 27.9397, -26.1115, 27.9397, 10.0, 100))
    }

    @Test
    fun aCoarseFixOnlyCountsOnceItsErrorIsTakenOff() {
        assertFalse(movedBeyond(-26.1145, 27.9397, -26.1115, 27.9397, 500.0, 100))
        assertTrue(movedBeyond(-26.1145, 27.9397, -26.0965, 27.9397, 1000.0, 100))
    }

    @Test
    fun stayingPutDoesNotCount() {
        assertFalse(movedBeyond(-26.1145, 27.9397, -26.1146, 27.9398, 65.0, 100))
    }
}
