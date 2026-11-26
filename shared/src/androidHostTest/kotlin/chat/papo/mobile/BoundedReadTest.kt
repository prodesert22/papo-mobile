package chat.papo.mobile

import chat.papo.mobile.platform.readAtMost
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A leitura do conteúdo que o teclado entrega: inteiro até o limite, e nada além dele. */
class BoundedReadTest {
    private fun bytes(count: Int) = ByteArray(count) { (it % 251).toByte() }

    @Test
    fun conteudoVazioDevolveUmArrayVazio() {
        assertContentEquals(ByteArray(0), readAtMost(ByteArrayInputStream(ByteArray(0)), 10))
    }

    @Test
    fun conteudoMenorQueOLimiteVemInteiro() {
        val content = bytes(7)
        assertContentEquals(content, readAtMost(ByteArrayInputStream(content), 10))
    }

    @Test
    fun conteudoDoTamanhoDoLimiteVemInteiro() {
        val content = bytes(10)
        assertContentEquals(content, readAtMost(ByteArrayInputStream(content), 10))
    }

    @Test
    fun umByteAlemDoLimiteRecusa() {
        assertNull(readAtMost(ByteArrayInputStream(bytes(11)), 10))
    }

    @Test
    fun limiteZeroSoAceitaVazio() {
        assertContentEquals(ByteArray(0), readAtMost(ByteArrayInputStream(ByteArray(0)), 0))
        assertNull(readAtMost(ByteArrayInputStream(bytes(1)), 0))
    }

    @Test
    fun conteudoMaiorQueUmPedacoDeLeituraVemInteiro() {
        val content = bytes(200_000)
        assertContentEquals(content, readAtMost(ByteArrayInputStream(content), 200_000))
        assertNull(readAtMost(ByteArrayInputStream(content), 199_999))
    }

    @Test
    fun fluxoQueEntregaPoucosBytesPorLeituraVemInteiro() {
        val content = bytes(1_000)
        assertContentEquals(content, readAtMost(Trickle(content, 3), 1_000))
    }

    @Test
    fun naoLeAlemDeUmByteDepoisDoLimite() {
        val stream = Counting(bytes(1_000_000))
        assertNull(readAtMost(stream, 100))
        assertEquals(101, stream.read, "o byte a mais é o que mostra que passou do limite")
    }

    @Test
    fun falhaDeLeituraSobeParaQuemChamou() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("falhou")
        }
        assertFailsWith<IOException> { readAtMost(broken, 10) }
    }

    /** Entrega no máximo [step] bytes por leitura, como um provedor lento. */
    private class Trickle(private val content: ByteArray, private val step: Int) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < content.size) content[position++].toInt() and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= content.size) return -1
            val count = minOf(step, length, content.size - position)
            content.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    /** Conta os bytes que saíram. */
    private class Counting(private val content: ByteArray) : InputStream() {
        var read = 0
            private set

        override fun read(): Int = if (read < content.size) content[read++].toInt() and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (read >= content.size) return -1
            val count = minOf(length, content.size - read)
            content.copyInto(buffer, offset, read, read + count)
            read += count
            return count
        }
    }
}
