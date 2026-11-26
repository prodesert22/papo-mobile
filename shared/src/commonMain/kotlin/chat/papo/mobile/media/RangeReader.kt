package chat.papo.mobile.media

import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.api.toApiException
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray

/** O [RangeReader] precisaria baixar além do orçamento de bytes: quem lê desiste, em vez de baixar o arquivo. */
class ByteBudgetExceeded(val budget: Long) : Exception("Orçamento de $budget bytes esgotado")

/** O servidor não atendeu o trecho pedido como o contrato prevê (sem suporte a `Range`, arquivo menor que o declarado). */
class RangeReadFailed(message: String) : Exception(message)

/** Busca um trecho de um arquivo remoto: os bytes `start..endInclusive` (inclusive), exatamente, ou lança. */
fun interface RangeFetch {
    suspend fun fetch(start: Long, endInclusive: Long): ByteArray
}

/**
 * Leitura aleatória de um arquivo remoto, em blocos, com um teto de bytes baixados. Serve a quem precisa de pedaços
 * espalhados de um arquivo grande (o decodificador de vídeo lê o início, salta para o `moov` no fim e volta para um
 * quadro): cada bloco vem de um pedido `Range`, fica só em memória e não é pedido duas vezes.
 *
 * Passando de [budget] bytes baixados, [readAt] lança [ByteBudgetExceeded] antes de pedir o bloco que estouraria: o
 * arquivo nunca é baixado inteiro por engano. Entre dois pedidos vai uma pausa de [pauseMs], para uma extração não
 * encostar no limite de pedidos por segundo do servidor. É seguro chamar de várias threads (uma leitura por vez).
 * Os blocos vêm de [source].
 */
class RangeReader(
    /** Tamanho do arquivo, em bytes: o que o contrato do anexo declara. */
    val size: Long,
    private val source: RangeFetch,
    private val blockSize: Int = POSTER_BLOCK_BYTES,
    private val budget: Long = POSTER_BUDGET_BYTES,
    private val pauseMs: Long = POSTER_REQUEST_PAUSE_MS,
) {
    init {
        require(blockSize > 0) { "blockSize deve ser positivo" }
    }

    private val lock = Mutex()
    private val blocks = HashMap<Long, ByteArray>()

    /** Bytes baixados até agora. */
    var fetchedBytes = 0L
        private set

    /** Pedidos feitos até agora (inclusive o que falhou). */
    var requests = 0
        private set

    /**
     * Copia para [into] (a partir de [offset]) até [length] bytes do arquivo lidos de [position], e devolve quantos
     * foram: menos que [length] só no fim do arquivo. Devolve -1 se [position] já está no fim ou depois dele.
     */
    suspend fun readAt(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= into.size) { "intervalo inválido" }
        if (length == 0) return 0
        if (position >= size) return -1
        return lock.withLock {
            val end = minOf(size, position + length)
            var cursor = position
            while (cursor < end) {
                val block = block(cursor / blockSize)
                val inBlock = (cursor % blockSize).toInt()
                val count = minOf(block.size - inBlock, (end - cursor).toInt())
                block.copyInto(into, offset + (cursor - position).toInt(), inBlock, inBlock + count)
                cursor += count
            }
            (end - position).toInt()
        }
    }

    private suspend fun block(index: Long): ByteArray {
        blocks[index]?.let { return it }
        val start = index * blockSize
        val length = minOf(blockSize.toLong(), size - start).toInt()
        if (fetchedBytes + length > budget) throw ByteBudgetExceeded(budget)
        if (requests > 0) delay(pauseMs)
        requests++
        val bytes = source.fetch(start, start + length - 1)
        if (bytes.size != length) throw RangeReadFailed("Pedido de $length bytes devolveu ${bytes.size}")
        fetchedBytes += length
        blocks[index] = bytes
        return bytes
    }
}

/**
 * Busca um trecho de [url], um endereço do servidor da conta, pelo cliente dessa conta: o cookie `Auth` e a renovação
 * única depois de um 401 vêm do interceptador dele (regra 4), nada sai por outro caminho. Lê só o tamanho pedido.
 */
class HttpRangeFetch(private val client: PapoClient, private val url: String) : RangeFetch {
    override suspend fun fetch(start: Long, endInclusive: Long): ByteArray {
        val wanted = endInclusive - start + 1
        return client.http.prepareGet(url) {
            header(HttpHeaders.Range, "bytes=$start-$endInclusive")
            // O Range conta bytes do arquivo, não de um corpo comprimido.
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            if (!response.status.isSuccess()) throw response.toApiException()
            // Sem suporte a Range o servidor manda o arquivo inteiro (200): só o começo serve, e só o tamanho pedido
            // é lido; quem estaria no meio dele exigiria baixar tudo o que vem antes.
            if (response.status != HttpStatusCode.PartialContent && start != 0L) {
                throw RangeReadFailed("O servidor ignorou o Range")
            }
            response.bodyAsChannel().readRemaining(wanted).readByteArray()
        }
    }
}
