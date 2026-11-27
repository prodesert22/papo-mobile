package chat.papo.mobile.voice

/** Os textos fixos da notificação da chamada e dos botões da janela flutuante, resolvidos de `strings.xml` na abertura da sessão. */
class CallNoticeTexts(
    val channelLabel: String,
    /** O título quando o canal não tem nome (ainda não carregou). */
    val defaultTitle: String,
    val publicText: String,
    val joining: String,
    val connecting: String,
    val reconnecting: String,
    val micOn: String,
    val micOff: String,
    val cameraOn: String,
    val sharingScreen: String,
    val mute: String,
    val unmute: String,
    val hangup: String,
)

/** Junta as partes do que está ativo ("Microfone desligado · Câmera ligada"). */
private const val SEPARATOR = " · "

/**
 * A notificação da chamada para o estado [state]. O texto diz o que está ativo; enquanto a chamada ainda não está de pé, diz
 * onde ela está (entrando, conectando, reconectando). [channelName] é o nome do canal de voz, que só a versão completa mostra.
 */
fun callNotice(state: CallState, channelName: String?, texts: CallNoticeTexts): CallNotice {
    val text = when (state.phase) {
        CallPhase.Joining -> texts.joining
        CallPhase.Connecting -> texts.connecting
        CallPhase.Reconnecting -> texts.reconnecting
        CallPhase.Connected, CallPhase.Ended -> buildList {
            add(if (state.micMuted) texts.micOff else texts.micOn)
            if (state.cameraOn) add(texts.cameraOn)
            if (state.screenOn) add(texts.sharingScreen)
        }.joinToString(SEPARATOR)
    }
    return CallNotice(
        channelLabel = texts.channelLabel,
        title = channelName?.takeIf { it.isNotBlank() } ?: texts.defaultTitle,
        text = text,
        publicText = texts.publicText,
        muteLabel = if (state.micMuted) texts.unmute else texts.mute,
        hangupLabel = texts.hangup,
        muted = state.micMuted,
        canMute = !state.pushToTalk,
    )
}
