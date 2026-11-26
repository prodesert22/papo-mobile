package chat.papo.mobile.media

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.PapoClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Maior lado do quadro da capa, em pixels: o cartão do vídeo tem no máximo 280 x 320 dp. */
const val POSTER_MAX_PX = 640

/** Tamanho de cada pedido `Range`: poucos pedidos por vídeo, sem trazer muito do que a capa não usa. */
const val POSTER_BLOCK_BYTES = 256 * 1024

/**
 * Teto de bytes baixados para tirar uma capa. Cobre o começo do arquivo, um `moov` de até alguns MB no fim e o primeiro
 * quadro de referência; estourando, o vídeo fica sem capa em vez de ser baixado.
 */
const val POSTER_BUDGET_BYTES = 6L * 1024 * 1024

/**
 * Pausa entre dois pedidos de uma extração. O servidor limita os pedidos por endereço (cerca de 20 por segundo, para o
 * app todo): com 100 ms uma extração faz no máximo 10 por segundo, e só uma roda por vez em cada conta.
 */
const val POSTER_REQUEST_PAUSE_MS = 100L

/** Espera antes de começar uma extração: o cartão que só passou pela tela numa rolagem rápida sai antes e não gasta pedidos. */
const val POSTER_SETTLE_MS = 400L

/**
 * Tempo máximo de uma extração, a partir de quando ela começa a rodar. Uma leitura que não anda (servidor que pinga bytes,
 * decodificador preso) não pode segurar a fila da conta para sempre: passado o limite o vídeo fica sem capa.
 */
const val POSTER_EXTRACT_TIMEOUT_MS = 30_000L

/** Instante do vídeo usado para a capa, no máximo: depois da abertura, quando o primeiro quadro costuma ser preto. */
private const val POSTER_TIME_US = 1_000_000L

/** Onde a capa de [attachmentId] fica no cache cifrado da conta [accountId]: nunca é buscada nem extraída duas vezes. */
fun posterKey(accountId: String, attachmentId: String): String = "att-poster/$accountId/$attachmentId"

/** De onde vem a capa do cartão de um vídeo. */
enum class PosterSource {
    /** Sem capa: o cartão simples. */
    None,

    /** A miniatura que o servidor gerou (`thumbnail_id`), pelo mesmo caminho das imagens. */
    Server,

    /** Um quadro tirado do próprio vídeo neste aparelho. */
    Extract,
}

/**
 * De onde sai a capa de [attachment]. Escondido (marcado como sensível e ainda não revelado, [revealed] falso) não tem
 * capa nem pedido nenhum. Se o servidor gerou a miniatura, ela vale; senão só dá para extrair com o tamanho do arquivo
 * conhecido, que o `Range` precisa. O que já falhou nesta sessão é decidido por [VideoPosters.poster].
 */
fun posterSource(attachment: AttachmentDto, revealed: Boolean): PosterSource = when {
    !revealed -> PosterSource.None
    attachment.thumbnailId != null -> PosterSource.Server
    attachment.sizeBytes > 0 -> PosterSource.Extract
    else -> PosterSource.None
}

/**
 * O instante do quadro da capa, em microssegundos: 1 s, ou a metade de um vídeo mais curto que 2 s. [durationMs] é a
 * duração do contêiner; sem ela (ou zerada) vale o primeiro quadro.
 */
fun posterTimeUs(durationMs: Long?): Long {
    if (durationMs == null || durationMs <= 0) return 0
    return minOf(POSTER_TIME_US, durationMs * 1_000 / 2)
}

/**
 * O tamanho da capa de um vídeo de [width] x [height] pixels com rotação de [rotationDegrees] (a do contêiner, que 90 e
 * 270 viram de lado): o lado maior em [maxPx] no máximo, na proporção de como o vídeo aparece, e nunca maior que o
 * original (o decodificador amplia quadros pequenos se o pedido for maior). Sem as medidas do contêiner vale o quadrado
 * [maxPx] x [maxPx], em que o quadro cabe na proporção dele.
 */
fun posterSize(width: Int?, height: Int?, rotationDegrees: Int?, maxPx: Int): PixelSize {
    if (width == null || height == null || width <= 0 || height <= 0) return PixelSize(maxPx, maxPx)
    val quarterTurn = rotationDegrees != null && ((rotationDegrees % 360) + 360) % 180 == 90
    return if (quarterTurn) fitWithin(height, width, maxPx) else fitWithin(width, height, maxPx)
}

/**
 * Tira o quadro da capa de um vídeo. A plataforma só decodifica: os bytes vêm do [RangeReader], que já é da conta dona do
 * vídeo, e nada vai ao disco (o cache cifrado é de quem chama).
 */
interface VideoPosterExtractor {
    /**
     * O quadro do vídeo lido por [reader], codificado em JPEG, com o lado maior de no máximo [maxPx] pixels. Null se o
     * aparelho não decodifica o formato ou se a leitura falhou (a conexão, o orçamento do [reader]). Cancelável: ao ser
     * cancelada interrompe a leitura e libera tudo o que abriu antes de lançar a [CancellationException].
     */
    suspend fun extract(reader: RangeReader, maxPx: Int): ByteArray?
}

/**
 * As capas de vídeo de uma conta: decide quando extrair e em que ordem, e é o único dono dessa fila. Cada conta tem a sua
 * (regra 4); [extractor] é nulo onde a plataforma ainda não sabe extrair, e então nunca há capa.
 *
 * Uma extração por vez; quem espera na fila sai dela ao ser cancelado (o cartão saiu da tela). O que falhou uma vez
 * nesta sessão não é tentado de novo sozinho. Com o visualizador de vídeo aberto ([playerOpened]) as extrações esperam:
 * o player e a capa disputariam o mesmo limite de pedidos.
 */
class VideoPosters(
    private val client: PapoClient,
    private val extractor: VideoPosterExtractor?,
    private val settleMs: Long = POSTER_SETTLE_MS,
    private val blockBytes: Int = POSTER_BLOCK_BYTES,
    private val budgetBytes: Long = POSTER_BUDGET_BYTES,
    private val pauseMs: Long = POSTER_REQUEST_PAUSE_MS,
) {
    private val queue = Mutex()
    private val failed = MutableStateFlow<Set<String>>(emptySet())
    private val players = MutableStateFlow(0)

    /** Um visualizador de vídeo abriu: as capas esperam até todos fecharem. */
    fun playerOpened() = players.update { it + 1 }

    fun playerClosed() = players.update { (it - 1).coerceAtLeast(0) }

    /**
     * A capa do anexo [attachmentId], em JPEG, extraída do arquivo em [url] (do servidor da conta), de [sizeBytes] bytes;
     * null se não dá para ter uma. Roda dentro do `fetch` do cache de mídia, que só chama quando a capa não está lá.
     */
    suspend fun poster(attachmentId: String, url: String, sizeBytes: Long): ByteArray? {
        val extractor = extractor ?: return null
        if (attachmentId in failed.value) return null
        delay(settleMs)
        return queue.withLock {
            players.first { it == 0 }
            val reader = RangeReader(sizeBytes, HttpRangeFetch(client, url), blockBytes, budgetBytes, pauseMs)
            val bytes = try {
                extractor.extract(reader, POSTER_MAX_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bytes == null) failed.update { it + attachmentId }
            bytes
        }
    }
}
