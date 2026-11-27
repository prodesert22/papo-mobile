package chat.papo.mobile

import chat.papo.mobile.voice.AudioRoute
import chat.papo.mobile.voice.VolumeLevels
import chat.papo.mobile.voice.gains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceGainsTest {
    private val routes = mapOf(
        "papo-audio-0" to AudioRoute("bia", AudioRoute.MICROPHONE),
        "papo-audio-1" to AudioRoute("caio", AudioRoute.MICROPHONE),
        "papo-screen-audio-0" to AudioRoute("bia", AudioRoute.SCREEN_AUDIO),
    )

    @Test
    fun semVolumeGuardadoTocaNoNormal() {
        assertEquals(
            mapOf("papo-audio-0" to 1.0, "papo-audio-1" to 1.0, "papo-screen-audio-0" to 1.0),
            gains(routes, VolumeLevels()),
        )
    }

    @Test
    fun porcentagemViraFracao() {
        val levels = VolumeLevels(voice = mapOf("bia" to 30, "caio" to 0))
        val result = gains(routes, levels)
        assertEquals(0.3, result["papo-audio-0"])
        assertEquals(0.0, result["papo-audio-1"])
    }

    @Test
    fun voiceETelaDaMesmaPessoaTemVolumesSeparados() {
        val levels = VolumeLevels(voice = mapOf("bia" to 50), screen = mapOf("bia" to 150))
        val result = gains(routes, levels)
        assertEquals(0.5, result["papo-audio-0"])
        assertEquals(1.5, result["papo-screen-audio-0"])
        assertEquals(1.0, result["papo-audio-1"])
    }

    @Test
    fun limitaEntreZeroEDois() {
        val levels = VolumeLevels(voice = mapOf("bia" to 250, "caio" to -20), screen = mapOf("bia" to 200))
        val result = gains(routes, levels)
        assertEquals(2.0, result["papo-audio-0"])
        assertEquals(0.0, result["papo-audio-1"])
        assertEquals(2.0, result["papo-screen-audio-0"])
    }

    @Test
    fun rotaDeTipoDesconhecidoFicaDeFora() {
        val result = gains(mapOf("papo-x-0" to AudioRoute("bia", "video")), VolumeLevels(voice = mapOf("bia" to 10)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun soHaGanhoParaAsRotasQueExistem() {
        // O volume guardado de quem não está em nenhuma rota não gera nada; sem rotas, nada.
        val levels = VolumeLevels(voice = mapOf("dani" to 10))
        assertEquals(setOf("papo-audio-0", "papo-audio-1", "papo-screen-audio-0"), gains(routes, levels).keys)
        assertTrue(gains(emptyMap(), levels).isEmpty())
    }
}
