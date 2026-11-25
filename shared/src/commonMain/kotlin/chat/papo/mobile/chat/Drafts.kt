package chat.papo.mobile.chat

import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** O que estava sendo escrito numa conversa: o texto e o id da mensagem que ele responde. Anexos não entram. */
data class Draft(val text: String, val replyTo: String? = null) {
    /** Sem texto (ou só espaços) e sem resposta: não há o que guardar. */
    val isEmpty: Boolean get() = text.isBlank() && replyTo == null
}

/**
 * Rascunhos por conversa de uma conta. Ficam na tabela `meta` do banco cifrado da conta, uma chave
 * `draft:<canal>` por conversa, então não saem do aparelho nem se misturam entre contas.
 *
 * A gravação espera [debounce] sem novas mudanças, para não ir ao banco a cada letra; [flush] grava já o
 * que está esperando (tela saindo de cena, app indo para segundo plano). Quem grava é o escopo da
 * sessão, então sair da conversa não perde nada. Use de uma thread só, como o resto do [ChatStore].
 */
class Drafts(
    private val meta: MetaQueries,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val debounce: Duration = DEBOUNCE,
) {
    /** O valor mais novo de cada conversa que ainda não chegou ao banco; null quer dizer "apagar". */
    private val pending = MutableStateFlow<Map<String, Draft?>>(emptyMap())

    /** Uma gravação por vez, sempre do valor mais novo: duas em paralelo poderiam terminar fora de ordem. */
    private val writeLock = Mutex()
    private var timer: Job? = null

    /** O rascunho guardado da conversa. Null se não há um ou se o valor guardado está estragado. */
    suspend fun load(channelId: String): Draft? {
        // O que ainda espera para ser gravado é mais novo que o banco.
        val waiting = pending.value
        if (channelId in waiting) return waiting[channelId]
        return decode(withContext(io) { meta.get(key(channelId)).executeAsOneOrNull() })
    }

    /** Guarda o rascunho da conversa depois do [debounce]. Vazio (ou null) apaga o que estava guardado. */
    fun save(channelId: String, draft: Draft?) {
        pending.update { it + (channelId to draft?.takeUnless { value -> value.isEmpty }) }
        timer?.cancel()
        timer = scope.launch {
            delay(debounce)
            writePending()
        }
    }

    /** Grava já tudo o que está esperando. */
    fun flush() {
        scope.launch { writePending() }
    }

    private suspend fun writePending() = writeLock.withLock {
        for ((channelId, draft) in pending.value) {
            try {
                withContext(io) {
                    if (draft == null) meta.remove(key(channelId)) else meta.put(key(channelId), encode(draft))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // O rascunho é um conforto: se o banco falhou, ele segue esperando e vai na próxima gravação.
                continue
            }
            // Se mudou enquanto gravava, o valor novo continua esperando a vez dele.
            pending.update { if (channelId in it && it[channelId] == draft) it - channelId else it }
        }
    }

    internal companion object {
        val DEBOUNCE = 400.milliseconds

        /** Mais que isso não é rascunho, é outra coisa: o que passa é cortado ao gravar. */
        const val MAX_TEXT = 20_000

        private const val PREFIX = "draft:"
        private const val MAX_ID = 64

        /** Estrito de propósito: só este código escreve o valor, então o que foge do formato é lixo, não variação. */
        private val json = Json { ignoreUnknownKeys = true }

        fun key(channelId: String) = PREFIX + channelId

        fun encode(draft: Draft): String =
            json.encodeToString(Stored.serializer(), Stored(draft.text.take(MAX_TEXT), draft.replyTo))

        /** O rascunho guardado em [value], ou null se não há valor, se ele não é um rascunho ou se está vazio. */
        fun decode(value: String?): Draft? {
            if (value == null) return null
            val stored = try {
                json.decodeFromString(Stored.serializer(), value)
            } catch (_: IllegalArgumentException) {
                return null // valor estragado (ou de outra versão): ignora e começa sem rascunho
            }
            if (stored.text.length > MAX_TEXT) return null
            val replyTo = stored.replyTo?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
            return Draft(stored.text, replyTo).takeUnless { it.isEmpty }
        }
    }

    @Serializable
    private class Stored(val text: String, val replyTo: String? = null)
}
