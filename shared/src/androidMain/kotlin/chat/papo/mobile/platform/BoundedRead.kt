package chat.papo.mobile.platform

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Lê [input] até o fim, mas só se couber em [limit] bytes: devolve null se tiver mais que isso, sem ler além de um byte
 * depois do limite. Não fecha o fluxo. Uma falha de leitura (`IOException`) sobe para quem chamou.
 */
internal fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(CHUNK)
    var total = 0
    while (true) {
        // Pede um byte além do que falta: é ele que mostra que o conteúdo passou do limite.
        val read = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (read < 0) return out.toByteArray()
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
}

private const val CHUNK = 64 * 1024
