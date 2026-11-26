package chat.papo.mobile

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
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

    @Test
    fun canalSemEscolhaMostraOValorDoServidorParaOsRotulosDePadrao() {
        val server = ServerNotificationSettings(NotificationLevel.All, suppressEveryone = true, suppressMentions = false, sync = false)
        val state = channelNotificationsState(server, ChannelNotificationSettings(), now, syncAvailable = false)
        assertNull(state.level)
        assertEquals(NotificationLevel.All, state.serverLevel)
        assertNull(state.suppressEveryone)
        assertTrue(state.serverSuppressEveryone)
        assertNull(state.suppressMentions)
        assertFalse(state.serverSuppressMentions)
        assertNull(state.sync)
        assertFalse(state.serverSync)
        assertNull(state.mute)
        assertFalse(state.canUnmute)
    }

    @Test
    fun canalComEscolhaMostraAEscolhaEOValorDoServidorAoLado() {
        val channel = ChannelNotificationSettings(NotificationLevel.Nothing, suppressEveryone = false, suppressMentions = true, sync = true)
        val state = channelNotificationsState(ServerNotificationSettings(), channel, now, syncAvailable = true)
        assertEquals(NotificationLevel.Nothing, state.level)
        assertEquals(NotificationLevel.OnlyMentions, state.serverLevel)
        assertEquals(false, state.suppressEveryone)
        assertEquals(true, state.suppressMentions)
        assertEquals(true, state.sync)
        assertTrue(state.syncAvailable)
    }

    @Test
    fun oAvisoDeSincronizacaoVemDoBackendNaoSerGuardado() {
        val off = channelNotificationsState(ServerNotificationSettings(), ChannelNotificationSettings(), now, syncAvailable = false)
        assertFalse(off.syncAvailable)
        assertFalse(dmNotificationsState(ServerNotificationSettings(), DmNotificationSettings(), now, syncAvailable = false).syncAvailable)
        assertFalse(serverNotificationsState(ServerNotificationSettings(), now, syncAvailable = false).syncAvailable)
        assertTrue(serverNotificationsState(ServerNotificationSettings(), now, syncAvailable = true).syncAvailable)
    }

    @Test
    fun silencioDoCanalPodeSerReativado() {
        val channel = ChannelNotificationSettings(mute = Mute(now + 1.hours))
        val state = channelNotificationsState(ServerNotificationSettings(), channel, now, syncAvailable = false)
        assertEquals(ActiveMute(MuteSource.Channel, now + 1.hours), state.mute)
        assertTrue(state.canUnmute)
    }

    @Test
    fun silencioHerdadoDoServidorNaoPodeSerReativadoNoCanal() {
        val server = ServerNotificationSettings(mute = Mute(null))
        val state = channelNotificationsState(server, ChannelNotificationSettings(), now, syncAvailable = false)
        assertEquals(ActiveMute(MuteSource.Server, null), state.mute)
        assertFalse(state.canUnmute)
    }

    @Test
    fun silencioQueAcabouNaoApareceNemNoCanalNemNoServidor() {
        val server = ServerNotificationSettings(mute = Mute(now))
        assertNull(channelNotificationsState(server, ChannelNotificationSettings(mute = Mute(now - 1.minutes)), now, false).mute)
        assertNull(serverNotificationsState(server, now, false).mute)
        assertFalse(serverNotificationsState(server, now, false).canUnmute)
    }

    @Test
    fun dmSoMostraOSilencioDelaENaoOdoServidor() {
        val server = ServerNotificationSettings(mute = Mute(null), sync = false)
        val none = dmNotificationsState(server, DmNotificationSettings(), now, syncAvailable = false)
        assertNull(none.mute)
        assertFalse(none.canUnmute)
        assertFalse(none.serverSync)
        val own = dmNotificationsState(server, DmNotificationSettings(Mute(now + 15.minutes), sync = true), now, syncAvailable = false)
        assertEquals(ActiveMute(MuteSource.Channel, now + 15.minutes), own.mute)
        assertTrue(own.canUnmute)
        assertEquals(true, own.sync)
    }

    @Test
    fun servidorMostraSuasEscolhasEOSilencioComOrigemServidor() {
        val server = ServerNotificationSettings(NotificationLevel.Nothing, suppressEveryone = true, suppressMentions = true, mute = Mute(now + 3.hours), sync = false)
        val state = serverNotificationsState(server, now, syncAvailable = false)
        assertEquals(NotificationLevel.Nothing, state.level)
        assertTrue(state.suppressEveryone)
        assertTrue(state.suppressMentions)
        assertFalse(state.sync)
        assertEquals(ActiveMute(MuteSource.Server, now + 3.hours), state.mute)
        assertTrue(state.canUnmute)
        assertNull(serverNotificationsState(ServerNotificationSettings(), now, false).mute)
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
