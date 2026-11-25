package chat.papo.mobile

import chat.papo.mobile.chat.EmojiRef
import chat.papo.mobile.chat.RecentEmojis
import chat.papo.mobile.data.db.account.AccountDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class RecentEmojisTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun close() = scope.cancel()

    private val thumbs = EmojiRef.Unicode("👍")
    private val heart = EmojiRef.Unicode("❤️")
    private val custom = EmojiRef.Custom("0b5f6a48-0000-4000-8000-000000000001")

    @Test
    fun oMaisRecenteVemPrimeiroESemRepetir() = runBlocking {
        val recent = RecentEmojis(AccountDb(TestResources.driver()).metaQueries, scope, Dispatchers.IO)
        recent.used(thumbs)
        recent.used(custom)
        recent.used(heart)
        recent.used(thumbs)
        assertEquals(listOf(thumbs, heart, custom), recent.items.value)
    }

    @Test
    fun guardaNoMaximo32() = runBlocking {
        val recent = RecentEmojis(AccountDb(TestResources.driver()).metaQueries, scope, Dispatchers.IO)
        repeat(40) { recent.used(EmojiRef.Unicode("e$it")) }
        assertEquals(32, recent.items.value.size)
        assertEquals(EmojiRef.Unicode("e39"), recent.items.value.first())
        assertEquals(EmojiRef.Unicode("e8"), recent.items.value.last())
    }

    @Test
    fun sobreviveAoReinicioDaConta() = runBlocking {
        val meta = AccountDb(TestResources.driver()).metaQueries
        val first = RecentEmojis(meta, scope, Dispatchers.IO)
        first.used(thumbs)
        first.used(custom)
        // A gravação é assíncrona: espera o banco ter o valor final antes de reabrir.
        val expected = RecentEmojis.encode(listOf(custom, thumbs))
        withTimeout(10.seconds) {
            while (meta.get(RecentEmojis.KEY).executeAsOneOrNull() != expected) delay(10)
        }
        val reopened = RecentEmojis(meta, scope, Dispatchers.IO)
        assertEquals(listOf(custom, thumbs), withTimeout(10.seconds) { reopened.items.first { it.isNotEmpty() } })
    }

    @Test
    fun cadaContaTemOsSeus() = runBlocking {
        val a = RecentEmojis(AccountDb(TestResources.driver()).metaQueries, scope, Dispatchers.IO)
        val b = RecentEmojis(AccountDb(TestResources.driver()).metaQueries, scope, Dispatchers.IO)
        a.used(thumbs)
        assertEquals(emptyList(), b.items.value)
    }

    @Test
    fun codificaEDecodificaIgnorandoLixo() {
        val items = listOf(thumbs, custom, heart)
        assertEquals(items, RecentEmojis.decode(RecentEmojis.encode(items)))
        assertEquals(emptyList(), RecentEmojis.decode(null))
        assertEquals(listOf(thumbs), RecentEmojis.decode("x:abc\nu:\n${"u:" + "a".repeat(80)}\nu:👍\nu:👍"))
    }
}
