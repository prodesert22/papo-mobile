package chat.papo.mobile

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NoServerChannelLevels
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.ServerChannelLevels
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPrefsTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

    /** Quanto o relógio de parede está atrasado em relação ao tempo virtual (para simular um ajuste do relógio). */
    private var skew = 0.milliseconds

    private fun TestScope.clock(): () -> Instant = { start + testScheduler.currentTime.milliseconds - skew }

    private fun TestScope.prefs(
        meta: MetaQueries,
        levels: ServerChannelLevels = NoServerChannelLevels,
        scope: CoroutineScope = backgroundScope,
    ) = NotificationPrefs(meta, scope, StandardTestDispatcher(testScheduler), levels, clock())

    private fun meta() = AccountDb(TestResources.driver()).metaQueries

    private fun MetaQueries.raw(key: String): String? = get(key).executeAsOneOrNull()

    private fun MetaQueries.channel(id: String) = NotificationPrefs.decodeChannel(raw(NotificationPrefs.channelKey(id)))

    private fun MetaQueries.dm(id: String) = NotificationPrefs.decodeDm(raw(NotificationPrefs.dmKey(id)))

    private fun MetaQueries.server() = NotificationPrefs.decodeServer(raw(NotificationPrefs.SERVER_KEY))

    @Test
    fun semNadaGuardadoValemOsPadroesDepoisDeLer() = runTest {
        val prefs = prefs(meta())
        assertFalse(prefs.loaded.value)
        runCurrent()
        assertTrue(prefs.loaded.value)
        assertEquals(ServerNotificationSettings(), prefs.server.value)
        assertEquals(emptyMap(), prefs.channels.value)
        assertEquals(emptyMap(), prefs.dms.value)
    }

    @Test
    fun oQueMudaVaiParaOBancoESobreviveAReabrirAConta() = runTest {
        val meta = meta()
        val prefs = prefs(meta)
        runCurrent()
        val server = ServerNotificationSettings(NotificationLevel.All, suppressEveryone = true, sync = false)
        val channel = ChannelNotificationSettings(NotificationLevel.Nothing, suppressMentions = true, mute = Mute(start + 1.days))
        val dm = DmNotificationSettings(Mute(null))
        prefs.updateServer { server }
        prefs.updateChannel("c1") { channel }
        prefs.updateDm("d1") { dm }
        runCurrent()
        assertEquals(server, meta.server())
        assertEquals(channel, meta.channel("c1"))
        assertEquals(dm, meta.dm("d1"))

        val reopened = prefs(meta)
        runCurrent()
        assertEquals(server, reopened.server.value)
        assertEquals(mapOf("c1" to channel), reopened.channels.value)
        assertEquals(mapOf("d1" to dm), reopened.dms.value)
    }

    @Test
    fun updateRecebeOValorAtualEMudaSoOQueFoiPedido() = runTest {
        val prefs = prefs(meta())
        runCurrent()
        prefs.updateChannel("c1") { it.copy(level = NotificationLevel.All) }
        prefs.updateChannel("c1") { it.copy(suppressEveryone = true) }
        assertEquals(ChannelNotificationSettings(NotificationLevel.All, suppressEveryone = true), prefs.channels.value["c1"])
        prefs.updateDm("d1") { it.copy(mute = Mute(null)) }
        assertEquals(DmNotificationSettings(Mute(null)), prefs.dms.value["d1"])
        prefs.updateDm("d2") { it.copy(mute = MuteDuration.Hour1.from(start)) }
        assertEquals(setOf("d1", "d2"), prefs.dms.value.keys)
    }

    @Test
    fun voltarAoPadraoApagaAEntradaEALinhaDoBanco() = runTest {
        val meta = meta()
        val prefs = prefs(meta)
        runCurrent()
        prefs.updateServer { it.copy(level = NotificationLevel.Nothing) }
        prefs.updateChannel("c1") { it.copy(level = NotificationLevel.All) }
        prefs.updateDm("d1") { it.copy(mute = Mute(null)) }
        runCurrent()
        assertTrue(meta.keysWithPrefix("notif:").executeAsList().size == 3)

        prefs.updateServer { ServerNotificationSettings() }
        prefs.updateChannel("c1") { ChannelNotificationSettings() }
        prefs.updateDm("d1") { DmNotificationSettings() }
        runCurrent()
        assertEquals(emptyMap(), prefs.channels.value)
        assertEquals(emptyMap(), prefs.dms.value)
        assertEquals(emptyList(), meta.keysWithPrefix("notif:").executeAsList())
    }

    @Test
    fun valoresEstragadosOuDeOutraVersaoCaemNosPadroes() = runTest {
        val meta = meta()
        meta.put(NotificationPrefs.SERVER_KEY, "not json")
        meta.put(NotificationPrefs.channelKey("bad"), "{")
        meta.put(NotificationPrefs.channelKey("empty"), "{}")
        meta.put(NotificationPrefs.channelKey("ok"), NotificationPrefs.encodeChannel(ChannelNotificationSettings(level = NotificationLevel.All)))
        meta.put(NotificationPrefs.dmKey("bad"), "[]")
        meta.put("notif:future:x", "{\"a\":1}")
        meta.put("draft:c1", "texto de outra família")
        val prefs = prefs(meta)
        runCurrent()
        assertTrue(prefs.loaded.value)
        assertEquals(ServerNotificationSettings(), prefs.server.value)
        assertEquals(mapOf("ok" to ChannelNotificationSettings(level = NotificationLevel.All)), prefs.channels.value)
        assertEquals(emptyMap(), prefs.dms.value)
    }

    @Test
    fun oQueAPessoaMudouAntesDaLeituraTerminarVenceOBancoInclusiveVoltarAoPadrao() = runTest {
        val meta = meta()
        meta.put(NotificationPrefs.SERVER_KEY, NotificationPrefs.encodeServer(ServerNotificationSettings(NotificationLevel.Nothing)))
        meta.put(NotificationPrefs.channelKey("c1"), NotificationPrefs.encodeChannel(ChannelNotificationSettings(level = NotificationLevel.All)))
        meta.put(NotificationPrefs.channelKey("c2"), NotificationPrefs.encodeChannel(ChannelNotificationSettings(level = NotificationLevel.All)))
        meta.put(NotificationPrefs.channelKey("c3"), NotificationPrefs.encodeChannel(ChannelNotificationSettings(suppressMentions = true)))
        meta.put(NotificationPrefs.dmKey("d1"), NotificationPrefs.encodeDm(DmNotificationSettings(Mute(null))))
        meta.put(NotificationPrefs.dmKey("d2"), NotificationPrefs.encodeDm(DmNotificationSettings(Mute(null))))
        val prefs = prefs(meta)

        // Nada rodou ainda: a leitura do banco só termina no `runCurrent`.
        prefs.updateServer { it.copy(suppressEveryone = true) }
        prefs.updateChannel("c1") { it.copy(level = NotificationLevel.OnlyMentions) }
        prefs.updateChannel("c2") { ChannelNotificationSettings() }
        prefs.updateDm("d1") { DmNotificationSettings() }
        runCurrent()

        assertEquals(ServerNotificationSettings(suppressEveryone = true), prefs.server.value) // o do banco (level Nada) não volta
        assertEquals(
            mapOf(
                "c1" to ChannelNotificationSettings(level = NotificationLevel.OnlyMentions),
                "c3" to ChannelNotificationSettings(suppressMentions = true),
            ),
            prefs.channels.value,
        )
        assertEquals(mapOf("d2" to DmNotificationSettings(Mute(null))), prefs.dms.value)
        assertEquals(ServerNotificationSettings(suppressEveryone = true), meta.server())
        assertNull(meta.channel("c2"))
        assertNull(meta.dm("d1"))
    }

    @Test
    fun efetivoJuntaOCanalComOPadraoDoServidor() = runTest {
        val prefs = prefs(meta())
        runCurrent()
        assertEquals(NotificationLevel.OnlyMentions, prefs.effectiveFor("c1").level)
        prefs.updateServer { it.copy(level = NotificationLevel.All, mute = MuteDuration.Hour1.from(start)) }
        // Sincronizado (o padrão), o nível geral do aparelho não vale: o do servidor, sem valor, é "só menções".
        assertEquals(NotificationLevel.OnlyMentions, prefs.effectiveFor("c1").level)
        assertEquals(ActiveMute(MuteSource.Server, start + 1.hours), prefs.effectiveFor("c1").mute)
        prefs.updateChannel("c1") { it.copy(mute = Mute(null)) }
        assertEquals(ActiveMute(MuteSource.Channel, null), prefs.effectiveFor("c1").mute)
        // A DM não herda o silêncio do servidor.
        assertNull(prefs.effectiveForDm("d1").mute)
    }

    @Test
    fun silencioComFimSomeSozinhoNoFimEOBancoAcompanha() = runTest {
        val meta = meta()
        val prefs = prefs(meta)
        runCurrent()
        prefs.updateChannel("c1") { it.copy(mute = MuteDuration.Minutes15.from(start)) }
        prefs.updateChannel("c2") { it.copy(level = NotificationLevel.All, mute = MuteDuration.Minutes15.from(start)) }
        prefs.updateDm("d1") { it.copy(mute = MuteDuration.Hour1.from(start)) }
        prefs.updateDm("d2") { it.copy(mute = MuteDuration.Hours3.from(start)) }
        prefs.updateServer { it.copy(mute = MuteDuration.Hours3.from(start)) }
        runCurrent()

        advanceTimeBy(15.minutes - 1.milliseconds)
        runCurrent()
        assertTrue(prefs.effectiveFor("c1").muted)
        assertEquals(setOf("c1", "c2"), prefs.channels.value.keys)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        // c1 só tinha o silêncio: a entrada some. c2 guarda o nível.
        assertEquals(mapOf("c2" to ChannelNotificationSettings(level = NotificationLevel.All)), prefs.channels.value)
        assertEquals(ServerNotificationSettings(mute = MuteDuration.Hours3.from(start)), prefs.server.value) // o do servidor segue
        assertNull(meta.channel("c1"))
        assertEquals(ChannelNotificationSettings(level = NotificationLevel.All), meta.channel("c2"))

        advanceTimeBy(45.minutes)
        runCurrent()
        assertEquals(mapOf("d2" to DmNotificationSettings(MuteDuration.Hours3.from(start))), prefs.dms.value)
        assertNull(meta.dm("d1"))
        assertEquals(DmNotificationSettings(MuteDuration.Hours3.from(start)), meta.dm("d2"))

        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(ServerNotificationSettings(), prefs.server.value)
        assertNull(meta.raw(NotificationPrefs.SERVER_KEY))
        assertEquals(emptyMap(), prefs.dms.value)
        assertNull(meta.dm("d2"))
        assertFalse(prefs.effectiveFor("c2").muted)
    }

    @Test
    fun silencioDoServidorQueAcabaMantemOQueMaisFoiEscolhido() = runTest {
        val meta = meta()
        val prefs = prefs(meta)
        runCurrent()
        prefs.updateServer { it.copy(level = NotificationLevel.All, mute = MuteDuration.Minutes15.from(start)) }
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(ServerNotificationSettings(level = NotificationLevel.All), prefs.server.value)
        assertEquals(ServerNotificationSettings(level = NotificationLevel.All), meta.server())
    }

    @Test
    fun silencioAteReativarNuncaSome() = runTest {
        val prefs = prefs(meta())
        runCurrent()
        prefs.updateChannel("c1") { it.copy(mute = MuteDuration.UntilReenabled.from(start)) }
        advanceTimeBy(365.days)
        runCurrent()
        assertTrue(prefs.effectiveFor("c1").muted)
        assertEquals(ChannelNotificationSettings(mute = Mute(null)), prefs.channels.value["c1"])
    }

    @Test
    fun trocarOSilencioRefazOTemporizador() = runTest {
        val prefs = prefs(meta())
        runCurrent()
        prefs.updateChannel("c1") { it.copy(mute = MuteDuration.Hour1.from(start)) }
        runCurrent()
        // Um silêncio mais curto passa na frente do que já esperava.
        prefs.updateChannel("c2") { it.copy(mute = MuteDuration.Minutes15.from(start)) }
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(setOf("c1"), prefs.channels.value.keys)
        // Reativar a mão cancela a espera: nada mais a tirar.
        prefs.updateChannel("c1") { it.copy(mute = null) }
        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun silencioQueJaAcabouAoAbrirASessaoEDescartadoNaLeitura() = runTest {
        val meta = meta()
        meta.put(NotificationPrefs.channelKey("c1"), NotificationPrefs.encodeChannel(ChannelNotificationSettings(mute = Mute(start - 1.minutes))))
        meta.put(NotificationPrefs.channelKey("c2"), NotificationPrefs.encodeChannel(ChannelNotificationSettings(mute = Mute(start))))
        meta.put(NotificationPrefs.channelKey("c3"), NotificationPrefs.encodeChannel(ChannelNotificationSettings(mute = Mute(start + 1.milliseconds))))
        meta.put(NotificationPrefs.dmKey("d1"), NotificationPrefs.encodeDm(DmNotificationSettings(Mute(start - 1.hours))))
        val prefs = prefs(meta)
        runCurrent()
        // O fim exato já é fim; um milissegundo depois ainda vale.
        assertEquals(setOf("c3"), prefs.channels.value.keys)
        assertEquals(emptyMap(), prefs.dms.value)
        assertNull(meta.channel("c1"))
        assertNull(meta.channel("c2"))
        assertNull(meta.dm("d1"))
        assertTrue(meta.channel("c3") != null)
    }

    @Test
    fun relogioAtrasadoFazOTemporizadorEsperarOQueFalta() = runTest {
        val prefs = prefs(meta())
        runCurrent()
        prefs.updateChannel("c1") { it.copy(mute = MuteDuration.Minutes15.from(start)) }
        runCurrent()
        skew = 10.milliseconds // o relógio de parede foi atrasado 10 ms
        advanceTimeBy(15.minutes)
        runCurrent()
        assertTrue(prefs.channels.value.containsKey("c1"))
        assertTrue(prefs.effectiveFor("c1").muted)
        advanceTimeBy(10.milliseconds)
        runCurrent()
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun cadaContaTemAsSuas() = runTest {
        val metaA = meta()
        val metaB = meta()
        val a = prefs(metaA)
        val b = prefs(metaB)
        runCurrent()
        a.updateServer { it.copy(level = NotificationLevel.Nothing) }
        a.updateChannel("mesmo-id") { it.copy(mute = Mute(null)) }
        a.updateDm("mesmo-id") { it.copy(mute = Mute(null)) }
        runCurrent()
        assertEquals(ServerNotificationSettings(), b.server.value)
        assertEquals(emptyMap(), b.channels.value)
        assertEquals(emptyMap(), b.dms.value)
        assertFalse(b.effectiveFor("mesmo-id").muted)
        assertEquals(emptyList(), metaB.keysWithPrefix("notif:").executeAsList())
        assertTrue(a.effectiveFor("mesmo-id").muted)

        // E o silêncio que acaba numa conta não mexe na outra.
        a.updateChannel("c9") { it.copy(mute = MuteDuration.Minutes15.from(start)) }
        b.updateChannel("c9") { it.copy(mute = MuteDuration.Hour1.from(start)) }
        advanceTimeBy(15.minutes)
        runCurrent()
        assertFalse(a.channels.value.containsKey("c9"))
        assertTrue(b.channels.value.containsKey("c9"))
    }

    @Test
    fun bancoComFalhaNaoDerrubaAsConfiguracoesNemAsApaga() = runTest {
        val driver = TestResources.driver()
        val meta = AccountDb(driver).metaQueries
        driver.close()
        val prefs = prefs(meta)
        runCurrent()
        assertTrue(prefs.loaded.value) // a leitura falhou: valem os padrões
        prefs.updateChannel("c1") { it.copy(level = NotificationLevel.All, sync = false) }
        prefs.updateServer { it.copy(mute = MuteDuration.Minutes15.from(start)) }
        runCurrent() // as gravações falham sem estourar no escopo
        assertEquals(NotificationLevel.All, prefs.effectiveFor("c1").level)
        advanceTimeBy(15.minutes) // e o silêncio ainda é tirado da memória
        runCurrent()
        assertEquals(ServerNotificationSettings(), prefs.server.value)
    }

    @Test
    fun cancelarOEscopoParaOTemporizador() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = prefs(meta(), scope = owner)
        runCurrent()
        prefs.updateChannel("c1") { it.copy(mute = MuteDuration.Minutes15.from(start)) }
        runCurrent()
        owner.cancel()
        advanceTimeBy(1.hours)
        runCurrent()
        // Sem dono vivo ninguém tira o silêncio da memória (o valor efetivo continua certo pelo relógio).
        assertTrue(prefs.channels.value.containsKey("c1"))
        assertFalse(prefs.effectiveFor("c1").muted)
    }
}
