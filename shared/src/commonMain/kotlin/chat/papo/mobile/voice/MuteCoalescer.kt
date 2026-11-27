package chat.papo.mobile.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Junta os pedidos de `voice_mute` para ficar longe do limite de sinalização do servidor (10 por segundo, contando
 * candidatos ICE e câmera): sai no máximo um a cada [interval], e dos que chegaram nesse intervalo vale o último.
 * O primeiro pedido depois de um tempo parado sai na hora, então apertar para falar não ganha atraso. Se o estado
 * final é o que o servidor já tem, nada é enviado.
 *
 * [send] devolve se o comando saiu; se não saiu, o servidor continua com o estado anterior e o próximo pedido tenta
 * de novo. [serverMuted] é o que o servidor sabe no início: quem entra na sala entra mutado. O envio acontece em
 * [scope], que é de quem a chamou e termina com ela.
 */
class MuteCoalescer(
    scope: CoroutineScope,
    private val send: suspend (muted: Boolean) -> Boolean,
    private val interval: Duration = INTERVAL,
    serverMuted: Boolean = true,
) {
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private var serverState = serverMuted

    init {
        scope.launch {
            for (muted in requests) {
                if (muted != serverState && send(muted)) serverState = muted
                // O que chegar durante a espera fica no canal e o último substitui os outros.
                delay(interval)
            }
        }
    }

    /** Pede que o servidor fique com [muted]; o envio respeita o intervalo. */
    fun submit(muted: Boolean) {
        requests.trySend(muted)
    }

    companion object {
        val INTERVAL = 250.milliseconds
    }
}
