package chat.papo.mobile.platform

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import chat.papo.mobile.media.PlaybackErrorKind
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.media.classifyPlayback
import chat.papo.mobile.media.retryDelay
import java.io.IOException

// Traduz os erros do Media3 para o contrato comum (`PlaybackFailure`, `retryDelay`). Fica longe do
// composable para que as regras possam ser testadas no JVM, sem carregar o player nem a interface.
// Nenhuma função aqui lê cabeçalhos, URL ou corpo de resposta: só o código de status e o tipo do erro
// (regra 5).

/** Quantas causas encadeadas olhar: o bastante para qualquer envoltório do Media3, sem risco de laço. */
private const val MAX_CAUSE_DEPTH = 16

/** Faixa dos códigos de erro do player que são de conteúdo: 3xxx contêiner, 4xxx decodificação, 5xxx áudio. */
private val CONTENT_ERROR_CODES = 3000..5999

/** Status HTTP do primeiro `InvalidResponseCodeException` na cadeia de causas de [error], ou null. */
internal fun httpStatusOf(error: Throwable?): Int? =
    generateSequence(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

/**
 * Motivo do erro final que o player entregou ([PlaybackException.getCause] e
 * [PlaybackException.errorCode]). Havendo status HTTP de erro na cadeia, ele decide; senão, o código:
 * contêiner, codec ou áudio que o aparelho não aceita é [PlaybackFailure.Unsupported]; o resto é rede.
 */
internal fun failureOf(cause: Throwable?, errorCode: Int): PlaybackFailure {
    val kind = if (errorCode in CONTENT_ERROR_CODES) PlaybackErrorKind.Decoding else PlaybackErrorKind.Io
    return classifyPlayback(httpStatusOf(cause), kind)
}

/**
 * Motivo de uma falha de carga, a exceção crua do carregador (antes de virar [PlaybackException]).
 * Contêiner que o extrator não lê ou corrompido é de conteúdo; qualquer outro `IOException` sem
 * status é de rede.
 */
internal fun loadFailureOf(error: IOException): PlaybackFailure {
    val content = generateSequence<Throwable>(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .any { it is ParserException || it is Loader.UnexpectedLoaderException }
    return classifyPlayback(httpStatusOf(error), if (content) PlaybackErrorKind.Decoding else PlaybackErrorKind.Io)
}

/** Quantas repetições automáticas a política admite: a primeira tentativa sem espera marca o fim. */
internal val MAX_AUTOMATIC_RETRIES: Int =
    generateSequence(0) { it + 1 }.first { retryDelay(PlaybackFailure.Network, it) == null }

/**
 * Espera em milissegundos antes de repetir a carga que falhou, ou [C.TIME_UNSET] para desistir.
 * [errorCount] conta as falhas seguidas da mesma carga, a atual inclusive (1 na primeira).
 */
@OptIn(UnstableApi::class)
internal fun loadRetryDelayMs(error: IOException, errorCount: Int): Long =
    retryDelay(loadFailureOf(error), (errorCount - 1).coerceAtLeast(0)) ?: C.TIME_UNSET

/**
 * A política de repetição dos players: só rede e 5xx repetem, no máximo duas vezes (`retryDelay`).
 * Qualquer 4xx, em especial o 429, é definitivo, e não há troca de local nem de faixa (sem
 * alternativas). O padrão do Media3 repetiria, por exemplo, um 429 até três vezes, sem saber do
 * limite de pedidos por endereço do servidor.
 */
@OptIn(UnstableApi::class)
internal object PapoLoadErrorPolicy : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(fallbackOptions: FallbackOptions, loadErrorInfo: LoadErrorInfo): FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long =
        loadRetryDelayMs(loadErrorInfo.exception, loadErrorInfo.errorCount)

    // O erro só sobe ao player depois de passar por todas as repetições da política.
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_AUTOMATIC_RETRIES
}
