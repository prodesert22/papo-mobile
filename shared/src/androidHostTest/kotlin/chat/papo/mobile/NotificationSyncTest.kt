package chat.papo.mobile

import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.LevelSave
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.ServerChannelLevels
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.data.db.account.AccountDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** O nível que o servidor guarda, à mão: `set` só registra o pedido, quem muda os níveis é o teste (como uma resposta 200). */
internal class FakeServerLevels(initial: Map<String, NotificationLevel> = emptyMap()) : ServerChannelLevels {
    val state = MutableStateFlow(initial)
    override val levels: StateFlow<Map<String, NotificationLevel>> = state
    override val save = MutableStateFlow(LevelSave())
    val sets = mutableListOf<Pair<String, NotificationLevel>>()

    override fun set(channelId: String, level: NotificationLevel) {
        sets += channelId to level
    }

    override fun clearFailure() {
        save.value = save.value.copy(failure = null)
    }
}

/**
 * O nível do canal sincronizado (o do servidor) ou do aparelho, e o princípio de que ligar ou desligar a
 * sincronização não muda o nível de nenhum canal na hora.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSyncTest {
    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val all = NotificationLevel.All
    private val mentions = NotificationLevel.OnlyMentions
    private val nothing = NotificationLevel.Nothing

    private fun TestScope.prefs(levels: ServerChannelLevels): NotificationPrefs {
        val meta = AccountDb(TestResources.driver()).metaQueries
        return NotificationPrefs(meta, backgroundScope, StandardTestDispatcher(testScheduler), levels) { start }
    }

    private fun NotificationPrefs.level(id: String) = effectiveFor(id).level

    @Test
    fun canalSincronizadoUsaONivelDoServidorEAcompanhaSuasMudancas() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to all))
        val prefs = prefs(levels)
        runCurrent()
        assertEquals(all, prefs.level("c1"))
        assertEquals(mentions, prefs.level("sem-valor")) // canal que o servidor não tem: o padrão dele
        assertTrue(prefs.isSyncing("c1"))
        levels.state.value = mapOf("c1" to nothing) // os canais foram relidos
        assertEquals(nothing, prefs.level("c1"))
        assertEquals(levels.state, prefs.serverLevels)
    }

    @Test
    fun nivelSincronizadoNaoDependeDoNivelGeralDoAparelho() = runTest {
        val prefs = prefs(FakeServerLevels(mapOf("c1" to all)))
        runCurrent()
        prefs.updateServer { it.copy(level = nothing) }
        assertEquals(all, prefs.level("c1"))
    }

    @Test
    fun escolherONivelDeUmCanalSincronizadoPedeAoServidorENaoMudaNadaAteConfirmar() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to all))
        val prefs = prefs(levels)
        runCurrent()
        prefs.setChannelLevel("c1", nothing)
        assertEquals(listOf("c1" to nothing), levels.sets)
        assertEquals(all, prefs.level("c1")) // vale quando o servidor confirmar
        assertEquals(emptyMap(), prefs.channels.value) // nada guardado no aparelho
    }

    @Test
    fun nivelNuloEmCanalSincronizadoNaoFazNada() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to all))
        val prefs = prefs(levels)
        runCurrent()
        prefs.setChannelLevel("c1", null)
        assertEquals(emptyList(), levels.sets)
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun escolherONivelSemSincronizacaoFicaNoAparelhoENuloVoltaAoGeral() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to all))
        val prefs = prefs(levels)
        runCurrent()
        prefs.setChannelSync("c1", false)
        prefs.setChannelLevel("c1", nothing)
        assertEquals(emptyList(), levels.sets) // nada vai ao servidor
        assertEquals(nothing, prefs.level("c1"))
        assertEquals(nothing, prefs.channels.value["c1"]?.level)
        prefs.updateServer { it.copy(level = all) }
        prefs.setChannelLevel("c1", null) // "Usar padrão do servidor": o nível geral do aparelho
        assertEquals(all, prefs.level("c1"))
        assertNull(prefs.channels.value["c1"]?.level)
    }

    @Test
    fun desligarASincronizacaoDoCanalCopiaONivelDoServidorEElePermaneceAoMudarOServidor() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to all))
        val prefs = prefs(levels)
        runCurrent()
        prefs.setChannelSync("c1", false)
        assertEquals(ChannelNotificationSettings(level = all, sync = false), prefs.channels.value["c1"])
        assertEquals(all, prefs.level("c1"))
        assertFalse(prefs.isSyncing("c1"))
        levels.state.value = mapOf("c1" to nothing) // outro aparelho muda o canal no servidor
        assertEquals(all, prefs.level("c1")) // este não acompanha
        assertEquals(emptyList(), levels.sets)
    }

    @Test
    fun ligarASincronizacaoDoCanalLargaONivelDoAparelhoEVoltaAoDoServidorSemEnviarNada() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to mentions))
        val prefs = prefs(levels)
        runCurrent()
        prefs.setChannelSync("c1", false)
        prefs.setChannelLevel("c1", nothing)
        prefs.setChannelSync("c1", true)
        assertNull(prefs.channels.value["c1"]?.level)
        assertEquals(mentions, prefs.level("c1")) // o do servidor, que nunca recebeu o do aparelho
        assertEquals(emptyList(), levels.sets)
        assertTrue(prefs.isSyncing("c1"))
        // Nulo (seguir o servidor, que está ligado) também sincroniza e larga o nível do aparelho.
        prefs.setChannelSync("c1", false)
        prefs.setChannelLevel("c1", all)
        prefs.setChannelSync("c1", null)
        assertEquals(mentions, prefs.level("c1"))
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun desligarASincronizacaoDoServidorNaoMudaONivelDeNenhumCanal() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to all, "c2" to mentions, "c3" to nothing, "c4" to nothing, "c5" to all))
        val prefs = prefs(levels)
        runCurrent()
        prefs.updateChannel("c4") { it.copy(sync = true) } // sincroniza de propósito: não segue o servidor
        prefs.updateChannel("c5") { it.copy(sync = false, level = mentions) } // já tinha o próprio nível no aparelho
        val before = listOf("c1", "c2", "c3", "c4", "c5").associateWith { prefs.level(it) }

        prefs.setServerSync(false)

        assertEquals(before, before.keys.associateWith { prefs.level(it) })
        assertFalse(prefs.server.value.sync)
        // Quem mudaria guarda o nível que tinha; quem não mudaria não ganha entrada.
        assertEquals(all, prefs.channels.value["c1"]?.level)
        assertNull(prefs.channels.value["c2"])
        assertEquals(nothing, prefs.channels.value["c3"]?.level)
        assertEquals(true, prefs.channels.value["c4"]?.sync)
        assertEquals(mentions, prefs.channels.value["c5"]?.level)
        assertEquals(emptyList(), levels.sets)
    }

    @Test
    fun ligarASincronizacaoDoServidorNaoMudaONivelDeNenhumCanalEFicaSemSincronizarQuemMudaria() = runTest {
        val levels = FakeServerLevels(mapOf("c1" to all, "c2" to mentions, "c3" to nothing, "c4" to mentions))
        val prefs = prefs(levels)
        runCurrent()
        prefs.updateServer { it.copy(sync = false, level = all) } // o geral do aparelho passa a ser "todas"
        prefs.updateChannel("c3") { it.copy(level = nothing) } // coincide com o do servidor; vale só no aparelho
        prefs.updateChannel("c4") { it.copy(sync = true) }
        val before = listOf("c1", "c2", "c3", "c4").associateWith { prefs.level(it) }
        assertEquals(mapOf("c1" to all, "c2" to all, "c3" to nothing, "c4" to mentions), before)

        prefs.setServerSync(true)

        assertEquals(before, before.keys.associateWith { prefs.level(it) })
        assertTrue(prefs.server.value.sync)
        assertNull(prefs.channels.value["c1"]) // o servidor já tinha "todas": passa a sincronizar
        assertTrue(prefs.isSyncing("c1"))
        assertEquals(false, prefs.channels.value["c2"]?.sync) // o servidor tem "só menções": ficaria diferente
        assertEquals(all, prefs.channels.value["c2"]?.level)
        assertFalse(prefs.isSyncing("c2"))
        assertNull(prefs.channels.value["c3"]) // o nível do aparelho igual ao do servidor é largado ao sincronizar
        assertTrue(prefs.isSyncing("c3"))
        assertEquals(emptyList(), levels.sets) // ligar nunca manda o nível do aparelho
    }

    @Test
    fun ligarEDesligarOServidorSemCanaisNemNiveisNaoGuardaNada() = runTest {
        val prefs = prefs(FakeServerLevels())
        runCurrent()
        prefs.setServerSync(false)
        prefs.setServerSync(true)
        assertEquals(ServerNotificationSettings(), prefs.server.value)
        assertEquals(emptyMap(), prefs.channels.value)
    }

    @Test
    fun nenhumaCombinacaoDeLigarOuDesligarMudaONivelDeAgoraSalvoLigarUmCanalComNivelDoAparelho() = runTest {
        val levelsOnServer = mapOf("a" to all, "b" to mentions, "c" to nothing)
        val locals = listOf<NotificationLevel?>(null, all, mentions, nothing)
        val channelSyncs = listOf<Boolean?>(null, true, false)
        for (serverSync in listOf(true, false)) for (channelSync in channelSyncs) for (local in locals) for (serverLevel in NotificationLevel.entries) {
            val prefs = prefs(FakeServerLevels(levelsOnServer))
            runCurrent()
            prefs.updateServer { it.copy(sync = serverSync, level = serverLevel) }
            prefs.updateChannel("a") { it.copy(sync = channelSync, level = local) }
            val before = levelsOnServer.keys.associateWith { prefs.level(it) }
            val label = "servidor=$serverSync canal=$channelSync local=$local geral=$serverLevel"

            prefs.setServerSync(!serverSync)
            assertEquals(before, levelsOnServer.keys.associateWith { prefs.level(it) }, "servidor invertido: $label")

            prefs.setChannelSync("a", false)
            assertEquals(before, levelsOnServer.keys.associateWith { prefs.level(it) }, "canal desligado: $label")
        }
    }
}
