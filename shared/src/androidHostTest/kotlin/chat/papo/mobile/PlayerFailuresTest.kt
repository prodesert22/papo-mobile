package chat.papo.mobile

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.platform.MAX_AUTOMATIC_RETRIES
import chat.papo.mobile.platform.PapoLoadErrorPolicy
import chat.papo.mobile.platform.failureOf
import chat.papo.mobile.platform.httpStatusOf
import chat.papo.mobile.platform.loadFailureOf
import chat.papo.mobile.platform.loadRetryDelayMs
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import sun.misc.Unsafe

/**
 * As regras que traduzem os erros do Media3 para `PlaybackFailure` e para a política de repetição.
 * Só exceções puras: o player e o `Uri` do Android não existem no JVM.
 */
class PlayerFailuresTest {
    // O `Uri` do Android não existe no JVM e o `DataSpec` e o `LoadEventInfo` exigem um: eles são
    // alocados sem passar pelo construtor. Quem os recebe só guarda a referência; nada aqui lê o endereço.
    private inline fun <reified T : Any> allocate(): T {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return unsafe.allocateInstance(T::class.java) as T
    }

    private val dataSpec: DataSpec = allocate()

    private fun status(code: Int, cause: IOException? = null) =
        InvalidResponseCodeException(code, null, cause, emptyMap(), dataSpec, ByteArray(0))

    private fun wrapped(inner: Throwable, levels: Int): Throwable =
        (1..levels).fold(inner) { cause, _ -> RuntimeException("envoltório", cause) }

    // httpStatusOf

    @Test
    fun semErroNaoHaStatus() {
        assertNull(httpStatusOf(null))
    }

    @Test
    fun erroSemRespostaHttpNaoTemStatus() {
        assertNull(httpStatusOf(wrapped(SocketTimeoutException(), 3)))
    }

    @Test
    fun oStatusSaiDoProprioErro() {
        assertEquals(404, httpStatusOf(status(404)))
    }

    @Test
    fun oStatusSaiDeUmaCausaAninhada() {
        assertEquals(502, httpStatusOf(wrapped(status(502), 2)))
    }

    @Test
    fun acimaDoLimiteDeCausasOStatusNaoEhProcurado() {
        assertEquals(503, httpStatusOf(wrapped(status(503), 15)))
        assertNull(httpStatusOf(wrapped(status(503), 16)))
    }

    // failureOf: o erro final entregue pelo player

    @Test
    fun statusHttpDecideOMotivoDoErroFinal() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(PlaybackFailure.SessionEnded, failureOf(status(401), code))
        assertEquals(PlaybackFailure.Forbidden, failureOf(status(403), code))
        assertEquals(PlaybackFailure.NotFound, failureOf(status(404), code))
        assertEquals(PlaybackFailure.RateLimited, failureOf(wrapped(status(429), 1), code))
        assertEquals(PlaybackFailure.Unavailable, failureOf(status(502), code))
    }

    @Test
    fun statusHttpVenceOCodigoDoErro() {
        assertEquals(PlaybackFailure.Unavailable, failureOf(status(502), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test
    fun intervaloInalcancavelViraGone() {
        val outOfRange = PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        assertEquals(PlaybackFailure.Gone, failureOf(status(416), outOfRange))
    }

    @Test
    fun semStatusUmCodigoDeRedeOuGenericoViraNetwork() {
        assertEquals(PlaybackFailure.Network, failureOf(null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(PlaybackFailure.Network, failureOf(SocketTimeoutException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(PlaybackFailure.Network, failureOf(IOException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    @Test
    fun semStatusUmCodigoDeConteudoViraUnsupported() {
        assertEquals(PlaybackFailure.Unsupported, failureOf(null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackFailure.Unsupported, failureOf(null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackFailure.Unsupported, failureOf(null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(PlaybackFailure.Unsupported, failureOf(null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }

    @Test
    fun osLimitesDaFaixaDeConteudoSaoExatos() {
        assertEquals(PlaybackFailure.Network, failureOf(null, 2999))
        assertEquals(PlaybackFailure.Unsupported, failureOf(null, 3000))
        assertEquals(PlaybackFailure.Unsupported, failureOf(null, 5999))
        assertEquals(PlaybackFailure.Network, failureOf(null, 6000))
    }

    // loadFailureOf: a exceção crua do carregador

    @Test
    fun containeIlegivelNaCargaViraUnsupported() {
        assertEquals(PlaybackFailure.Unsupported, loadFailureOf(ParserException.createForMalformedContainer("lixo", null)))
        assertEquals(PlaybackFailure.Unsupported, loadFailureOf(ParserException.createForUnsupportedContainerFeature("recurso")))
    }

    @Test
    fun erroDeContainerEscondidoEmUmaCausaViraUnsupported() {
        val error = IOException("falha", ParserException.createForMalformedContainer("lixo", null))
        assertEquals(PlaybackFailure.Unsupported, loadFailureOf(error))
    }

    @Test
    fun excecaoInesperadaDoCarregadorViraUnsupported() {
        assertEquals(PlaybackFailure.Unsupported, loadFailureOf(Loader.UnexpectedLoaderException(IllegalStateException())))
    }

    @Test
    fun statusHttpNaCargaDecideOMotivo() {
        assertEquals(PlaybackFailure.SessionEnded, loadFailureOf(status(401)))
        assertEquals(PlaybackFailure.Forbidden, loadFailureOf(status(403)))
        assertEquals(PlaybackFailure.RateLimited, loadFailureOf(status(429)))
        assertEquals(PlaybackFailure.Unavailable, loadFailureOf(status(502)))
        assertEquals(PlaybackFailure.Gone, loadFailureOf(status(416)))
    }

    @Test
    fun falhaDeLeituraSemStatusNaCargaViraNetwork() {
        assertEquals(PlaybackFailure.Network, loadFailureOf(IOException("conexão caiu")))
        val read = HttpDataSourceException(SocketTimeoutException(), dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, HttpDataSourceException.TYPE_READ)
        assertEquals(PlaybackFailure.Network, loadFailureOf(read))
    }

    // loadRetryDelayMs: a política de repetição

    @Test
    fun aPoliticaAdmiteDuasRepeticoes() {
        assertEquals(2, MAX_AUTOMATIC_RETRIES)
    }

    @Test
    fun redeRepeteDuasVezesComEsperaCrescente() {
        val error = IOException("conexão caiu")
        assertEquals(1_000L, loadRetryDelayMs(error, errorCount = 1))
        assertEquals(3_000L, loadRetryDelayMs(error, errorCount = 2))
        assertEquals(C.TIME_UNSET, loadRetryDelayMs(error, errorCount = 3))
    }

    @Test
    fun erroDoServidorRepeteDuasVezes() {
        assertEquals(1_000L, loadRetryDelayMs(status(502), errorCount = 1))
        assertEquals(3_000L, loadRetryDelayMs(status(502), errorCount = 2))
        assertEquals(C.TIME_UNSET, loadRetryDelayMs(status(502), errorCount = 3))
    }

    @Test
    fun erroDoClienteNuncaRepeteInclusiveOLimiteDePedidos() {
        for (code in listOf(400, 401, 403, 404, 410, 416, 429)) {
            assertEquals(C.TIME_UNSET, loadRetryDelayMs(status(code), errorCount = 1), "status $code")
        }
    }

    @Test
    fun conteudoQueNaoSeLeNaoRepete() {
        assertEquals(C.TIME_UNSET, loadRetryDelayMs(ParserException.createForMalformedContainer("lixo", null), errorCount = 1))
        assertEquals(C.TIME_UNSET, loadRetryDelayMs(Loader.UnexpectedLoaderException(IllegalStateException()), errorCount = 1))
    }

    @Test
    fun contagemZeroEhTratadaComoPrimeiraFalha() {
        assertEquals(1_000L, loadRetryDelayMs(IOException(), errorCount = 0))
    }

    // PapoLoadErrorPolicy: a cola entre o Media3 e as regras acima

    private fun info(error: IOException, errorCount: Int) =
        LoadErrorInfo(allocate<LoadEventInfo>(), MediaLoadData(C.DATA_TYPE_MEDIA), error, errorCount)

    @Test
    fun aPoliticaDevolveAEsperaDaFalhaEDesisteDaFatal() {
        assertEquals(1_000L, PapoLoadErrorPolicy.getRetryDelayMsFor(info(status(503), errorCount = 1)))
        assertEquals(C.TIME_UNSET, PapoLoadErrorPolicy.getRetryDelayMsFor(info(status(429), errorCount = 1)))
    }

    @Test
    fun aPoliticaSoDeixaOErroSubirDepoisDasRepeticoes() {
        assertEquals(MAX_AUTOMATIC_RETRIES, PapoLoadErrorPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test
    fun aPoliticaNaoTrocaDeLocalNemDeFaixa() {
        val options = FallbackOptions(1, 0, 1, 0)
        assertNull(PapoLoadErrorPolicy.getFallbackSelectionFor(options, info(status(503), errorCount = 1)))
    }
}
