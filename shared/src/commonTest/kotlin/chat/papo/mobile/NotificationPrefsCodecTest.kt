package chat.papo.mobile

import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.ServerNotificationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NotificationPrefsCodecTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun servidorIdaEVolta() {
        val values = listOf(
            ServerNotificationSettings(),
            ServerNotificationSettings(NotificationLevel.All, suppressEveryone = true, suppressMentions = true, sync = false),
            ServerNotificationSettings(NotificationLevel.Nothing, mute = Mute(at)),
            ServerNotificationSettings(mute = Mute(null)),
        )
        for (value in values) assertEquals(value, NotificationPrefs.decodeServer(NotificationPrefs.encodeServer(value)))
    }

    @Test
    fun canalIdaEVolta() {
        val values = listOf(
            ChannelNotificationSettings(NotificationLevel.OnlyMentions),
            ChannelNotificationSettings(NotificationLevel.All, suppressEveryone = false, suppressMentions = true, mute = Mute(at), sync = false),
            ChannelNotificationSettings(mute = Mute(null)),
            ChannelNotificationSettings(suppressEveryone = true, sync = true),
        )
        for (value in values) assertEquals(value, NotificationPrefs.decodeChannel(NotificationPrefs.encodeChannel(value)))
    }

    @Test
    fun dmIdaEVolta() {
        val values = listOf(DmNotificationSettings(Mute(at + 15.minutes)), DmNotificationSettings(Mute(null)))
        for (value in values) assertEquals(value, NotificationPrefs.decodeDm(NotificationPrefs.encodeDm(value)))
    }

    @Test
    fun semValorOuComLixoValemOsPadroes() {
        for (junk in listOf(null, "", "not json", "[]", "{\"sync\":\"yes\"}", "{\"muted\":5}", "{\"mutedUntil\":\"soon\"}")) {
            assertNull(NotificationPrefs.decodeServer(junk), "servidor: $junk")
            assertNull(NotificationPrefs.decodeChannel(junk), "canal: $junk")
            assertNull(NotificationPrefs.decodeDm(junk), "dm: $junk")
        }
    }

    @Test
    fun nivelDesconhecidoCaiNoPadraoDoServidorENoHerdadoDoCanal() {
        val json = "{\"level\":\"loud\",\"suppressEveryone\":true}"
        assertEquals(ServerNotificationSettings(suppressEveryone = true), NotificationPrefs.decodeServer(json))
        assertEquals(ChannelNotificationSettings(level = null, suppressEveryone = true), NotificationPrefs.decodeChannel(json))
    }

    @Test
    fun campoNovoDeOutraVersaoEIgnorado() {
        assertEquals(
            ServerNotificationSettings(NotificationLevel.All),
            NotificationPrefs.decodeServer("{\"level\":\"all\",\"badge\":true}"),
        )
    }

    @Test
    fun valorVazioOuSemNadaEscolhidoNaoViraEntrada() {
        assertNull(NotificationPrefs.decodeChannel("{}"))
        assertNull(NotificationPrefs.decodeChannel("{\"level\":\"loud\"}"))
        assertNull(NotificationPrefs.decodeDm("{}"))
        assertEquals(ServerNotificationSettings(), NotificationPrefs.decodeServer("{}"))
    }

    @Test
    fun mutedFalsoIgnoraOFimEMutedSemFimEAteReativar() {
        assertEquals(
            ServerNotificationSettings(),
            NotificationPrefs.decodeServer("{\"muted\":false,\"mutedUntil\":${at.toEpochMilliseconds()}}"),
        )
        assertEquals(DmNotificationSettings(Mute(null)), NotificationPrefs.decodeDm("{\"muted\":true}"))
        assertEquals(DmNotificationSettings(Mute(at)), NotificationPrefs.decodeDm("{\"muted\":true,\"mutedUntil\":${at.toEpochMilliseconds()}}"))
    }

    @Test
    fun chavesTemOPrefixoEUmaPorEntrada() {
        assertEquals("notif:server", NotificationPrefs.SERVER_KEY)
        assertEquals("notif:channel:c1", NotificationPrefs.channelKey("c1"))
        assertEquals("notif:dm:d1", NotificationPrefs.dmKey("d1"))
    }

    @Test
    fun oFimMaisProximoEntreServidorCanaisEDms() {
        val server = ServerNotificationSettings(mute = Mute(at + 3.hours))
        val channels = mapOf(
            "a" to ChannelNotificationSettings(mute = Mute(at + 2.hours)),
            "b" to ChannelNotificationSettings(level = NotificationLevel.All),
            "c" to ChannelNotificationSettings(mute = Mute(null)),
        )
        val dms = mapOf("d" to DmNotificationSettings(Mute(at + 1.hours)))
        assertEquals(at + 1.hours, NotificationPrefs.nearestEnd(server, channels, dms))
        assertEquals(at + 2.hours, NotificationPrefs.nearestEnd(server, channels, emptyMap()))
        assertEquals(at + 3.hours, NotificationPrefs.nearestEnd(server, emptyMap(), emptyMap()))
    }

    @Test
    fun semSilencioComFimNaoHaProximoFim() {
        assertNull(NotificationPrefs.nearestEnd(ServerNotificationSettings(), emptyMap(), emptyMap()))
        assertNull(NotificationPrefs.nearestEnd(ServerNotificationSettings(mute = Mute(null)), mapOf("a" to ChannelNotificationSettings(mute = Mute(null))), emptyMap()))
    }
}
