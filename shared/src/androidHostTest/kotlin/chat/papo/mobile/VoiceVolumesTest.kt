package chat.papo.mobile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.account.MetaQueries
import chat.papo.mobile.voice.StoredVoiceVolumes
import chat.papo.mobile.voice.VolumeLevels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceVolumesTest {
    private fun TestScope.volumes(meta: MetaQueries) = StoredVoiceVolumes(meta, backgroundScope, StandardTestDispatcher(testScheduler))

    private fun meta() = AccountDb(TestResources.driver()).metaQueries

    private fun MetaQueries.value(key: String): String? = get(key).executeAsOneOrNull()

    @Test
    fun voltaDepoisDeReiniciarAConta() = runTest {
        val meta = meta()
        val first = volumes(meta)
        first.setVoice("bia", 30)
        first.setScreen("bia", 150)
        first.setVoice("caio", 0)
        first.flush()
        runCurrent()

        assertEquals("30", meta.value("voice_volume:bia"))
        assertEquals("150", meta.value("voice_screen_volume:bia"))
        assertEquals("0", meta.value("voice_volume:caio"))

        val second = volumes(meta)
        runCurrent()
        second.awaitLoaded()
        assertEquals(VolumeLevels(voice = mapOf("bia" to 30, "caio" to 0), screen = mapOf("bia" to 150)), second.levels.value)
    }

    @Test
    fun soGravaDepoisDoAtrasoSemNovasMudancas() = runTest {
        val meta = meta()
        val volumes = volumes(meta)
        volumes.setVoice("bia", 40)
        advanceTimeBy(StoredVoiceVolumes.DEBOUNCE - 1.milliseconds)
        runCurrent()
        assertNull(meta.value("voice_volume:bia"))

        // Outro passo do controle deslizante recomeça a espera.
        volumes.setVoice("bia", 45)
        advanceTimeBy(StoredVoiceVolumes.DEBOUNCE - 1.milliseconds)
        runCurrent()
        assertNull(meta.value("voice_volume:bia"))

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals("45", meta.value("voice_volume:bia"))
        assertEquals(45, volumes.levels.value.voiceOf("bia")) // o valor vale na hora, antes de gravar
    }

    @Test
    fun flushGravaNaHoraEDepoisNadaMudaNoBanco() = runTest {
        val meta = meta()
        val volumes = volumes(meta)
        volumes.setVoice("bia", 70)
        volumes.flush()
        runCurrent()
        assertEquals("70", meta.value("voice_volume:bia"))

        advanceTimeBy(StoredVoiceVolumes.DEBOUNCE * 2)
        runCurrent()
        assertEquals("70", meta.value("voice_volume:bia"))
    }

    @Test
    fun flushSemNadaEsperandoNaoFazNada() = runTest {
        val meta = meta()
        volumes(meta).flush()
        runCurrent()
        assertNull(meta.value("voice_volume:bia"))
    }

    @Test
    fun voltarAoNormalApagaAChave() = runTest {
        val meta = meta()
        val volumes = volumes(meta)
        volumes.setVoice("bia", 20)
        volumes.setScreen("bia", 20)
        volumes.flush()
        runCurrent()

        volumes.setVoice("bia", 100)
        volumes.setScreen("bia", 100)
        volumes.flush()
        runCurrent()
        assertNull(meta.value("voice_volume:bia"))
        assertNull(meta.value("voice_screen_volume:bia"))
        assertEquals(VolumeLevels(), volumes.levels.value)
        assertEquals(100, volumes.levels.value.voiceOf("bia"))
        assertEquals(100, volumes.levels.value.screenOf("bia"))
    }

    @Test
    fun forasDoIntervaloSaoLimitados() = runTest {
        val meta = meta()
        val volumes = volumes(meta)
        volumes.setVoice("bia", 250)
        volumes.setVoice("caio", -5)
        volumes.flush()
        runCurrent()
        assertEquals("200", meta.value("voice_volume:bia"))
        assertEquals("0", meta.value("voice_volume:caio"))
        assertEquals(200, volumes.levels.value.voiceOf("bia"))
        assertEquals(0, volumes.levels.value.voiceOf("caio"))
    }

    @Test
    fun usuarioSemIdOuComIdGrandeDemaisEIgnorado() = runTest {
        val meta = meta()
        val volumes = volumes(meta)
        volumes.setVoice("", 10)
        volumes.setScreen("x".repeat(65), 10)
        volumes.flush()
        runCurrent()
        assertEquals(VolumeLevels(), volumes.levels.value)
        assertTrue(meta.keysWithPrefix("voice").executeAsList().isEmpty())
    }

    @Test
    fun valorEstragadoNoBancoEIgnorado() = runTest {
        val meta = meta()
        meta.put("voice_volume:bia", "abc")
        meta.put("voice_volume:caio", "250")
        meta.put("voice_volume:dani", "-1")
        meta.put("voice_volume:edu", "070")
        meta.put("voice_volume:fabi", "")
        meta.put("voice_volume:gabi", "1e2")
        meta.put("voice_volume:", "40") // sem usuário
        meta.put("voice_volume:" + "x".repeat(65), "40") // id grande demais
        meta.put("voice_volume:hugo", "100") // o normal não precisa estar guardado
        meta.put("voiceXvolume:ivo", "10") // o LIKE trata "_" como curinga; o prefixo é conferido depois
        meta.put("voice_volume:judite", "40")
        meta.put("voice_screen_volume:judite", "x")
        val volumes = volumes(meta)
        runCurrent()
        volumes.awaitLoaded()
        assertEquals(VolumeLevels(voice = mapOf("judite" to 40)), volumes.levels.value)
    }

    @Test
    fun doisBancosDeContaFicamIsolados() = runTest {
        val accountA = meta()
        val accountB = meta()
        val a = volumes(accountA)
        val b = volumes(accountB)

        // O mesmo id de usuário em duas contas é outra pessoa.
        a.setVoice("bia", 10)
        a.flush()
        runCurrent()
        assertEquals(10, a.levels.value.voiceOf("bia"))
        assertEquals(100, b.levels.value.voiceOf("bia"))
        assertNull(accountB.value("voice_volume:bia"))

        b.setVoice("bia", 180)
        b.flush()
        runCurrent()
        assertEquals("10", accountA.value("voice_volume:bia"))
        assertEquals("180", accountB.value("voice_volume:bia"))
    }

    @Test
    fun oQueOUsuarioMexeuAntesDaLeituraTerminarVence() = runTest {
        val meta = meta()
        meta.put("voice_volume:bia", "30")
        meta.put("voice_volume:caio", "60")
        val volumes = volumes(meta)
        volumes.setVoice("bia", 80) // a leitura ainda não rodou
        volumes.setScreen("caio", 90)
        runCurrent()
        volumes.awaitLoaded()

        assertEquals(VolumeLevels(voice = mapOf("bia" to 80, "caio" to 60), screen = mapOf("caio" to 90)), volumes.levels.value)
    }

    @Test
    fun awaitLoadedEsperaALeitura() = runTest {
        val meta = meta()
        meta.put("voice_volume:bia", "30")
        val volumes = volumes(meta)
        val done = CompletableDeferred<Unit>()
        backgroundScope.launch {
            volumes.awaitLoaded()
            done.complete(Unit)
        }
        assertTrue(!done.isCompleted)
        runCurrent()
        assertTrue(done.isCompleted)
    }

    @Test
    fun falhaNoBancoNaoPerdeOVolumeNemTravaAChamada() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AccountDb.Schema.create(it) }
        val meta = AccountDb(driver).metaQueries
        val volumes = volumes(meta)
        driver.close() // o banco some: a leitura e a gravação falham
        assertFails { meta.put("voice_volume:bia", "1") }

        volumes.setVoice("bia", 25)
        volumes.flush()
        runCurrent()
        advanceTimeBy(StoredVoiceVolumes.DEBOUNCE)
        runCurrent()

        // Sem exceção solta, a espera da chamada termina e o volume escolhido continua valendo.
        volumes.awaitLoaded()
        assertEquals(25, volumes.levels.value.voiceOf("bia"))
    }
}
