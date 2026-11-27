package chat.papo.mobile.voice

/**
 * A janela flutuante só abre com a chamada de pé (conectando, conectada ou reconectando). Além disso, ou a tela de chamada
 * está à frente, ou a pessoa está transmitindo a tela ou a câmera: aí ela sai do app para mostrar outra coisa e quer ver a
 * chamada. Só voz fora da tela de chamada vira notificação, sem janela.
 */
fun pipEligible(state: CallState, callScreenInFront: Boolean): Boolean {
    val live = state.phase == CallPhase.Connecting || state.phase == CallPhase.Connected || state.phase == CallPhase.Reconnecting
    return live && (callScreenInFront || state.screenOn || state.cameraOn)
}

/** O que a janela flutuante mostra. Nunca a própria captura de tela (ela se espelharia sem fim). */
sealed interface PipContent {
    val aspect: PipAspect

    /** A tela que [userId] transmite (outra pessoa). */
    data class Screen(val userId: String) : PipContent {
        override val aspect get() = PipAspect.Wide
    }

    /** A câmera de [userId]; a própria (espelhada) quando [mine]. */
    data class Camera(val userId: String, val mine: Boolean) : PipContent {
        override val aspect get() = PipAspect.Standard
    }

    /** A foto de [userId]: não há vídeo para mostrar (chamada só de voz). */
    data class Avatar(val userId: String) : PipContent {
        override val aspect get() = PipAspect.Square
    }
}

/**
 * Escolhe o que mostrar na janela flutuante, nesta ordem:
 * 1. a tela que outra pessoa transmite (a de quem fala, se houver mais de uma);
 * 2. a câmera de quem fala (a minha incluída);
 * 3. a câmera em foco na tela de chamada (uma tela em foco já teria sido escolhida no passo 1);
 * 4. a foto de quem fala;
 * 5. sem ninguém falando: a primeira câmera de outra pessoa, senão a foto da primeira outra pessoa (ou a minha, sozinho).
 *
 * Só conta vídeo que chega: a câmera ou a tela de outra pessoa precisa ter assinatura ([CallState.videoSlots]).
 */
fun pipContent(state: CallState, myUserId: String, focus: VideoKey?): PipContent {
    fun subscribed(userId: String, kind: String) = state.videoSlots.containsValue(VideoKey(userId, kind))
    fun screenOf(userId: String) = userId != myUserId && state.members[userId]?.screenSharing == true && subscribed(userId, VideoKey.SCREEN)
    fun cameraOf(userId: String) = if (userId == myUserId) state.cameraOn else state.members[userId]?.cameraOn == true && subscribed(userId, VideoKey.CAMERA)

    val screens = state.members.keys.filter(::screenOf)
    val screen = screens.firstOrNull { it in state.speakers } ?: screens.firstOrNull()
    if (screen != null) return PipContent.Screen(screen)

    val speakerOnCamera = state.speakers.firstOrNull(::cameraOf)
    if (speakerOnCamera != null) return PipContent.Camera(speakerOnCamera, speakerOnCamera == myUserId)

    // Só a câmera pode estar em foco aqui: uma tela transmitida em foco já foi escolhida acima. O foco pode ter ficado para
    // trás (a pessoa desligou a câmera): só vale se ainda há vídeo.
    if (focus?.kind == VideoKey.CAMERA && cameraOf(focus.userId)) return PipContent.Camera(focus.userId, focus.userId == myUserId)

    val speaker = state.speakers.firstOrNull()
    if (speaker != null) return PipContent.Avatar(speaker)

    val others = state.members.keys.filter { it != myUserId }
    val camera = others.firstOrNull(::cameraOf)
    if (camera != null) return PipContent.Camera(camera, mine = false)
    return PipContent.Avatar(others.firstOrNull() ?: myUserId)
}
