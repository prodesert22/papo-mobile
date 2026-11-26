package chat.papo.mobile

import chat.papo.mobile.data.api.OutgoingFile
import io.ktor.utils.io.ByteReadChannel

/** Arquivo de teste com o conteúdo em memória; cada `open` devolve um fluxo novo, como o provedor faria. */
fun bytesFile(name: String, mimeType: String, bytes: ByteArray): OutgoingFile =
    OutgoingFile(name, mimeType, bytes.size.toLong(), open = { ByteReadChannel(bytes) })

/** Arquivo de teste que só diz o tamanho: os limites de uma mensagem não leem o conteúdo. */
fun sizedFile(name: String, size: Long?, mimeType: String = "application/octet-stream"): OutgoingFile =
    OutgoingFile(name, mimeType, size, open = { ByteReadChannel(ByteArray(0)) })
