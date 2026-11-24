package chat.papo.mobile.chat

import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.EmojiDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/** Emojis próprios do servidor, carregados uma vez por sessão. */
class EmojiCatalog(private val api: ChatApi) {
    private val mutex = Mutex()
    private var loaded: Map<String, EmojiDto>? = null

    suspend fun all(): Map<String, EmojiDto> = mutex.withLock {
        loaded?.let { return it }
        val result = LinkedHashMap<String, EmojiDto>()
        var since: String? = null
        var lastId: String? = null
        for (page in 0 until MAX_PAGES) {
            val response = api.emojis(since, lastId)
            response.emojis.forEach { result[it.id] = it }
            val last = response.emojis.lastOrNull()
            if (!response.hasMore || last?.createdAt == null) break
            since = last.createdAt
            lastId = last.id
        }
        loaded = result
        result
    }

    suspend fun imageBytes(emojiId: String): ByteArray? =
        all()[emojiId]?.imageBlob?.let { runCatching { Base64.decode(it) }.getOrNull() }

    private companion object {
        const val MAX_PAGES = 8 // 25 por página
    }
}
