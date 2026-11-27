package chat.papo.mobile

import chat.papo.mobile.voice.CallMember
import chat.papo.mobile.voice.CallPhase
import chat.papo.mobile.voice.CallState
import chat.papo.mobile.voice.PipAspect
import chat.papo.mobile.voice.PipContent
import chat.papo.mobile.voice.VideoKey
import chat.papo.mobile.voice.pipContent
import chat.papo.mobile.voice.pipEligible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipPolicyTest {
    private fun state(
        phase: CallPhase = CallPhase.Connected,
        members: List<CallMember> = emptyList(),
        speakers: Set<String> = emptySet(),
        cameraOn: Boolean = false,
        screenOn: Boolean = false,
        slots: Map<Int, VideoKey> = emptyMap(),
    ) = CallState(
        accountId = "conta",
        channelId = "v1",
        phase = phase,
        members = members.associateBy { it.userId },
        speakers = speakers,
        cameraOn = cameraOn,
        screenOn = screenOn,
        videoSlots = slots,
    )

    private val me = CallMember("eu", muted = false)
    private val bia = CallMember("bia", muted = false)
    private val caio = CallMember("caio", muted = false)

    // pipEligible

    @Test
    fun soAbreComAChamadaDePe() {
        for (phase in listOf(CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting)) {
            assertTrue(pipEligible(state(phase = phase), callScreenInFront = true), phase.name)
        }
        assertFalse(pipEligible(state(phase = CallPhase.Joining), callScreenInFront = true))
        assertFalse(pipEligible(state(phase = CallPhase.Ended), callScreenInFront = true))
    }

    @Test
    fun foraDaTelaDeChamadaSoSeTransmiteATelaOuACamera() {
        assertFalse(pipEligible(state(), callScreenInFront = false))
        assertTrue(pipEligible(state(screenOn = true), callScreenInFront = false))
        assertTrue(pipEligible(state(cameraOn = true), callScreenInFront = false))
    }

    @Test
    fun transmitirNaoAbreAJanelaSemAChamadaDePe() {
        assertFalse(pipEligible(state(phase = CallPhase.Joining, screenOn = true, cameraOn = true), callScreenInFront = false))
        assertFalse(pipEligible(state(phase = CallPhase.Ended, screenOn = true), callScreenInFront = true))
    }

    // pipContent: chamada só de voz

    @Test
    fun soVozMostraAFotoDeQuemFala() {
        val content = pipContent(state(members = listOf(me, bia, caio), speakers = setOf("caio")), "eu", null)
        assertEquals(PipContent.Avatar("caio"), content)
        assertEquals(PipAspect.Square, content.aspect)
    }

    @Test
    fun soVozMostraAMinhaFotoQuandoSouEuQuemFala() {
        assertEquals(PipContent.Avatar("eu"), pipContent(state(members = listOf(me, bia), speakers = setOf("eu")), "eu", null))
    }

    @Test
    fun soVozSemNinguemFalandoMostraAFotoDaPrimeiraOutraPessoa() {
        assertEquals(PipContent.Avatar("bia"), pipContent(state(members = listOf(me, bia, caio)), "eu", null))
    }

    @Test
    fun sozinhoNaChamadaMostraAMinhaFoto() {
        assertEquals(PipContent.Avatar("eu"), pipContent(state(members = listOf(me)), "eu", null))
    }

    // pipContent: tela transmitida

    @Test
    fun aTelaDeOutraPessoaVenceTudo() {
        val sharing = bia.copy(screenSharing = true)
        val content = pipContent(
            state(
                members = listOf(me, sharing, caio.copy(cameraOn = true)),
                speakers = setOf("caio"),
                cameraOn = true,
                slots = mapOf(0 to VideoKey("bia", VideoKey.SCREEN), 1 to VideoKey("caio", VideoKey.CAMERA)),
            ),
            "eu",
            VideoKey("caio", VideoKey.CAMERA),
        )
        assertEquals(PipContent.Screen("bia"), content)
        assertEquals(PipAspect.Wide, content.aspect)
    }

    @Test
    fun comDuasTelasFicaComADeQuemFala() {
        val members = listOf(me, bia.copy(screenSharing = true), caio.copy(screenSharing = true))
        val slots = mapOf(0 to VideoKey("bia", VideoKey.SCREEN), 1 to VideoKey("caio", VideoKey.SCREEN))
        assertEquals(PipContent.Screen("caio"), pipContent(state(members = members, speakers = setOf("caio"), slots = slots), "eu", null))
        assertEquals(PipContent.Screen("bia"), pipContent(state(members = members, speakers = setOf("eu"), slots = slots), "eu", null))
    }

    @Test
    fun aMinhaTelaNuncaApareceNaJanela() {
        // Eu transmito a tela e falo: mostrar a captura da tela dentro da tela se repetiria sem fim.
        val content = pipContent(
            state(members = listOf(me.copy(screenSharing = true), bia), speakers = setOf("eu"), screenOn = true),
            "eu",
            null,
        )
        assertEquals(PipContent.Avatar("eu"), content)
    }

    @Test
    fun aMinhaTelaNaoImpedeAVozOuACameraDeOutraPessoa() {
        val slots = mapOf(0 to VideoKey("bia", VideoKey.CAMERA))
        val content = pipContent(
            state(members = listOf(me.copy(screenSharing = true), bia.copy(cameraOn = true)), speakers = setOf("bia"), screenOn = true, slots = slots),
            "eu",
            null,
        )
        assertEquals(PipContent.Camera("bia", mine = false), content)
    }

    @Test
    fun telaSemAssinaturaNaoConta() {
        // Há mais transmissões que slots: a tela de bia não chega e não pode ser escolhida.
        val content = pipContent(state(members = listOf(me, bia.copy(screenSharing = true)), speakers = setOf("bia")), "eu", null)
        assertEquals(PipContent.Avatar("bia"), content)
    }

    // pipContent: câmera

    @Test
    fun aCameraDeQuemFalaVenceOFocoEAFoto() {
        val slots = mapOf(0 to VideoKey("bia", VideoKey.CAMERA), 1 to VideoKey("caio", VideoKey.CAMERA))
        val content = pipContent(
            state(members = listOf(me, bia.copy(cameraOn = true), caio.copy(cameraOn = true)), speakers = setOf("bia"), slots = slots),
            "eu",
            VideoKey("caio", VideoKey.CAMERA),
        )
        assertEquals(PipContent.Camera("bia", mine = false), content)
        assertEquals(PipAspect.Standard, content.aspect)
    }

    @Test
    fun aMinhaCameraApareceEspelhadaQuandoEuFalo() {
        val content = pipContent(state(members = listOf(me.copy(cameraOn = true), bia), speakers = setOf("eu"), cameraOn = true), "eu", null)
        assertEquals(PipContent.Camera("eu", mine = true), content)
    }

    @Test
    fun quemFalaSemCameraDeixaOFocoValer() {
        val slots = mapOf(0 to VideoKey("caio", VideoKey.CAMERA))
        val content = pipContent(
            state(members = listOf(me, bia, caio.copy(cameraOn = true)), speakers = setOf("bia"), slots = slots),
            "eu",
            VideoKey("caio", VideoKey.CAMERA),
        )
        assertEquals(PipContent.Camera("caio", mine = false), content)
    }

    @Test
    fun focoNaMinhaCameraValeEmFicaEspelhada() {
        val content = pipContent(
            state(members = listOf(me.copy(cameraOn = true), bia), speakers = setOf("bia"), cameraOn = true),
            "eu",
            VideoKey("eu", VideoKey.CAMERA),
        )
        assertEquals(PipContent.Camera("eu", mine = true), content)
    }

    @Test
    fun focoQueFicouParaTrasNaoValeNemComCameraDesligadaNemSemAssinatura() {
        val slots = mapOf(0 to VideoKey("caio", VideoKey.CAMERA))
        // Caio desligou a câmera: a foto de quem fala.
        assertEquals(
            PipContent.Avatar("bia"),
            pipContent(state(members = listOf(me, bia, caio), speakers = setOf("bia"), slots = slots), "eu", VideoKey("caio", VideoKey.CAMERA)),
        )
        // A câmera de caio está ligada, mas não chega (sem assinatura).
        assertEquals(
            PipContent.Avatar("bia"),
            pipContent(state(members = listOf(me, bia, caio.copy(cameraOn = true)), speakers = setOf("bia")), "eu", VideoKey("caio", VideoKey.CAMERA)),
        )
        // O foco numa tela só vale se ela ainda está sendo transmitida (e aí ela já venceu no passo 1).
        assertEquals(
            PipContent.Avatar("bia"),
            pipContent(state(members = listOf(me, bia, caio), speakers = setOf("bia")), "eu", VideoKey("caio", VideoKey.SCREEN)),
        )
    }

    @Test
    fun ninguemFalandoMostraAPrimeiraCameraDeOutraPessoa() {
        val slots = mapOf(0 to VideoKey("caio", VideoKey.CAMERA))
        val content = pipContent(state(members = listOf(me, bia, caio.copy(cameraOn = true)), slots = slots), "eu", null)
        assertEquals(PipContent.Camera("caio", mine = false), content)
    }

    @Test
    fun ninguemFalandoNaoMostraSoAMinhaCameraSeHaOutraPessoa() {
        // Eu estou com a câmera ligada, mas sem ninguém falando mostro a foto da outra pessoa, não o meu rosto.
        val content = pipContent(state(members = listOf(me.copy(cameraOn = true), bia), cameraOn = true), "eu", null)
        assertEquals(PipContent.Avatar("bia"), content)
    }
}
