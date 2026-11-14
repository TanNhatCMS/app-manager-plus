package com.app.manager.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `equal versions compare as zero`() {
        assertEquals(0, VersionComparator.compare("21.04.223", "21.04.223"))
    }

    @Test
    fun `missing parts are treated as zero`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
    }

    @Test
    fun `newer numeric parts win`() {
        assertEquals(1, VersionComparator.compare("21.13.163", "21.04.223"))
        assertEquals(-1, VersionComparator.compare("20.40.45", "21.04.223"))
    }

    @Test
    fun `prerelease ranks below its own release`() {
        // On numbers alone "7.1.0-dev.5" reads as [7,1,0,5] and would beat stable "7.1.0"
        assertEquals(-1, VersionComparator.compare("7.1.0-dev.5", "7.1.0"))
        assertEquals(1, VersionComparator.compare("7.1.1-dev.5", "7.1.0"))
    }

    @Test
    fun `whitespace and brackets read as separators`() {
        // "2.0.3 (41-d04e542)" is the same build as "2.0.3.41-d04e542" — the app used to report
        // an update forever for such versionNames
        assertEquals(0, VersionComparator.compare("2.0.3 (41-d04e542)", "2.0.3.41-d04e542"))
        assertEquals(-1, VersionComparator.compare("2.0.3 (41-d04e542)", "2.0.3.42"))
    }

    @Test
    fun `blank or unparseable versions compare as equal`() {
        assertEquals(0, VersionComparator.compare("", "1.0.0"))
        assertEquals(0, VersionComparator.compare("1.0.0", ""))
    }
}
