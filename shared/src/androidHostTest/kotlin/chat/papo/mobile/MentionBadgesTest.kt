package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.InboxState
import chat.papo.mobile.chat.MentionBadges
import chat.papo.mobile.chat.MentionCounts
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.NotificationItem
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.totalOf
import chat.papo.mobile.data.db.account.AccountDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** O número de menções de uma conta: o que entra, o que a supressão tira e o que silenciar não muda. */
@OptIn(ExperimentalCoroutinesApi::class)
class MentionBadgesTest {
    private val me = "abcdef01-1111-4111-8111-111111111111"
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val mention = "oi @mention(<@$me>)"
    private val everyone = "aviso @everyone"
    private val reply = "concordo"

    private fun item(id: String, channel: String, content: String, read: Boolean = false) = NotificationItem(
        id, "m-$id", channel, authorId = "u2", webhookId = null, externalAuthor = null, content = content, read = read,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun channel(id: String, type: ChannelType = ChannelType.Text) = Channel(
        id, id, type, 0, null, null, null, null, null, null, null, null,
    )

    private fun dm(id: String, unread: Int) = Dm(id, "u2", null, null, null, unread)

    private class Fixture(scope: TestScope, db: AccountDb, me: String) {
        val inbox = MutableStateFlow(InboxState(loaded = true))
        val dms = MutableStateFlow(emptyList<Dm>())
        val channels = MutableStateFlow(emptyList<Channel>())
        private var skew = 0
        val prefs = NotificationPrefs(db.metaQueries, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler)) {
            Instant.fromEpochMilliseconds(1_700_000_000_000) + scope.testScheduler.currentTime.milliseconds
        }
        val badges = MentionBadges(me, inbox, dms, channels, prefs, scope.backgroundScope)

        fun rows(vararg items: NotificationItem) {
            inbox.value = inbox.value.copy(items = items.toList())
        }
    }

    private fun TestScope.fixture(): Fixture {
        val f = Fixture(this, AccountDb(TestResources.driver()), me)
        f.channels.value = listOf(channel("c1"), channel("c2"), channel("voz", ChannelType.Voice))
        runCurrent()
        return f
    }

    @Test
    fun contaMencaoRespostaEEveryoneNaoLidasPorCanal() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", mention), item("b", "c1", reply), item("c", "c2", everyone), item("d", "c2", mention, read = true))
        runCurrent()

        val counts = f.badges.mentions.value

        assertEquals(mapOf("c1" to 2, "c2" to 1), counts.perChannel)
        assertEquals(3, counts.total)
    }

    @Test
    fun canalSilenciadoEServidorSilenciadoContamMesmoAssim() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", mention), item("b", "c2", everyone))
        f.prefs.updateChannel("c1") { it.copy(mute = Mute(null)) }
        f.prefs.updateServer { it.copy(mute = Mute(null)) }
        runCurrent()

        assertEquals(mapOf("c1" to 1, "c2" to 1), f.badges.mentions.value.perChannel)
    }

    @Test
    fun nivelNadaNaoMudaONumero() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", mention))
        f.prefs.updateServer { it.copy(level = chat.papo.mobile.chat.NotificationLevel.Nothing) }
        runCurrent()

        assertEquals(1, f.badges.mentions.value.total)
    }

    @Test
    fun everyoneSuprimidoNaoContaMasMencaoSim() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", mention), item("b", "c1", everyone))
        f.prefs.updateServer { it.copy(suppressEveryone = true) }
        runCurrent()

        assertEquals(mapOf("c1" to 1), f.badges.mentions.value.perChannel)
    }

    @Test
    fun mencoesESuprimidasTiramMencaoERespostaMasNaoEveryone() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", mention), item("b", "c1", reply), item("c", "c1", everyone))
        f.prefs.updateServer { it.copy(suppressMentions = true) }
        runCurrent()

        assertEquals(mapOf("c1" to 1), f.badges.mentions.value.perChannel)
    }

    @Test
    fun oCanalPodeVoltarAContarOQueOServidorSuprime() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", everyone), item("b", "c2", everyone))
        f.prefs.updateServer { it.copy(suppressEveryone = true) }
        f.prefs.updateChannel("c2") { it.copy(suppressEveryone = false) }
        runCurrent()

        assertEquals(mapOf("c2" to 1), f.badges.mentions.value.perChannel)
    }

    @Test
    fun canalQueAContaNaoVeOuNaoConheceNaoConta() = runTest {
        val f = fixture()
        f.rows(item("a", "secreto", mention), item("b", "dm-fechada", mention), item("c", "c1", mention))
        runCurrent()

        assertEquals(mapOf("c1" to 1), f.badges.mentions.value.perChannel)
    }

    @Test
    fun canalDeVozComChatContaQuandoALeituraEPermitida() = runTest {
        val f = fixture()
        f.rows(item("a", "voz", mention))
        runCurrent()

        assertEquals(mapOf("voz" to 1), f.badges.mentions.value.perChannel)
    }

    @Test
    fun conversaDiretaContaOUnreadCountMesmoSilenciadaEIgnoraAsLinhas() = runTest {
        val f = fixture()
        f.dms.value = listOf(dm("d1", 3), dm("d2", 0))
        f.rows(item("a", "d1", "oi"), item("b", "d1", "oi de novo"))
        f.prefs.updateDm("d1") { it.copy(mute = Mute(null)) }
        runCurrent()

        val counts = f.badges.mentions.value

        assertEquals(mapOf("d1" to 3), counts.perDm)
        assertEquals(emptyMap(), counts.perChannel)
        assertEquals(3, counts.total)
    }

    @Test
    fun suprimirMencoesNaoTiraONumeroDaConversaDireta() = runTest {
        val f = fixture()
        f.dms.value = listOf(dm("d1", 2))
        f.prefs.updateServer { it.copy(suppressMentions = true, suppressEveryone = true) }
        runCurrent()

        assertEquals(2, f.badges.mentions.value.total)
    }

    @Test
    fun refazONumeroQuandoAsConfiguracoesMudam() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", everyone))
        runCurrent()
        assertEquals(1, f.badges.mentions.value.total)

        f.prefs.updateChannel("c1") { it.copy(suppressEveryone = true) }
        runCurrent()
        assertEquals(0, f.badges.mentions.value.total)

        f.prefs.updateChannel("c1") { ChannelNotificationSettings() }
        runCurrent()
        assertEquals(1, f.badges.mentions.value.total)
    }

    @Test
    fun oFimDeUmSilencioRefazAContaESemMudarONumero() = runTest {
        val f = fixture()
        f.rows(item("a", "c1", mention))
        f.prefs.updateChannel("c1") { it.copy(mute = Mute(start + 1.hours)) }
        runCurrent()
        val before = f.badges.mentions.value
        assertEquals(1, before.total)

        advanceTimeBy(1.hours.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(emptyMap(), f.prefs.channels.value) // o silêncio acabou e saiu das configurações
        assertEquals(before, f.badges.mentions.value)
    }

    @Test
    fun oCorteDaCaixaSegueAteAContagem() = runTest {
        val f = fixture()
        f.inbox.value = f.inbox.value.copy(items = listOf(item("a", "c1", mention)), capped = true)
        runCurrent()

        assertEquals(true, f.badges.mentions.value.capped)
    }

    @Test
    fun osCanaisComMencaoSoSaemDepoisDeACaixaSerLida() = runTest {
        val f = fixture()
        f.inbox.value = InboxState(items = listOf(item("a", "c1", mention)), loaded = false)
        runCurrent()
        assertNull(f.badges.mentionedChannels.value)

        f.inbox.value = f.inbox.value.copy(loaded = true)
        runCurrent()
        assertEquals(setOf("c1"), f.badges.mentionedChannels.value)

        f.rows()
        runCurrent()
        assertEquals(emptySet(), f.badges.mentionedChannels.value)
    }

    @Test
    fun duasContasNoMesmoServidorTemNumerosSeparados() = runTest {
        val a = fixture()
        val b = Fixture(this, AccountDb(TestResources.driver()), "outro-usuario")
        b.channels.value = listOf(channel("c1"))
        // O mesmo canal e a mesma mensagem nas duas caixas; só a conta de `a` foi mencionada, e só `b` suprime @everyone.
        a.rows(item("x", "c1", mention), item("y", "c1", everyone))
        b.rows(item("x", "c1", mention), item("y", "c1", everyone))
        b.prefs.updateServer { it.copy(suppressEveryone = true) }
        b.dms.value = listOf(dm("d1", 4))
        runCurrent()

        assertEquals(2, a.badges.mentions.value.total)
        // `b` não foi mencionada: a mesma linha de menção a outro usuário vale como resposta, e o @everyone é suprimido.
        assertEquals(mapOf("c1" to 1), b.badges.mentions.value.perChannel)
        assertEquals(5, b.badges.mentions.value.total)
        assertEquals(7, totalOf(listOf(a.badges.mentions.value, b.badges.mentions.value)).count)
    }

    @Test
    fun aSomaDasContasJuntaOCorte() {
        val a = MentionCounts(mapOf("c1" to 2), emptyMap(), 2, capped = false)
        val b = MentionCounts(emptyMap(), mapOf("d1" to 5), 5, capped = true)

        assertEquals(7, totalOf(listOf(a, b)).count)
        assertEquals(true, totalOf(listOf(a, b)).capped)
        assertEquals(0, totalOf(emptyList()).count)
        assertEquals(false, totalOf(listOf(a)).capped)
    }
}
