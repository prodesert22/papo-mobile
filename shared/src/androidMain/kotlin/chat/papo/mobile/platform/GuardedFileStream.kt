package chat.papo.mobile.platform

import chat.papo.mobile.data.api.FileUnavailableException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * O fluxo de um arquivo do provedor, em que uma falha de leitura vira [FileUnavailableException] com o nome do arquivo.
 * Quem lê o corpo da requisição só vê "falhou"; aqui se sabe que foi o arquivo, e não a rede, que parou no meio.
 */
internal class GuardedFileStream(stream: InputStream, private val name: String) : FilterInputStream(stream) {
    override fun read(): Int = guard { super.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = guard { super.read(buffer, offset, length) }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw FileUnavailableException(name, e)
    }
}
