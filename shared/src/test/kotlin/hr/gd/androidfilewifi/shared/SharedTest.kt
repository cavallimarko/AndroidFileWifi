package hr.gd.androidfilewifi.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathSanitizerTest {
    @Test fun blocksTraversal() {
        assertEquals(listOf("etc", "passwd"), PathSanitizer.segments("../../etc/passwd"))
        assertEquals(listOf("a", "b"), PathSanitizer.segments("a/./b"))
    }

    @Test fun blocksAbsoluteAndDrive() {
        assertEquals(listOf("Windows", "system32"), PathSanitizer.segments("/Windows/system32"))
        assertEquals(listOf("C_", "x"), PathSanitizer.segments("C:/x"))
        assertEquals(listOf("server", "share"), PathSanitizer.segments("\\\\server\\share"))
    }

    @Test fun replacesIllegalCharsAndReservedNames() {
        assertEquals("a_b_c", PathSanitizer.segment("a:b*c"))
        assertEquals("_CON.txt", PathSanitizer.segment("CON.txt"))
        assertEquals("name", PathSanitizer.segment("name. "))
    }

    @Test fun neverEmpty() {
        assertEquals(listOf("_"), PathSanitizer.segments(""))
        assertEquals(listOf("_"), PathSanitizer.segments("../.."))
        assertTrue(PathSanitizer.segment("   ").isNotEmpty())
    }

    @Test fun truncatesLongNames() {
        assertTrue(PathSanitizer.segment("x".repeat(500)).length <= 120)
    }
}

class PairingLinkTest {
    private val fp = "ab".repeat(32)

    @Test fun roundTrip() {
        val link = PairingLink("192.168.1.20", 47800, fp, "123456", "My PC & Co")
        assertEquals(link, PairingLink.parse(link.toUri()))
    }

    @Test fun rejectsGarbage() {
        assertNull(PairingLink.parse("http://x"))
        assertNull(PairingLink.parse("afw://host:notaport?fp=$fp"))
        assertNull(PairingLink.parse("afw://1.2.3.4:47800?fp=short"))
        assertNull(PairingLink.parse("afw://1.2.3.4:47800"))
    }

    @Test fun parsesWithoutCode() {
        assertNotNull(PairingLink.parse("afw://10.0.0.5:47801?fp=$fp"))
    }
}
