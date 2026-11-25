package chat.papo.mobile.chat

import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Um emoji escolhido pelo usuário: do Unicode ou do servidor. */
sealed interface EmojiRef {
    data class Unicode(val char: String) : EmojiRef
    data class Custom(val id: String) : EmojiRef
}

/**
 * Os últimos emojis usados numa conta, mais recente primeiro. Ficam na tabela `meta`
 * do banco cifrado da conta, então não saem do aparelho nem se misturam entre contas.
 */
class RecentEmojis(
    private val meta: MetaQueries,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _items = MutableStateFlow<List<EmojiRef>>(emptyList())
    val items: StateFlow<List<EmojiRef>> = _items.asStateFlow()

    /** Uma gravação por vez, sempre do valor mais novo: duas em paralelo poderiam terminar fora de ordem. */
    private val writeLock = Mutex()

    init {
        scope.launch(io) {
            val stored = decode(meta.get(KEY).executeAsOneOrNull())
            // Se o usuário já escolheu algo antes da leitura terminar, o que ele escolheu vem na frente.
            _items.update { current -> (current + stored).distinct().take(MAX) }
        }
    }

    fun used(emoji: EmojiRef) {
        _items.update { current -> (listOf(emoji) + current.filter { it != emoji }).take(MAX) }
        scope.launch(io) { writeLock.withLock { meta.put(KEY, encode(_items.value)) } }
    }

    internal companion object {
        const val KEY = "emoji.recent"
        const val MAX = 32

        fun encode(items: List<EmojiRef>): String = items.joinToString("\n") {
            when (it) {
                is EmojiRef.Unicode -> "u:${it.char}"
                is EmojiRef.Custom -> "c:${it.id}"
            }
        }

        fun decode(text: String?): List<EmojiRef> = text.orEmpty().lineSequence().mapNotNull { line ->
            val value = line.drop(2)
            when {
                value.isEmpty() || value.length > 64 -> null
                line.startsWith("u:") -> EmojiRef.Unicode(value)
                line.startsWith("c:") -> EmojiRef.Custom(value)
                else -> null
            }
        }.distinct().take(MAX).toList()
    }
}
