package chat.papo.mobile.media

import chat.papo.mobile.data.api.OutgoingFile
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Teto do que a prévia de uma imagem pendente lê para a memória: uma foto de câmera cabe com folga, e um arquivo maior
 * que isto (o servidor aceita até 100 MiB) não é aberto, para a prévia não esgotar a memória do aparelho.
 */
const val PREVIEW_MAX_BYTES = 32 * 1024 * 1024

/**
 * O conteúdo de [this] em memória, ou null se passa de [maxBytes]: pelo tamanho que o provedor declarou, sem sequer abrir
 * o arquivo, ou, sem tamanho declarado, pelo que o fluxo entrega. Lê no máximo [maxBytes] mais um byte e sempre fecha o
 * fluxo, também quando desiste no meio ou é cancelada. Uma falha ao abrir ou ler passa para quem chamou. Faz leitura
 * bloqueante do provedor: chame fora da thread principal.
 */
suspend fun OutgoingFile.readAtMost(maxBytes: Int): ByteArray? {
    if ((size ?: 0L) > maxBytes) return null
    val channel = open()
    return try {
        val bytes = channel.readRemaining(maxBytes + 1L).readByteArray()
        // Uma falha do provedor no meio fecha o fluxo com a causa, e a leitura a vê como o fim do arquivo: sem isto a
        // prévia seria a de um arquivo cortado.
        channel.closedCause?.let { throw it }
        bytes.takeIf { it.size <= maxBytes }
    } finally {
        channel.cancel()
    }
}
