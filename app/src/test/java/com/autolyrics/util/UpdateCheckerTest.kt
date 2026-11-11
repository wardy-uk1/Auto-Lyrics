package com.autolyrics.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerVersionsCompareHigher() {
        assertTrue(UpdateChecker.compareVersions("2.1.0", "2.0.0") > 0)
        assertTrue(UpdateChecker.compareVersions("v2.0.10", "2.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("3.0", "2.9.9") > 0)
    }

    @Test
    fun olderVersionsCompareLower() {
        assertTrue(UpdateChecker.compareVersions("1.15.0", "2.0.0") < 0)
    }

    @Test
    fun equalVersions() {
        assertEquals(0, UpdateChecker.compareVersions("v2.1.0", "2.1.0"))
        assertEquals(0, UpdateChecker.compareVersions("2.1", "2.1.0"))
    }

    @Test
    fun preReleaseIsOlderThanFinal() {
        assertTrue(UpdateChecker.compareVersions("2.1.0-beta1", "2.1.0") < 0)
        assertTrue(UpdateChecker.compareVersions("2.1.0", "2.1.0-beta1") > 0)
    }
}
