package chat.papo.mobile

import chat.papo.mobile.ui.components.ExternalUriHandler
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalUriHandlerTest {
    private val opened = mutableListOf<String>()
    private val handler = ExternalUriHandler { opened += it; true }

    @Test
    fun httpAndHttpsReachTheOpener() {
        handler.openUri("https://example.com/a")
        handler.openUri("HTTP://example.com")
        assertEquals(listOf("https://example.com/a", "HTTP://example.com"), opened)
    }

    @Test
    fun otherSchemesNeverReachTheOpener() {
        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "content://a/b", "mailto:a@b.c", "tel:123", "https://", "not a link", "")
            .forEach { handler.openUri(it) }
        assertEquals(emptyList(), opened)
    }

    @Test
    fun anOpenerThatFailsDoesNotBreakTheHandler() {
        ExternalUriHandler { false }.openUri("https://example.com")
    }
}
