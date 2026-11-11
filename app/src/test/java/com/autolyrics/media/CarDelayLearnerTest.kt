package com.autolyrics.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarDelayLearnerTest {

    @Test
    fun needsAtLeastThreeSongs() {
        assertNull(CarDelayLearner.decideShift(listOf(400L, 450L)))
    }

    @Test
    fun learnsSharedDelay() {
        assertEquals(400L, CarDelayLearner.decideShift(listOf(400L, 450L, 350L)))
    }

    @Test
    fun ignoresWhenSongsDisagree() {
        assertNull(CarDelayLearner.decideShift(listOf(400L, 450L, 0L)))
        assertNull(CarDelayLearner.decideShift(listOf(400L, -400L, 420L, -380L)))
    }

    @Test
    fun ignoresTinyDifferences() {
        assertNull(CarDelayLearner.decideShift(listOf(100L, 120L, 90L)))
    }

    @Test
    fun negativeDelayWithOneOddSong() {
        assertEquals(-300L, CarDelayLearner.decideShift(listOf(-350L, -320L, -300L, 500L)))
    }

    @Test
    fun roundsToNearest50ms() {
        assertEquals(450L, CarDelayLearner.decideShift(listOf(430L, 430L, 430L)))
    }
}
