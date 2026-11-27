package chat.papo.mobile

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.LevelFailure
import chat.papo.mobile.chat.LevelFailureReason
import chat.papo.mobile.chat.LevelSave
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.ui.notifications.channelNotificationsState
import chat.papo.mobile.ui.notifications.dmNotificationsState
import chat.papo.mobile.ui.notifications.muteEndText
import chat.papo.mobile.ui.notifications.serverNotificationsState
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NotificationUiStateTest {
    // 2023-11-14 22:13:20 UTC
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun channelState(
        server: ServerNotificationSettings = ServerNotificationSettings(),
        channel: ChannelNotificationSettings = ChannelNotificationSettings(),
        serverLevel: NotificationLevel = NotificationLevel.OnlyMentions,
        save: LevelSave = LevelSave(),
    ) = channelNotificationsState("c1", server, channel, serverLevel, save, now)

    @Test
    fun canalSincronizadoMostraONivelDoServidorSemOpcaoDePadrao() {
        val state = channelState(serverLevel = NotificationLevel.All)
        assertTrue(state.synced)
        assertEquals(NotificationLevel.All, state.serverLevel)
        assertNull(state.level) // nada escolhido neste aparelho
        assertNull(state.sync) // a sincronização segue o servidor
        assertTrue(state.serverSync)
    }

    @Test
    fun canalSemSincronizacaoMostraAEscolhaDoAparelhoEOGeralParaOPadrao() {
        val server = ServerNotificationSettings(level = NotificationLevel.Nothing, sync = false)
        val none = channelState(server)
        assertFalse(none.synced)
        assertNull(none.level)
        assertEquals(NotificationLevel.Nothing, none.defaultLevel)
        assertFalse(none.serverSync)
        val own = channelState(ServerNotificationSettings(), ChannelNotificationSettings(NotificationLevel.All, sync = false))
        assertFalse(own.synced)
        assertEquals(NotificationLevel.All, own.level)
        assertEquals(false, own.sync)
    }

    @Test
    fun aSincronizacaoDoCanalVenceAdoServidor() {
        assertTrue(channelState(ServerNotificationSettings(sync = false), ChannelNotificationSettings(sync = true)).synced)
        assertFalse(channelState(ServerNotificationSettings(sync = true), ChannelNotificationSettings(sync = false)).synced)
    }

    @Test
    fun suprimirMostraAEscolhaDoCanalEOValorDoServidor() {
        val server = ServerNotificationSettings(suppressEveryone = true, suppressMentions = false)
        val inherit = channelState(server)
        assertNull(inherit.suppressEveryone)
        assertTrue(inherit.serverSuppressEveryone)
        assertNull(inherit.suppressMentions)
        assertFalse(inherit.serverSuppressMentions)
        val own = channelState(server, ChannelNotificationSettings(suppressEveryone = false, suppressMentions = true))
        assertEquals(false, own.suppressEveryone)
        assertEquals(true, own.suppressMentions)
    }

    @Test
    fun gravandoOEstadoDizSoParaOCanalCerto() {
        val save = LevelSave(savingChannelId = "c1")
        assertTrue(channelState(save = save).saving)
        assertFalse(channelNotificationsState("c2", ServerNotificationSettings(), ChannelNotificationSettings(), NotificationLevel.All, save, now).saving)
        assertFalse(channelState().saving)
    }

    @Test
    fun aFalhaApareceSoNoCanalAQueSeRefere() {
        val save = LevelSave(failure = LevelFailure("c1", LevelFailureReason.RateLimited))
        assertEquals(LevelFailureReason.RateLimited, channelState(save = save).failure)
        assertNull(channelNotificationsState("c2", ServerNotificationSettings(), ChannelNotificationSettings(), NotificationLevel.All, save, now).failure)
        assertNull(channelState().failure)
    }

    @Test
    fun silencioDoCanalPodeSerReativado() {
        val state = channelState(channel = ChannelNotificationSettings(mute = Mute(now + 1.hours)))
        assertEquals(ActiveMute(MuteSource.Channel, now + 1.hours), state.mute)
        assertTrue(state.canUnmute)
    }

    @Test
    fun silencioHerdadoDoServidorNaoPodeSerReativadoNoCanal() {
        val state = channelState(ServerNotificationSettings(mute = Mute(null)))
        assertEquals(ActiveMute(MuteSource.Server, null), state.mute)
        assertFalse(state.canUnmute)
    }

    @Test
    fun silencioQueAcabouNaoApareceNemNoCanalNemNoServidor() {
        val server = ServerNotificationSettings(mute = Mute(now))
        assertNull(channelState(server, ChannelNotificationSettings(mute = Mute(now - 1.minutes))).mute)
        assertNull(serverNotificationsState(server, now).mute)
        assertFalse(serverNotificationsState(server, now).canUnmute)
    }

    @Test
    fun dmSoMostraOSilencioDelaENaoOdoServidor() {
        val server = ServerNotificationSettings(mute = Mute(null))
        val none = dmNotificationsState(server, DmNotificationSettings(), now)
        assertNull(none.mute)
        assertFalse(none.canUnmute)
        val own = dmNotificationsState(server, DmNotificationSettings(Mute(now + 15.minutes)), now)
        assertEquals(ActiveMute(MuteSource.Channel, now + 15.minutes), own.mute)
        assertTrue(own.canUnmute)
    }

    @Test
    fun servidorMostraSuasEscolhasEOSilencioComOrigemServidor() {
        val server = ServerNotificationSettings(NotificationLevel.Nothing, suppressEveryone = true, suppressMentions = true, mute = Mute(now + 3.hours), sync = false)
        val state = serverNotificationsState(server, now)
        assertEquals(NotificationLevel.Nothing, state.level)
        assertTrue(state.suppressEveryone)
        assertTrue(state.suppressMentions)
        assertFalse(state.sync)
        assertEquals(ActiveMute(MuteSource.Server, now + 3.hours), state.mute)
        assertTrue(state.canUnmute)
        assertNull(serverNotificationsState(ServerNotificationSettings(), now).mute)
    }

    @Test
    fun fimDoSilencioDeHojeMostraSoAHora() {
        assertEquals("23:00", (now + 47.minutes).muteEndText(now, TimeZone.UTC))
        assertEquals("22:13", now.muteEndText(now, TimeZone.UTC))
    }

    @Test
    fun fimDoSilencioDeOutroDiaMostraADataEAHora() {
        assertEquals("15/11/2023 22:13", (now + 24.hours).muteEndText(now, TimeZone.UTC))
        assertEquals("15/11/2023 00:00", (now + 107.minutes).muteEndText(now, TimeZone.UTC))
    }

    @Test
    fun ofusoDoAparelhoDecideOQueEHoje() {
        // Em Tóquio (UTC+9) são 07:13 do dia 15: mais 107 minutos ainda é hoje, mais 24 horas é amanhã.
        val tokyo = TimeZone.of("Asia/Tokyo")
        assertEquals("09:00", (now + 107.minutes).muteEndText(now, tokyo))
        assertEquals("16/11/2023 07:13", (now + 24.hours).muteEndText(now, tokyo))
    }
}
