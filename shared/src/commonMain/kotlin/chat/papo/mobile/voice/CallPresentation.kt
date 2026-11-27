package chat.papo.mobile.voice

import kotlinx.coroutines.flow.StateFlow

/** A proporção da janela flutuante (picture-in-picture), que acompanha o que ela mostra. */
data class PipAspect(val width: Int, val height: Int) {
    companion object {
        /** Tela transmitida. */
        val Wide = PipAspect(16, 9)

        /** Câmera. */
        val Standard = PipAspect(4, 3)

        /** Avatar, numa chamada só de voz. */
        val Square = PipAspect(1, 1)
    }
}

/**
 * O que a chamada mostra fora da tela do app (notificação e botões da janela flutuante), já em texto: quem monta é o código
 * comum, de `strings.xml` (regra 10); a plataforma só desenha e liga os botões ao serviço da chamada.
 *
 * [title] e [text] são da notificação completa e trazem o nome do canal; [publicText] é a versão da tela de bloqueio, que não
 * tem nome de canal nem de servidor. [muted] diz qual ícone o botão de silenciar usa e [muteLabel] já é o texto certo para o
 * estado (silenciar ou ativar); [canMute] é falso com o apertar-para-falar ligado, em que o botão não existe.
 */
data class CallNotice(
    val channelLabel: String,
    val title: String,
    val text: String,
    val publicText: String,
    val muteLabel: String,
    val hangupLabel: String,
    val muted: Boolean,
    val canMute: Boolean,
)

/**
 * Como a chamada aparece quando o app não está em tela cheia: a janela flutuante e a notificação. O código comum diz o que
 * mostrar e quando a janela pode abrir; a plataforma implementa (no Android, a `MainActivity` e o serviço da chamada).
 */
interface CallPresentation {
    /** A janela do app está flutuante agora. */
    val inPip: StateFlow<Boolean>

    /** Declara se a janela pode virar flutuante (ao sair do app) e a proporção que ela deve ter. Pode ser chamada a cada mudança. */
    fun setPipEligible(eligible: Boolean, aspect: PipAspect)

    /** A notificação e os botões da janela flutuante; nulo quando não há chamada. */
    fun setNotice(notice: CallNotice?)

    /** Sai da janela flutuante e volta à tela cheia (a chamada terminou); sem janela flutuante, não faz nada. */
    fun leavePip()
}
