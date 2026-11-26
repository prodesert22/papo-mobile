package chat.papo.mobile

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.NotificationSettingsRemote
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.ui.notifications.NotificationEdits
import chat.papo.mobile.ui.notifications.channelNotificationsState
import chat.papo.mobile.ui.notifications.dmNotificationsState
import chat.papo.mobile.ui.notifications.serverNotificationsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationEditsTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun TestScope.prefs(remote: NotificationSettingsRemote? = null): NotificationPrefs {
        val meta = AccountDb(TestResources.driver()).metaQueries
        val clock = { start + testScheduler.currentTime.milliseconds }
        return if (remote == null) {
            NotificationPrefs(meta, backgroundScope, StandardTestDispatcher(testScheduler), now = clock)
        } else {
            NotificationPrefs(meta, backgroundScope, StandardTestDispatcher(testScheduler), remote, clock)
        }
    }

    private fun TestScope.edits(prefs: NotificationPrefs) = NotificationEdits(prefs) { start }

    @Test
    fun cadaEscolhaDoServidorChamaOSetterCerto() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.setServerLevel(NotificationLevel.Nothing)
        edits.setServerSuppressEveryone(true)
        edits.setServerSuppressMentions(true)
        edits.setServerSync(false)
        assertEquals(
            ServerNotificationSettings(NotificationLevel.Nothing, suppressEveryone = true, suppressMentions = true, sync = false),
            prefs.server.value,
        )
        edits.setServerSuppressEveryone(false)
        assertFalse(prefs.server.value.suppressEveryone)
        assertTrue(prefs.server.value.suppressMentions)
    }

    @Test
    fun silenciarOServidorUsaOTempoEscolhidoEReativarVoltaAoNaoSilenciado() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        for (duration in MuteDuration.entries) {
            edits.muteServer(duration)
            assertEquals(duration.from(start), prefs.server.value.mute)
        }
        assertEquals(Mute(null), prefs.server.value.mute) // o último foi "até reativar"
        edits.unmuteServer()
        assertNull(prefs.server.value.mute)
        assertEquals(ServerNotificationSettings(), prefs.server.value)
    }

    @Test
    fun cadaEscolhaDoCanalChamaOSetterCertoSoNaquelaEntrada() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.setChannelLevel("c1", NotificationLevel.All)
        edits.setChannelSuppressEveryone("c1", true)
        edits.setChannelSuppressMentions("c1", false)
        edits.setChannelSync("c1", false)
        assertEquals(
            ChannelNotificationSettings(NotificationLevel.All, suppressEveryone = true, suppressMentions = false, sync = false),
            prefs.channels.value["c1"],
        )
        assertEquals(setOf("c1"), prefs.channels.value.keys)
        assertEquals(ServerNotificationSettings(), prefs.server.value)
    }

    @Test
    fun usarOPadraoDoServidorVoltaOCampoParaNulo() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.setChannelLevel("c1", NotificationLevel.All)
        edits.setChannelSuppressEveryone("c1", true)
        edits.setChannelSuppressMentions("c1", true)
        edits.setChannelSync("c1", true)
        edits.setChannelLevel("c1", null)
        edits.setChannelSuppressEveryone("c1", null)
        edits.setChannelSuppressMentions("c1", null)
        edits.setChannelSync("c1", null)
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun silenciarOCanalUsaOTempoEReativarVoltaAoPadraoDoServidor() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.setServerSuppressMentions(true)
        edits.muteChannel("c1", MuteDuration.Hours3)
        assertEquals(Mute(start + 3.hours), prefs.channels.value["c1"]?.mute)
        edits.unmuteChannel("c1")
        assertEquals(emptyMap(), prefs.channels.value)
        assertTrue(prefs.server.value.suppressMentions) // reativar o canal não mexe no servidor
    }

    @Test
    fun reativarOCanalComOServidorSilenciadoDeixaOSilencioDoServidor() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.muteServer(MuteDuration.UntilReenabled)
        edits.muteChannel("c1", MuteDuration.Hour1)
        edits.unmuteChannel("c1")
        val state = channelNotificationsState(prefs.server.value, prefs.channels.value["c1"] ?: ChannelNotificationSettings(), start, prefs.syncAvailable)
        assertEquals(ActiveMute(MuteSource.Server, null), state.mute)
        assertFalse(state.canUnmute)
    }

    @Test
    fun cadaEscolhaDaDmChamaOSetterCertoEADmNaoMexeNosCanais() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.muteDm("d1", MuteDuration.Minutes15)
        edits.setDmSync("d1", false)
        assertEquals(DmNotificationSettings(MuteDuration.Minutes15.from(start), sync = false), prefs.dms.value["d1"])
        assertEquals(emptyMap(), prefs.channels.value)
        edits.unmuteDm("d1")
        assertEquals(DmNotificationSettings(sync = false), prefs.dms.value["d1"])
        edits.setDmSync("d1", null)
        assertEquals(emptyMap(), prefs.dms.value)
    }

    @Test
    fun rotulosDePadraoMostramOValorAtualDoServidor() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        fun state() = channelNotificationsState(prefs.server.value, ChannelNotificationSettings(), start, prefs.syncAvailable)
        assertEquals(NotificationLevel.OnlyMentions, state().serverLevel)
        assertFalse(state().serverSuppressEveryone)
        edits.setServerLevel(NotificationLevel.All)
        edits.setServerSuppressEveryone(true)
        edits.setServerSync(false)
        assertEquals(NotificationLevel.All, state().serverLevel)
        assertTrue(state().serverSuppressEveryone)
        assertFalse(state().serverSync)
        assertNull(state().level) // e o canal segue em "padrão"
    }

    @Test
    fun oAvisoDeSincronizacaoApareceEnquantoOBackendNaoGuarda() = runTest {
        val unavailable = prefs()
        runCurrent()
        assertFalse(unavailable.syncAvailable)
        assertFalse(channelNotificationsState(unavailable.server.value, ChannelNotificationSettings(), start, unavailable.syncAvailable).syncAvailable)
        assertFalse(dmNotificationsState(unavailable.server.value, DmNotificationSettings(), start, unavailable.syncAvailable).syncAvailable)
        assertFalse(serverNotificationsState(unavailable.server.value, start, unavailable.syncAvailable).syncAvailable)

        val available = prefs(object : NotificationSettingsRemote {
            override val available = true
        })
        runCurrent()
        assertTrue(available.syncAvailable)
        assertTrue(serverNotificationsState(available.server.value, start, available.syncAvailable).syncAvailable)
    }

    @Test
    fun oQueAFolhaEscolheChegaAosFluxosQueElaObserva() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.muteChannel("c1", MuteDuration.Hour1)
        val state = channelNotificationsState(prefs.server.value, prefs.channels.value.getValue("c1"), start, prefs.syncAvailable)
        assertEquals(ActiveMute(MuteSource.Channel, start + 1.hours), state.mute)
        assertTrue(state.canUnmute)
    }
}
