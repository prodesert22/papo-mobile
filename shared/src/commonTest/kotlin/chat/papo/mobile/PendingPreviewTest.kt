package chat.papo.mobile

import chat.papo.mobile.data.api.FileUnavailableException
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.media.PREVIEW_MAX_BYTES
import chat.papo.mobile.media.readAtMost
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A leitura, com teto, do arquivo pendente que a prévia mostra inteiro. */
class PendingPreviewTest {
    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    /** Um arquivo que conta quantas vezes é aberto e guarda o último fluxo, para ver se foi fechado. */
    private class Counting(content: ByteArray, declared: Long?) {
        var opened = 0
        var channel: ByteReadChannel? = null
        val file = OutgoingFile("foto.jpg", "image/jpeg", declared, open = {
            opened++
            ByteReadChannel(content).also { channel = it }
        })
    }

    @Test
    fun arquivoQueCabeNoTetoChegaInteiro() = runTest {
        val content = bytes(1_000)
        assertContentEquals(content, bytesFile("a.jpg", "image/jpeg", content).readAtMost(2_000))
    }

    @Test
    fun arquivoDoTamanhoExatoDoTetoAindaCabe() = runTest {
        val content = bytes(500)
        assertContentEquals(content, bytesFile("a.jpg", "image/jpeg", content).readAtMost(500))
    }

    @Test
    fun umByteAlemDoTetoNaoEntra() = runTest {
        assertNull(bytesFile("a.jpg", "image/jpeg", bytes(501)).readAtMost(500))
    }

    @Test
    fun tamanhoDeclaradoAcimaDoTetoNemAbreOArquivo() = runTest {
        val counting = Counting(bytes(10), declared = 501)
        assertNull(counting.file.readAtMost(500))
        assertEquals(0, counting.opened)
    }

    @Test
    fun semTamanhoDeclaradoQuemDecideEOQueOFluxoEntrega() = runTest {
        val small = Counting(bytes(400), declared = null)
        assertContentEquals(bytes(400), small.file.readAtMost(500))

        val large = Counting(bytes(5_000), declared = null)
        assertNull(large.file.readAtMost(500))
        assertEquals(1, large.opened)
        // Desistiu no meio: o fluxo do provedor é fechado, não fica esperando alguém ler o resto.
        assertNotNull(large.channel?.closedCause)
    }

    @Test
    fun arquivoVazioDevolveUmArrayVazioEOTetoDoAppCobreUmaFotoDeCamera() = runTest {
        assertEquals(0, assertNotNull(bytesFile("a.jpg", "image/jpeg", ByteArray(0)).readAtMost(500)).size)
        assertTrue(PREVIEW_MAX_BYTES >= 30 * 1024 * 1024)
    }

    @Test
    fun arquivoQueNaoAbreDeixaAFalhaPassarParaQuemChamou() = runTest {
        val file = OutgoingFile("sumiu.jpg", "image/jpeg", 10, open = { throw FileUnavailableException("sumiu.jpg") })
        assertFailsWith<FileUnavailableException> { file.readAtMost(500) }
    }

    @Test
    fun cancelarALeituraNoMeioFechaOFluxoDoProvedor() = runTest {
        val idle = ByteChannel() // o provedor abriu e ainda não entregou nada
        val file = OutgoingFile("foto.jpg", "image/jpeg", null, open = { idle })
        val reading = launch(start = CoroutineStart.UNDISPATCHED) { file.readAtMost(500) }
        reading.cancelAndJoin()
        assertNotNull(idle.closedCause)
    }

    @Test
    fun falhaNoMeioDaLeituraPassaParaQuemChamou() = runTest {
        val broken = ByteChannel()
        broken.cancel(IllegalStateException("o provedor parou"))
        val file = OutgoingFile("foto.jpg", "image/jpeg", null, open = { broken })
        // O ktor devolve a causa embrulhada num `ClosedByteChannelException`; o que importa é que a leitura não vire "arquivo cortado".
        val failure = assertFailsWith<Exception> { file.readAtMost(500) }
        assertTrue("o provedor parou" in failure.message.orEmpty())
    }
}
