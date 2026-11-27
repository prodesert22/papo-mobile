package chat.papo.mobile.voice

import chat.papo.mobile.account.AccountSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A chamada em curso. Só existe uma por vez no app, seja qual for a conta. */
class CallManager(private val engines: VoiceEngineFactory?, private val scope: CoroutineScope) {
    private val _current = MutableStateFlow<CallController?>(null)
    val current: StateFlow<CallController?> = _current.asStateFlow()

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
            volumes = session.voiceVolumes,
        )
        _current.value = controller
        _active.value = true
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

    /** Fecha o aviso de uma chamada que terminou. */
    fun dismissEnded() {
        val controller = _current.value ?: return
        if (controller.state.value.phase == CallPhase.Ended) _current.value = null
    }
}
