package chat.papo.mobile

import chat.papo.mobile.voice.CallEnd
import chat.papo.mobile.voice.CallManager
import chat.papo.mobile.voice.CallPhase
import chat.papo.mobile.voice.EngineConnection
import chat.papo.mobile.voice.IceServer
import chat.papo.mobile.voice.LocalCandidate
import chat.papo.mobile.voice.MediaPermission
import chat.papo.mobile.voice.VideoTrackHandle
import chat.papo.mobile.voice.VoiceEngine
import chat.papo.mobile.voice.VoiceSlots
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {
    /** Motor que fica parado no pedido de permissão até o teste liberar: a chamada permanece "entrando". */
    private class HeldEngine : VoiceEngine {
        val permission = CompletableDeferred<Boolean>()
        var closed = false
        override val connection = MutableStateFlow(EngineConnection.New)
        override val remoteVideo = MutableStateFlow<List<VideoTrackHandle?>>(emptyList())
        override val localCamera = MutableStateFlow<VideoTrackHandle?>(null)
        override val localScreen = MutableStateFlow<VideoTrackHandle?>(null)
        override suspend fun ensurePermission(permission: MediaPermission) = this.permission.await()
        override suspend fun open(iceServers: List<IceServer>, slots: VoiceSlots, onCandidate: (LocalCandidate) -> Unit) = Unit
        override suspend fun createOffer() = "offer"
        override suspend fun applyAnswer(sdp: String) = Unit
        override fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) = Unit
        override fun setMicrophoneEnabled(enabled: Boolean) = Unit
        override suspend fun startCamera() = false
        override suspend fun stopCamera() = Unit
        override fun switchCamera() = Unit
        override suspend fun startScreen() = false
        override suspend fun stopScreen() = Unit
        override fun setSpeakerphone(enabled: Boolean) = Unit
        override fun close() {
            closed = true
        }
    }

    private val engine = MockEngine {
        respond("""{"ice_servers":[{"urls":["stun:stun.test:3478"]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun semMotorDeChamadasNaoHaChamada() = runTest {
        val calls = CallManager(null, backgroundScope)
        assertFalse(calls.supported)
        calls.join(TestResources.session(engine, this), "v1")
        assertNull(calls.current.value)
        assertFalse(calls.active.value)
    }

    @Test
    fun umaChamadaPorVezEntrarEmOutraSaiDaAnterior() = runTest {
        val engines = mutableListOf<HeldEngine>()
        val calls = CallManager({ HeldEngine().also { engines += it } }, backgroundScope)
        val session = TestResources.session(engine, this)

        calls.join(session, "v1")
        val first = calls.current.value!!
        assertTrue(calls.active.value)
        assertEquals(CallPhase.Joining, first.state.value.phase)

        // Tocar de novo no mesmo canal não recomeça a chamada.
        calls.join(session, "v1")
        assertSame(first, calls.current.value)
        assertEquals(1, engines.size)

        calls.join(session, "v2")
        runCurrent()
        val second = calls.current.value!!
        assertNotSame(first, second)
        assertEquals("v2", second.channelId)
        assertEquals(CallEnd.Left, first.state.value.end)
        assertTrue(engines[0].closed)
        assertFalse(engines[1].closed)
        assertTrue(calls.active.value) // a nova chamada segue em curso
    }

    @Test
    fun sairLimpaAChamada() = runTest {
        val calls = CallManager({ HeldEngine() }, backgroundScope)
        calls.join(TestResources.session(engine, this), "v1")
        calls.leave()
        runCurrent()
        assertNull(calls.current.value)
        assertFalse(calls.active.value)
    }

    @Test
    fun chamadaQueFalhaFicaVisivelAteOUsuarioFechar() = runTest {
        val held = HeldEngine()
        val calls = CallManager({ held }, backgroundScope)
        calls.join(TestResources.session(engine, this), "v1")
        val controller = calls.current.value!!

        held.permission.complete(false) // usuário negou o microfone
        controller.state.first { it.phase == CallPhase.Ended }
        runCurrent()

        assertEquals(CallEnd.NoMicrophone, controller.state.value.end)
        assertFalse(calls.active.value) // já não segura o bloqueio do app
        assertSame(controller, calls.current.value) // mas o motivo continua na tela
        calls.dismissEnded()
        assertNull(calls.current.value)
    }

    @Test
    fun semWebSocketConectadoAEntradaTerminaComoConexaoPerdida() = runTest {
        val held = HeldEngine()
        val calls = CallManager({ held }, backgroundScope)
        calls.join(TestResources.session(engine, this), "v1")
        held.permission.complete(true)

        val ended = calls.current.value!!.state.first { it.phase == CallPhase.Ended }
        assertEquals(CallEnd.ConnectionLost, ended.end)
        assertTrue(held.closed)
    }
}
