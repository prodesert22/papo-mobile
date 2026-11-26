package chat.papo.mobile

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.chat.effective
import chat.papo.mobile.chat.effectiveDm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NotificationSettingsTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val later = now + 1.hours
    private val server = ServerNotificationSettings()

    @Test
    fun padroesDoServidor() {
        assertEquals(NotificationLevel.OnlyMentions, server.level)
        assertFalse(server.suppressEveryone)
        assertFalse(server.suppressMentions)
        assertNull(server.mute)
        assertTrue(server.sync)
    }

    @Test
    fun nivelVaiEVoltaPeloTextoDoBackend() {
        assertEquals(listOf("all", "only_mentions", "off"), NotificationLevel.entries.map { it.wire })
        for (level in NotificationLevel.entries) assertEquals(level, NotificationLevel.fromWire(level.wire))
        assertNull(NotificationLevel.fromWire("loud"))
        assertNull(NotificationLevel.fromWire(null))
    }

    @Test
    fun silencioSemFimValeSempre() {
        assertTrue(Mute(null).isActive(now))
        assertTrue(Mute().isActive(now + 100_000.hours))
    }

    @Test
    fun silencioComFimValeAteOInstanteDoFimExcluindoEle() {
        val mute = Mute(now)
        assertTrue(mute.isActive(now - 1.milliseconds))
        assertFalse(mute.isActive(now))
        assertFalse(mute.isActive(now + 1.milliseconds))
    }

    @Test
    fun temposDeSilencio() {
        val lengths = MuteDuration.entries.map { it.length }
        assertEquals(listOf<Duration?>(15.minutes, 1.hours, 3.hours, 8.hours, 24.hours, null), lengths)
        assertEquals(Mute(now + 15.minutes), MuteDuration.Minutes15.from(now))
        assertEquals(Mute(now + 24.hours), MuteDuration.Hours24.from(now))
        assertEquals(Mute(null), MuteDuration.UntilReenabled.from(now))
    }

    @Test
    fun canalSemEscolhaSegueOServidorEmTudo() {
        val custom = ServerNotificationSettings(NotificationLevel.All, suppressEveryone = true, suppressMentions = true, sync = false)
        val result = effective(custom, ChannelNotificationSettings(), now)
        assertEquals(NotificationLevel.All, result.level)
        assertTrue(result.suppressEveryone)
        assertTrue(result.suppressMentions)
        assertFalse(result.sync)
        assertNull(result.mute)
        assertFalse(result.muted)
    }

    @Test
    fun canalComEscolhaVenceOServidorCampoACampo() {
        val custom = ServerNotificationSettings(NotificationLevel.Nothing, suppressEveryone = true, suppressMentions = true, sync = true)
        val channel = ChannelNotificationSettings(NotificationLevel.All, suppressEveryone = false, suppressMentions = false, sync = false)
        val result = effective(custom, channel, now)
        assertEquals(NotificationLevel.All, result.level)
        assertFalse(result.suppressEveryone)
        assertFalse(result.suppressMentions)
        assertFalse(result.sync)
    }

    @Test
    fun cadaCampoHerdaSozinho() {
        val custom = ServerNotificationSettings(NotificationLevel.All, suppressEveryone = true, suppressMentions = false)
        val result = effective(custom, ChannelNotificationSettings(suppressMentions = true), now)
        assertEquals(NotificationLevel.All, result.level)
        assertTrue(result.suppressEveryone)
        assertTrue(result.suppressMentions)
        assertTrue(result.sync)
    }

    @Test
    fun silencioDoCanalDizDeOndeVemEQuandoAcaba() {
        val result = effective(server, ChannelNotificationSettings(mute = Mute(later)), now)
        assertEquals(ActiveMute(MuteSource.Channel, later), result.mute)
        assertTrue(result.muted)
    }

    @Test
    fun canalSegueOSilencioDoServidor() {
        val result = effective(ServerNotificationSettings(mute = Mute(later)), ChannelNotificationSettings(), now)
        assertEquals(ActiveMute(MuteSource.Server, later), result.mute)
        assertTrue(result.muted)
    }

    @Test
    fun silencioDoCanalVenceOSilencioDoServidor() {
        val result = effective(ServerNotificationSettings(mute = Mute(later)), ChannelNotificationSettings(mute = Mute(null)), now)
        assertEquals(ActiveMute(MuteSource.Channel, null), result.mute)
    }

    @Test
    fun silencioDoCanalQueAcabouDeixaValerOSilencioDoServidor() {
        val result = effective(ServerNotificationSettings(mute = Mute(later)), ChannelNotificationSettings(mute = Mute(now)), now)
        assertEquals(ActiveMute(MuteSource.Server, later), result.mute)
    }

    @Test
    fun silencioQueAcabouContaComoNaoSilenciado() {
        val result = effective(ServerNotificationSettings(mute = Mute(now)), ChannelNotificationSettings(mute = Mute(now - 1.minutes)), now)
        assertNull(result.mute)
        assertFalse(result.muted)
    }

    @Test
    fun entradaSoComPadroesEPadrao() {
        assertTrue(ChannelNotificationSettings().isDefault)
        assertFalse(ChannelNotificationSettings(level = NotificationLevel.All).isDefault)
        assertFalse(ChannelNotificationSettings(suppressEveryone = false).isDefault)
        assertFalse(ChannelNotificationSettings(suppressMentions = false).isDefault)
        assertFalse(ChannelNotificationSettings(mute = Mute()).isDefault)
        assertFalse(ChannelNotificationSettings(sync = true).isDefault)
        assertTrue(DmNotificationSettings().isDefault)
        assertFalse(DmNotificationSettings(mute = Mute()).isDefault)
        assertFalse(DmNotificationSettings(sync = false).isDefault)
    }

    @Test
    fun dmSemEscolhaNaoEstaSilenciadaEUsaASincronizacaoDoServidor() {
        val result = effectiveDm(ServerNotificationSettings(sync = false), DmNotificationSettings(), now)
        assertNull(result.mute)
        assertFalse(result.muted)
        assertFalse(result.sync)
    }

    @Test
    fun dmTemOSeuSilencioEAPropriaSincronizacao() {
        val result = effectiveDm(ServerNotificationSettings(sync = false), DmNotificationSettings(Mute(later), sync = true), now)
        assertEquals(ActiveMute(MuteSource.Channel, later), result.mute)
        assertTrue(result.muted)
        assertTrue(result.sync)
    }

    @Test
    fun silenciarOServidorNaoCalaADm() {
        val result = effectiveDm(ServerNotificationSettings(mute = Mute(null)), DmNotificationSettings(), now)
        assertNull(result.mute)
    }

    @Test
    fun silencioDeDmQueAcabouContaComoNaoSilenciado() {
        assertNull(effectiveDm(server, DmNotificationSettings(Mute(now)), now).mute)
        assertTrue(effectiveDm(server, DmNotificationSettings(Mute(now + 1.milliseconds)), now).muted)
    }
}
