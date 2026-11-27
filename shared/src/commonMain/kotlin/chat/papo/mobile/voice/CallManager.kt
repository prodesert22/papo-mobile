package chat.papo.mobile.voice

import chat.papo.mobile.account.AccountSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A chamada em curso. Só existe uma por vez no app, seja qual for a conta. */
class CallManager(
    private val engines: VoiceEngineFactory?,
    private val scope: CoroutineScope,
    /** A janela flutuante e a notificação da chamada; nulo onde a plataforma não tem (ou nos testes). Vale junto com [noticeTexts]. */
    private val presentation: CallPresentation? = null,
    private val noticeTexts: CallNoticeTexts? = null,
) {
    private val _current = MutableStateFlow<CallController?>(null)
    val current: StateFlow<CallController?> = _current.asStateFlow()

    private val _focus = MutableStateFlow<VideoKey?>(null)

    /** O vídeo que a pessoa ampliou na tela de chamada; a janela flutuante o mostra. */
    val focus: StateFlow<VideoKey?> = _focus.asStateFlow()

    private val _callScreenInFront = MutableStateFlow(false)

    /** A última chamada que entrou, mesmo depois de terminar; a da plataforma só obedece a ela. */
    private var latest: CallController? = null

    private val _active = MutableStateFlow(false)
    /** Há chamada em andamento (mantém o app desbloqueado e conectado em segundo plano). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val supported: Boolean get() = engines != null

    /** Entra no canal de voz; sai antes da chamada anterior, se houver. */
    fun join(session: AccountSession, channelId: String) {
        val factory = engines ?: return
        val existing = _current.value
        if (existing != null && existing.state.value.phase != CallPhase.Ended) {
            if (existing.accountId == session.accountId && existing.channelId == channelId) return
            existing.leave() // o servidor não troca de sala sozinho: precisa sair antes
        }
        val controller = CallController(
            accountId = session.accountId,
            channelId = channelId,
            myUserId = session.userId,
            iceServers = { session.api.iceServers().map { IceServer(it.urls, it.username, it.credential) } },
            ws = session.ws,
            engine = factory.create(),
            parentScope = session.scope,
        )
        _current.value = controller
        _active.value = true
        _focus.value = null
        latest = controller
        outside(session, controller)
        controller.start()
        scope.launch {
            controller.state.first { it.phase == CallPhase.Ended }
            if (_current.value === controller) {
                _active.value = false
                // Saída pedida pelo usuário não tem o que mostrar; os outros motivos ficam na tela até fechar.
                if (controller.state.value.end == CallEnd.Left) _current.value = null
            }
        }
    }

    fun leave() {
        _current.value?.leave()
    }

    /** O botão de silenciar da notificação e da janela flutuante; com apertar-para-falar ele não existe, e um toque velho não vale. */
    fun toggleMute() {
        val controller = _current.value ?: return
        if (controller.state.value.pushToTalk) return
        controller.toggleMute()
    }

    /** A tela de chamada entrou ou saiu da frente; sem ela, a janela flutuante só abre se a pessoa transmite a tela ou a câmera. */
    fun setCallScreenInFront(inFront: Boolean) {
        _callScreenInFront.value = inFront
    }

    fun setFocus(key: VideoKey?) {
        _focus.value = key
    }

    /** Fecha o aviso de uma chamada que terminou. */
    fun dismissEnded() {
        val controller = _current.value ?: return
        if (controller.state.value.phase == CallPhase.Ended) _current.value = null
    }

    /** Liga a chamada à plataforma enquanto ela durar; o fim dela, ou o do escopo, limpa a notificação e a janela flutuante. */
    private fun outside(session: AccountSession, controller: CallController) {
        val target = presentation ?: return
        val texts = noticeTexts ?: return
        val outside = CallOutside(
            presentation = target,
            texts = texts,
            myUserId = session.userId,
            state = controller.state,
            channelName = session.chat.channels.map { channels -> channels.firstOrNull { it.id == controller.channelId }?.name },
            focus = focus,
            callScreenInFront = _callScreenInFront,
            isLatest = { latest === controller },
        )
        scope.launch { outside.run() }
    }
}
