package com.intervalcoach.update

import org.junit.Assert.*
import org.junit.Test

class VersionTest {
    @Test fun comparesReleaseVersionsNumerically() {
        assertTrue(isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(isNewerVersion("v2.0.0", "1.9.9"))
        assertFalse(isNewerVersion("1.0.0", "1.0"))
        assertFalse(isNewerVersion("v0.9.0", "1.0.0"))
        assertFalse(isNewerVersion("v2.0.0-beta", "1.0.0"))
    }
}
