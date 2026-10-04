package com.awesomephoto

import org.junit.Assert.*
import org.junit.Test

class PgyerUpdateCheckerTest {
    @Test fun publicPageProvidesVersionAndNotes() {
        val page = javaClass.getResource("/pgyer-public-page.html")!!.readText()
        val update = PgyerUpdateChecker.parse(page)!!
        assertEquals("1.0.9", update.version)
        assertTrue(update.notes.contains("支持查看照片评分口径与原因"))
        assertTrue(update.notes.contains("\n### Features\n"))
        assertFalse(update.notes.contains("<br"))
        assertTrue(PgyerUpdateChecker.isNewer(update.version, "1.0.8"))
        assertFalse(PgyerUpdateChecker.isNewer(update.version, "1.0.9"))
        assertFalse(PgyerUpdateChecker.isNewer(update.version, "1.0.10"))
    }

    @Test fun jsonFormatPreservesEscapedNotes() {
        val update = PgyerUpdateChecker.parse("""{"buildVersion":"1.0.10","buildUpdateDescription":"第一行\n\"修复\""}""")!!
        assertEquals("1.0.10", update.version)
        assertEquals("第一行\n\"修复\"", update.notes)
    }

    @Test fun unrelatedAndHistoryPagesDoNotOfferUpdates() {
        assertNull(PgyerUpdateChecker.parse("<h1>访问受限</h1><td>1.0.8 (build 3)</td>"))
        assertNull(PgyerUpdateChecker.parse("aVersion = '';"))
    }
}
