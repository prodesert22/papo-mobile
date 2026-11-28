package chat.papo.mobile

import chat.papo.mobile.voice.CallMember
import chat.papo.mobile.voice.CallNotice
import chat.papo.mobile.voice.CallNoticeTexts
import chat.papo.mobile.voice.CallOutside
import chat.papo.mobile.voice.CallPhase
import chat.papo.mobile.voice.CallPresentation
import chat.papo.mobile.voice.CallState
import chat.papo.mobile.voice.PipAspect
import chat.papo.mobile.voice.VideoKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallOutsideTest {
    /** A plataforma falsa: guarda o que a chamada pediu a ela. */
    private class FakePresentation : CallPresentation {
        override val inPip = MutableStateFlow(false)
        val notices = mutableListOf<CallNotice?>()
        val windows = mutableListOf<Pair<Boolean, PipAspect>>()
        var leaves = 0

        override fun setPipEligible(eligible: Boolean, aspect: PipAspect) {
            windows += eligible to aspect
        }

        override fun setNotice(notice: CallNotice?) {
            notices += notice
        }

        override fun leavePip() {
            leaves++
        }
    }

    private val texts = CallNoticeTexts(
        channelLabel = "Chamadas",
        defaultTitle = "Chamada de voz",
        publicText = "Chamada em andamento",
        joining = "Entrando",
        connecting = "Conectando",
        reconnecting = "Reconectando",
        micOn = "Microfone ligado",
        micOff = "Microfone desligado",
        cameraOn = "Câmera ligada",
        sharingScreen = "Transmitindo a tela",
        mute = "Silenciar",
        unmute = "Ativar microfone",
        hangup = "Desligar",
    )

    private class Fixture(initial: CallState) {
        val presentation = FakePresentation()
        val state = MutableStateFlow(initial)
        val channelName = MutableStateFlow<String?>("Sala de teste")
        val focus = MutableStateFlow<VideoKey?>(null)
        val inFront = MutableStateFlow(true)
        var latest = true
    }

    private fun connected(
        members: List<CallMember> = listOf(CallMember("eu", muted = false), CallMember("bia", muted = false)),
        speakers: Set<String> = setOf("bia"),
    ) = CallState(
        "conta",
        "v1",
        phase = CallPhase.Connected,
        members = members.associateBy { it.userId },
        speakers = speakers,
        micMuted = false,
    )

    private fun outside(f: Fixture) = CallOutside(
        presentation = f.presentation,
        texts = texts,
        myUserId = "eu",
        state = f.state,
        channelName = f.channelName,
        focus = f.focus,
        callScreenInFront = f.inFront,
        isLatest = { f.latest },
    )

    @Test
    fun publicaANotificacaoEAJanelaDaChamadaDePe() = runTest {
        val f = Fixture(connected())
        backgroundScope.launch { outside(f).run() }
        runCurrent()

        val notice = f.presentation.notices.single()!!
        assertEquals("Sala de teste", notice.title)
        assertEquals("Microfone ligado", notice.text)
        assertEquals(listOf(true to PipAspect.Square), f.presentation.windows)
    }

    @Test
    fun foraDaTelaDeChamadaSoVozNaoAbreAJanela() = runTest {
        val f = Fixture(connected())
        f.inFront.value = false
        backgroundScope.launch { outside(f).run() }
        runCurrent()
        assertEquals(listOf(false to PipAspect.Square), f.presentation.windows)

        // Ligar a câmera, mesmo fora da tela de chamada, libera a janela.
        f.state.value = f.state.value.copy(cameraOn = true)
        runCurrent()
        assertEquals(true to PipAspect.Square, f.presentation.windows.last())
    }

    @Test
    fun aNotificacaoAcompanhaOEstadoMasSoQuandoMuda() = runTest {
        val f = Fixture(connected())
        backgroundScope.launch { outside(f).run() }
        runCurrent()

        // Quem fala muda a toda hora e não muda o texto: a plataforma não é avisada.
        f.state.value = f.state.value.copy(speakers = setOf("eu"))
        runCurrent()
        f.state.value = f.state.value.copy(speakers = emptySet())
        runCurrent()
        assertEquals(1, f.presentation.notices.size)

        f.state.value = f.state.value.copy(micMuted = true)
        runCurrent()
        assertEquals(2, f.presentation.notices.size)
        assertEquals("Ativar microfone", f.presentation.notices.last()!!.muteLabel)

        f.channelName.value = "Outra sala"
        runCurrent()
        assertEquals("Outra sala", f.presentation.notices.last()!!.title)
    }

    @Test
    fun aProporcaoDaJanelaSegueOConteudo() = runTest {
        val slots = mapOf(0 to VideoKey("bia", VideoKey.CAMERA))
        val f = Fixture(connected())
        backgroundScope.launch { outside(f).run() }
        runCurrent()
        assertEquals(PipAspect.Square, f.presentation.windows.last().second)

        f.state.value = f.state.value.copy(
            members = mapOf("eu" to CallMember("eu", muted = false), "bia" to CallMember("bia", muted = false, cameraOn = true)),
            videoSlots = slots,
        )
        runCurrent()
        assertEquals(PipAspect.Standard, f.presentation.windows.last().second)

        f.state.value = f.state.value.copy(
            members = mapOf("eu" to CallMember("eu", muted = false), "bia" to CallMember("bia", muted = false, screenSharing = true)),
            videoSlots = mapOf(0 to VideoKey("bia", VideoKey.SCREEN)),
        )
        runCurrent()
        assertEquals(PipAspect.Wide, f.presentation.windows.last().second)
    }

    @Test
    fun oFocoMudaAProporcao() = runTest {
        val f = Fixture(
            connected(speakers = emptySet()).copy(
                members = mapOf("eu" to CallMember("eu", muted = false), "bia" to CallMember("bia", muted = false)),
                videoSlots = emptyMap(),
            ),
        )
        backgroundScope.launch { outside(f).run() }
        runCurrent()
        f.focus.value = VideoKey("eu", VideoKey.CAMERA)
        f.state.value = f.state.value.copy(cameraOn = true)
        runCurrent()
        assertEquals(PipAspect.Standard, f.presentation.windows.last().second)
    }

    @Test
    fun terminarALimpaANotificacaoEASaiDaJanela() = runTest {
        val f = Fixture(connected())
        f.presentation.inPip.value = true
        backgroundScope.launch { outside(f).run() }
        runCurrent()

        f.state.value = f.state.value.copy(phase = CallPhase.Ended)
        runCurrent()
        assertNull(f.presentation.notices.last())
        assertEquals(false to PipAspect.Standard, f.presentation.windows.last())
        assertEquals(1, f.presentation.leaves)
    }

    @Test
    fun cancelarOEscopoTambemLimpa() = runTest {
        val f = Fixture(connected())
        val job = launch { outside(f).run() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertNull(f.presentation.notices.last())
        assertEquals(1, f.presentation.leaves)
    }

    @Test
    fun chamadaQueFicouParaTrasNaoMexeNaPlataforma() = runTest {
        val f = Fixture(connected())
        f.latest = false
        backgroundScope.launch { outside(f).run() }
        runCurrent()
        f.state.value = f.state.value.copy(phase = CallPhase.Ended)
        runCurrent()

        // Outra chamada tomou o lugar: nem a notificação dela foi publicada, nem a nova foi apagada ao terminar esta.
        assertTrue(f.presentation.notices.isEmpty())
        assertTrue(f.presentation.windows.isEmpty())
        assertEquals(0, f.presentation.leaves)
    }
}
