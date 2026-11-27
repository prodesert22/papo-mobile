package chat.papo.mobile

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.ui.notifications.NotificationEdits
import chat.papo.mobile.ui.notifications.channelNotificationsState
import chat.papo.mobile.ui.notifications.dmNotificationsState
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
    private val levels = FakeServerLevels(mapOf("c1" to NotificationLevel.All))

    private fun TestScope.prefs(): NotificationPrefs {
        val meta = AccountDb(TestResources.driver()).metaQueries
        val clock = { start + testScheduler.currentTime.milliseconds }
        return NotificationPrefs(meta, backgroundScope, StandardTestDispatcher(testScheduler), levels, clock)
    }

    private fun edits(prefs: NotificationPrefs) = NotificationEdits(prefs) { start }

    private fun state(prefs: NotificationPrefs, id: String = "c1") = channelNotificationsState(
        id,
        prefs.server.value,
        prefs.channels.value[id] ?: ChannelNotificationSettings(),
        prefs.serverLevels.value[id] ?: NotificationLevel.OnlyMentions,
        prefs.levelSave.value,
        start,
    )

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
        assertEquals(ServerNotificationSettings(), prefs.server.value)
    }

    @Test
    fun ligarOuDesligarASincronizacaoDoServidorPassaPelaRegraQueNaoMudaONivelDosCanais() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        assertEquals(NotificationLevel.All, prefs.effectiveFor("c1").level)
        edits.setServerSync(false)
        assertEquals(NotificationLevel.All, prefs.effectiveFor("c1").level)
        assertEquals(NotificationLevel.All, prefs.channels.value["c1"]?.level) // copiado do servidor
    }

    @Test
    fun canalSincronizadoOferecePedeOsTresNiveisESemPadraoEGravaNoServidor() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        val synced = state(prefs)
        assertTrue(synced.synced) // a folha mostra só Todas, Só menções e Nada, selecionando o do servidor
        assertEquals(NotificationLevel.All, synced.serverLevel)

        edits.setChannelLevel("c1", NotificationLevel.Nothing)
        assertEquals(listOf("c1" to NotificationLevel.Nothing), levels.sets)
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun canalSemSincronizacaoOfereceOPadraoComOValorGeralEGuardaNoAparelho() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.setServerLevel(NotificationLevel.Nothing)
        edits.setChannelSync("c1", false)
        val local = state(prefs)
        assertFalse(local.synced) // a folha mostra "Usar padrão do servidor (Nada)" além dos três níveis
        assertEquals(NotificationLevel.Nothing, local.defaultLevel)

        edits.setChannelLevel("c1", NotificationLevel.All)
        assertEquals(NotificationLevel.All, state(prefs).level)
        edits.setChannelLevel("c1", null)
        assertNull(state(prefs).level)
        assertEquals(emptyList(), levels.sets) // nada foi ao servidor
    }

    @Test
    fun cadaEscolhaDeSuprimirEDeSincronizarDoCanalChamaOSetterCertoSoNaquelaEntrada() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.setChannelSuppressEveryone("c1", true)
        edits.setChannelSuppressMentions("c1", false)
        edits.setChannelSync("c1", true)
        assertEquals(
            ChannelNotificationSettings(suppressEveryone = true, suppressMentions = false, sync = true),
            prefs.channels.value["c1"],
        )
        assertEquals(setOf("c1"), prefs.channels.value.keys)
        edits.setChannelSuppressEveryone("c1", null)
        edits.setChannelSuppressMentions("c1", null)
        edits.setChannelSync("c1", null)
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun rotulosDePadraoMostramOValorAtualDoServidor() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        assertFalse(state(prefs).serverSuppressEveryone)
        edits.setServerSuppressEveryone(true)
        edits.setServerSuppressMentions(true)
        edits.setServerLevel(NotificationLevel.All)
        val after = state(prefs)
        assertTrue(after.serverSuppressEveryone)
        assertTrue(after.serverSuppressMentions)
        assertEquals(NotificationLevel.All, after.defaultLevel)
        assertNull(after.suppressEveryone) // e o canal segue em "padrão"
    }

    @Test
    fun silenciarOCanalUsaOTempoEReativarVoltaAoPadraoDoServidor() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.muteChannel("c1", MuteDuration.Hours3)
        assertEquals(Mute(start + 3.hours), prefs.channels.value["c1"]?.mute)
        edits.unmuteChannel("c1")
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun reativarOCanalComOServidorSilenciadoDeixaOSilencioDoServidor() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.muteServer(MuteDuration.UntilReenabled)
        edits.muteChannel("c1", MuteDuration.Hour1)
        edits.unmuteChannel("c1")
        val state = state(prefs)
        assertEquals(ActiveMute(MuteSource.Server, null), state.mute)
        assertFalse(state.canUnmute)
    }

    @Test
    fun aDmSoTemSilencio() = runTest {
        val prefs = prefs()
        val edits = edits(prefs)
        runCurrent()
        edits.muteDm("d1", MuteDuration.Minutes15)
        assertEquals(DmNotificationSettings(MuteDuration.Minutes15.from(start)), prefs.dms.value["d1"])
        assertEquals(emptyMap(), prefs.channels.value)
        assertTrue(dmNotificationsState(prefs.server.value, prefs.dms.value.getValue("d1"), start).canUnmute)
        edits.unmuteDm("d1")
        assertEquals(emptyMap(), prefs.dms.value)
    }
}
