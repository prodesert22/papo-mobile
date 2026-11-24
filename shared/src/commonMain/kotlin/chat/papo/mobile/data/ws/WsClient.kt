package chat.papo.mobile.data.ws

import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.voice.VoiceSignal
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class WsState { Disconnected, Connecting, Connected, Unauthorized }

/**
 * Conexão de tempo real de uma conta. Mantém o WebSocket aberto com heartbeat
 * e reconecta com espera crescente. A cada (re)conexão o [state] volta a
 * [WsState.Connected], sinal para quem consome ressincronizar o que perdeu.
 */
class WsClient(
    private val client: PapoClient,
    private val scope: CoroutineScope,
    /** Confere a sessão por REST (o que dispara o refresh do cookie); false se a sessão acabou. */
    private val sessionStillValid: suspend () -> Boolean,
    private val heartbeatInterval: Duration = 25.seconds,
    private val silenceTimeout: Duration = 70.seconds,
) : VoiceSignal {
    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(WsState.Disconnected)
    override val state: StateFlow<WsState> = _state.asStateFlow()

    private var job: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        _state.value = WsState.Disconnected
    }

    /** Envia um comando; devolve false se não há conexão. */
    override suspend fun send(command: WsCommand): Boolean {
        val current = session ?: return false
        return try {
            current.send(Frame.Text(WsCommand.encode(command)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun run() {
        var attempt = 0
        var connectedOnce = false
        while (scope.isActive) {
            _state.value = WsState.Connecting
            try {
                val opened = client.http.webSocketSession(client.server.webSocketUrl) {
                    client.cookieHeader()?.let { header(HttpHeaders.Cookie, it) }
                }
                session = opened
                attempt = 0
                connectedOnce = true
                _state.value = WsState.Connected
                pump(opened)
            } catch (e: CancellationException) {
                session?.let { runCatching { it.close() } }
                session = null
                throw e
            } catch (e: Exception) {
                // cai para a reconexão
            }
            session = null
            _state.value = WsState.Disconnected

            // Handshake recusado costuma ser cookie vencido: uma chamada REST renova a sessão.
            if ((attempt > 0 || !connectedOnce) && !sessionStillValid()) {
                _state.value = WsState.Unauthorized
                return
            }
            delay(backoff(attempt++))
        }
    }

    private suspend fun pump(session: DefaultClientWebSocketSession) = coroutineScope {
        var lastReceived = TimeSource.Monotonic.markNow()
        val heartbeat = launch {
            while (isActive) {
                delay(heartbeatInterval)
                if (lastReceived.elapsedNow() > silenceTimeout) {
                    session.close()
                    return@launch
                }
                session.send(Frame.Text(WsCommand.encode(WsCommand.Heartbeat)))
            }
        }
        try {
            for (frame in session.incoming) {
                lastReceived = TimeSource.Monotonic.markNow()
                if (frame is Frame.Text) WsEvent.parse(frame.readText())?.let { _events.emit(it) }
            }
        } finally {
            heartbeat.cancel()
        }
    }

    private fun backoff(attempt: Int): Duration {
        val base = (1 shl attempt.coerceAtMost(5)).coerceAtMost(30)
        return base.seconds + Random.nextInt(0, 1000).let { Duration.parse("${it}ms") }
    }
}
