package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.FileUnavailableException
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.platform.GuardedFileStream
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Um fluxo de arquivo que entrega [good] e depois falha, como um provedor que fecha o arquivo no meio da leitura. */
private class BreakingStream(private val good: ByteArray, private val error: IOException) : InputStream() {
    private var position = 0

    override fun read(): Int {
        if (position >= good.size) throw error
        return good[position++].toInt() and 0xFF
    }
}

class GuardedFileStreamTest {
    @Test
    fun entregaOsBytesDoArquivoSemMudarNada() {
        val data = ByteArray(1000) { it.toByte() }
        val stream = GuardedFileStream(ByteArrayInputStream(data), "a.bin")
        assertContentEquals(data, stream.readBytes())
    }

    @Test
    fun falhaDeLeituraViraFileUnavailableComONomeEACausa() {
        val broken = IOException("provedor fechou")
        val stream = GuardedFileStream(BreakingStream(byteArrayOf(1, 2), broken), "video.mp4")

        assertEquals(1, stream.read())
        assertEquals(2, stream.read())
        val failure = assertFailsWith<FileUnavailableException> { stream.read() }
        assertEquals("video.mp4", failure.fileName)
        assertSame(broken, failure.cause)
        // A leitura em bloco passa pelo mesmo caminho.
        assertFailsWith<FileUnavailableException> { GuardedFileStream(BreakingStream(ByteArray(0), broken), "x").read(ByteArray(8), 0, 8) }
    }

    @Test
    fun noEnvioUmaFalhaNoMeioDoArquivoChegaComoFileUnavailable() = runTest {
        // O caminho inteiro do Android: fluxo do provedor -> canal do Ktor -> corpo multipart -> motor HTTP.
        val engine = MockEngine { request ->
            request.body.toByteArray()
            respond("{}", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val broken = IOException("provedor fechou")
        val file = OutgoingFile(
            "foto.jpg", "image/jpeg", 6000,
            open = { GuardedFileStream(BreakingStream(ByteArray(3000), broken), "foto.jpg").toByteReadChannel() },
        )
        val api = ChatApi(PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt")))

        val failure = assertFailsWith<FileUnavailableException> { api.sendMessage("c1", "oi", replyTo = null, files = listOf(file)) }

        assertEquals("foto.jpg", failure.fileName)
    }
}
