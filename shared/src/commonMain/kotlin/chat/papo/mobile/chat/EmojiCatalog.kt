package chat.papo.mobile.chat

import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.EmojiDto
import chat.papo.mobile.data.api.EmojisPage
import chat.papo.mobile.data.db.account.MetaQueries
import chat.papo.mobile.media.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Emoji próprio do servidor. A imagem não fica aqui: está no cache de mídia cifrado, em [EmojiCatalog.mediaKey]. */
@Serializable
data class ServerEmoji(val id: String, val name: String, val format: String? = null)

/** O que se sabe dos emojis do servidor até agora. */
class ServerEmojis(
    /** Em ordem alfabética. */
    val list: List<ServerEmoji> = emptyList(),
    /** Há uma leitura do servidor em andamento. */
    val loading: Boolean = false,
    /**
     * [list] é uma foto inteira: veio do cache da conta ou de uma leitura que terminou.
     * Falso só na primeira leitura de todas, enquanto a lista ainda cresce.
     */
    val complete: Boolean = false,
    val failed: Boolean = false,
) {
    val byId: Map<String, ServerEmoji> = list.associateBy { it.id }

    /** Nome -> id. O servidor recusa nome repetido (409). */
    val idByName: Map<String, String> = list.associate { it.name to it.id }

    internal fun copy(
        list: List<ServerEmoji> = this.list,
        loading: Boolean = this.loading,
        complete: Boolean = this.complete,
        failed: Boolean = this.failed,
    ) = ServerEmojis(list, loading, complete, failed)
}

/**
 * Emojis próprios do servidor de uma conta. O servidor lista 25 por página e aceita até
 * 500. A paginação é por cursor (cada página parte do último item da anterior), então as
 * páginas não podem ser pedidas juntas; o que corre em paralelo é o trabalho com as
 * imagens de uma página (decodificar e cifrar no cache de mídia) enquanto a seguinte já
 * é pedida. O backend limita a taxa por IP para todas as rotas juntas (padrão 10/s com
 * rajada de 20), por isso os pedidos começam a pelo menos [pageInterval] um do outro e um
 * 429 espera antes de repetir a mesma página (é um GET: repetir não muda nada no servidor).
 * Tudo isso roda fora da thread da tela: o JSON de uma página com as imagens em base64
 * passa de 1 MB. A memória guarda só nome e id.
 *
 * A última lista completa fica na tabela `meta` do banco cifrado da conta: ao abrir o
 * app ela aparece na hora e a releitura do servidor só a troca quando termina, de uma
 * vez. Só a primeira leitura de todas mostra a lista crescendo.
 * Não há evento de WebSocket para emoji novo: a lista é relida de tempos em tempos.
 */
class EmojiCatalog(
    private val api: ChatApi,
    private val media: MediaCache,
    private val meta: MetaQueries,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Onde rodam a leitura do JSON e a decodificação das imagens: threads de prioridade baixa. */
    private val background: CoroutineDispatcher = io,
    /** Intervalo mínimo entre o início de um pedido de página e o do seguinte. */
    private val pageInterval: Duration = 300.milliseconds,
    /** Espera depois de um 429, dobrada a cada nova recusa da mesma página. */
    private val rateLimitBackoff: Duration = 2.seconds,
) {
    private val _state = MutableStateFlow(ServerEmojis())
    val state: StateFlow<ServerEmojis> = _state.asStateFlow()

    /** Quem chama `start()` pode estar em threads diferentes (a tela e o `imageBytes` do cache): só uma carga nasce. */
    private val starting = Mutex()
    private var job: Job? = null
    private var finishedAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Começa a carregar, a menos que já esteja carregando ou a lista seja recente
     * (uma carga que falhou espera 30 s antes de poder repetir). Com [force] ignora a
     * idade. Nada aqui se repete sozinho: o servidor pode estar limitando a taxa.
     */
    fun start(force: Boolean = false) {
        if (!starting.tryLock()) return // outra chamada está decidindo agora
        try {
            if (job?.isActive == true) return
            val done = finishedAt
            val wait = if (_state.value.failed) RETRY_AFTER else REFRESH_AFTER
            if (!force && done != null && done.elapsedNow() < wait) return
            // Uma lista inteira que já existe continua valendo até a nova chegar.
            _state.update { it.copy(loading = true, failed = false) }
            // Rede, JSON e imagens fora da thread da tela e com prioridade baixa; o StateFlow aceita
            // atualização de qualquer thread.
            job = scope.launch(background) { load() }
        } finally {
            starting.unlock()
        }
    }

    /** Bytes da imagem do emoji [id]; espera a carga chegar nele. Null se o servidor não o tem. */
    suspend fun imageBytes(id: String): ByteArray? {
        start()
        _state.first { id in it.byId || !it.loading }
        return media.peek(mediaKey(id))
    }

    private suspend fun load() {
        if (_state.value.list.isEmpty()) restoreCached()
        // Só entra aqui quem já teve a imagem guardada: quem vê a lista acha a imagem no cache.
        val ready = LinkedHashMap<String, ServerEmoji>()
        val readyLock = Mutex()
        // Sem nada para mostrar, a lista aparece aos poucos (no máximo uma vez por segundo);
        // com uma lista inteira, só troca no fim.
        val progressive = _state.value.list.isEmpty()
        var published: TimeSource.Monotonic.ValueTimeMark? = null
        var failure: Exception? = null
        try {
            coroutineScope {
                val imageWork = Semaphore(PRIME_PARALLELISM)
                var since: String? = null
                var lastId: String? = null
                var lastRequest: TimeSource.Monotonic.ValueTimeMark? = null
                for (page in 0 until MAX_PAGES) {
                    lastRequest?.let { delay(pageInterval - it.elapsedNow()) }
                    lastRequest = TimeSource.Monotonic.markNow()
                    val response = try {
                        fetchPage(since, lastId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // As imagens das páginas que já chegaram terminam de ser guardadas.
                        failure = e
                        break
                    }
                    for (dto in response.emojis) {
                        launch {
                            imageWork.withPermit { prime(dto) }
                            readyLock.withLock { ready[dto.id] = ServerEmoji(dto.id, dto.name, dto.format) }
                        }
                    }
                    if (progressive && published.let { it == null || it.elapsedNow() >= PUBLISH_EVERY }) {
                        publish(readyLock.withLock { LinkedHashMap(ready) }, complete = false)
                        published = TimeSource.Monotonic.markNow()
                    }
                    val last = response.emojis.lastOrNull()
                    if (!response.hasMore || last?.createdAt == null) break
                    since = last.createdAt
                    lastId = last.id
                }
            } // espera todas as imagens terminarem
            if (failure == null) {
                publish(ready, complete = true)
                saveCached(ready.values.toList())
            } else {
                // Uma lista inteira (do cache) continua valendo; na primeira leitura fica o que chegou.
                val partial = ready.values.sortedBy { it.name.lowercase() }
                _state.update {
                    if (progressive) it.copy(list = partial, loading = false, failed = true) else it.copy(loading = false, failed = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, failed = true) }
        } finally {
            finishedAt = TimeSource.Monotonic.markNow()
        }
    }

    /** Uma página; um 429 espera e tenta a mesma página de novo, algumas vezes. */
    private suspend fun fetchPage(since: String?, lastId: String?): EmojisPage {
        var wait = rateLimitBackoff
        repeat(RATE_LIMIT_RETRIES) {
            try {
                return api.emojis(since, lastId)
            } catch (e: ApiException) {
                if (e.status != 429) throw e
                delay(wait)
                wait *= 2
            }
        }
        return api.emojis(since, lastId)
    }

    private fun publish(found: Map<String, ServerEmoji>, complete: Boolean) {
        val sorted = found.values.sortedBy { it.name.lowercase() }
        _state.update { it.copy(list = sorted, loading = !complete, complete = complete, failed = false) }
    }

    /** A última lista completa desta conta, se houver. Cache ilegível é ignorado e relido do servidor. */
    private suspend fun restoreCached() {
        val cached = withContext(io) {
            try {
                meta.get(CACHE_KEY).executeAsOneOrNull()?.let { json.decodeFromString(listSerializer, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return
        if (cached.isEmpty()) return
        _state.update { current ->
            if (current.list.isNotEmpty()) current else current.copy(list = cached.sortedBy { it.name.lowercase() }, complete = true)
        }
    }

    private suspend fun saveCached(list: List<ServerEmoji>) = withContext(io) {
        try {
            meta.put(CACHE_KEY, json.encodeToString(listSerializer, list))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sem o cache a próxima abertura só volta a ler do servidor.
        }
    }

    /**
     * Põe a imagem no cache cifrado, se ainda não estiver lá. Imagem ilegível ou grande
     * demais é ignorada: o emoji existe, só fica sem imagem.
     */
    private suspend fun prime(dto: EmojiDto) {
        val blob = dto.imageBlob ?: return
        val key = mediaKey(dto.id)
        try {
            if (blob.length > MAX_BLOB_CHARS || media.has(key)) return
            val bytes = Base64.decode(blob) // já roda em [background]
            media.put(key, bytes, keepInMemory = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // base64 inválido ou falha ao gravar
        }
    }

    companion object {
        /** Chave do cache de mídia com a imagem do emoji [id]. */
        fun mediaKey(id: String) = "emoji/$id"

        /** Chave, na tabela `meta` da conta, da última lista completa. */
        internal const val CACHE_KEY = "emoji.server"

        /**
         * As chaves de mídia dos emojis da última lista completa guardada em [meta]; vazio se não há lista ou ela não
         * se lê. Bloqueante: só dentro de um dispatcher de E/S.
         */
        internal fun cachedMediaKeys(meta: MetaQueries): List<String> {
            val stored = meta.get(CACHE_KEY).executeAsOneOrNull() ?: return emptyList()
            val list = try {
                json.decodeFromString(listSerializer, stored)
            } catch (e: SerializationException) {
                return emptyList()
            }
            return list.map { mediaKey(it.id) }
        }

        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(ServerEmoji.serializer())

        /** O servidor aceita 500 emojis em páginas de 25; o resto é folga contra servidor que nunca diz `has_more = false`. */
        private const val MAX_PAGES = 24

        /**
         * Imagens decodificadas e cifradas ao mesmo tempo. Mais que isso só disputa CPU com a
         * tela em aparelho de poucos núcleos (medido: 4 em paralelo num emulador de 4 núcleos
         * derrubava quadros).
         */
        private const val PRIME_PARALLELISM = 2

        /** Quantas vezes uma página recusada com 429 é pedida de novo. */
        private const val RATE_LIMIT_RETRIES = 3

        /** Na primeira leitura, a lista parcial aparece no máximo uma vez a cada intervalo. */
        private val PUBLISH_EVERY = 1.seconds

        /** O servidor limita cada emoji a 256 KB; em base64 isso passa de 340 mil caracteres. */
        private const val MAX_BLOB_CHARS = 1_400_000

        private val REFRESH_AFTER = 10.minutes
        private val RETRY_AFTER = 30.seconds
    }
}
