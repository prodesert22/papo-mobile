package chat.papo.mobile.push

import kotlinx.coroutines.flow.Flow

/**
 * O token de push do aparelho (no Android, o do Firebase Cloud Messaging). É um só por instalação do app, e não de uma
 * conta: cada conta o registra no próprio servidor ([PushRegistration]). Quem tem o token pode mandar notificação ao
 * aparelho; por isso ele nunca vai para log nem para mensagem de erro.
 */
interface PushTokens {
    /** O aparelho tem como receber push (o app foi montado com o projeto Firebase). Sem isso nada abaixo é chamado. */
    val available: Boolean

    /** O modelo do aparelho, que o servidor mostra a quem o registrou. Quem registra o corta com [fitDeviceName]. */
    val deviceName: String

    /** O token de agora, ou nulo se a plataforma não conseguiu um (sem rede, sem Play Services). Pode demorar. */
    suspend fun current(): String?

    /**
     * Os tokens que a plataforma troca, em ordem. Quem começa a ouvir recebe logo o último que ela trocou, se algum, para
     * que a troca que veio antes de ouvir não se perca; repetir o mesmo token é normal.
     */
    val changes: Flow<String>
}

/** O limite de `device_name` no servidor (`PushDeviceRequest`), contado em bytes UTF-8, como o servidor conta. */
const val MAX_DEVICE_NAME_BYTES = 32

/** O nome que vai ao servidor: sem espaços nas pontas, cortado em [MAX_DEVICE_NAME_BYTES] bytes sem partir um caractere. */
fun fitDeviceName(name: String): String {
    val trimmed = name.trim()
    var bytes = 0
    var end = 0
    while (end < trimmed.length) {
        val c = trimmed[end]
        val pair = c.isHighSurrogate() && end + 1 < trimmed.length && trimmed[end + 1].isLowSurrogate()
        val size = when {
            pair -> 4
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
        if (bytes + size > MAX_DEVICE_NAME_BYTES) break
        bytes += size
        end += if (pair) 2 else 1
    }
    return trimmed.substring(0, end).trim()
}
