package chat.papo.mobile.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.takeWhile

/**
 * Mantém a plataforma ([CallPresentation]) em dia com a chamada: a notificação, os botões e a proporção da janela flutuante,
 * e se ela pode abrir. Uma instância vale por uma chamada; [run] termina quando a chamada acaba.
 */
internal class CallOutside(
    private val presentation: CallPresentation,
    private val texts: CallNoticeTexts,
    private val myUserId: String,
    private val state: StateFlow<CallState>,
    private val channelName: Flow<String?>,
    /** O vídeo que a pessoa ampliou na tela de chamada. */
    private val focus: StateFlow<VideoKey?>,
    /** A tela de chamada está à frente. */
    private val callScreenInFront: StateFlow<Boolean>,
    /** Falso quando outra chamada já tomou o lugar desta: a que ficou para trás não pode apagar nem sobrescrever a nova. */
    private val isLatest: () -> Boolean,
) {
    private class Snapshot(val state: CallState, val channelName: String?, val focus: VideoKey?, val inFront: Boolean)

    private var notice: CallNotice? = null
    private var pip: Pair<Boolean, PipAspect>? = null

    suspend fun run() {
        try {
            combine(state, channelName, focus, callScreenInFront, ::Snapshot)
                .takeWhile { it.state.phase != CallPhase.Ended }
                .collect(::publish)
        } finally {
            // Termine a chamada ou o escopo seja cancelado, nada dela fica na notificação nem na janela flutuante.
            if (isLatest()) {
                presentation.setNotice(null)
                presentation.setPipEligible(false, PipAspect.Standard)
                presentation.leavePip()
            }
        }
    }

    private fun publish(snapshot: Snapshot) {
        if (!isLatest()) return
        val next = callNotice(snapshot.state, snapshot.channelName, texts)
        if (next != notice) {
            notice = next
            presentation.setNotice(next)
        }
        val window = pipEligible(snapshot.state, snapshot.inFront) to pipContent(snapshot.state, myUserId, snapshot.focus).aspect
        if (window != pip) {
            pip = window
            presentation.setPipEligible(window.first, window.second)
        }
    }
}
