package chat.papo.mobile.chat

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.PapoJson
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.media.messageMediaKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * O que a conta guarda no aparelho de cada conversa: só as [MESSAGES_PER_CONVERSATION] mensagens mais novas das
 * [MAX_CONVERSATIONS] conversas abertas por último (canais e conversas diretas contados juntos), com a mídia delas. De
 * qualquer outra conversa não sobra mensagem nenhuma; o que falta vem do servidor quando a conversa abre.
 *
 * As mais recentes ficam na tabela `meta` da conta (cifrada), então sobrevivem a reiniciar o app. "Aberta" é outra
 * coisa: uma conversa com tela viva ([markOpen] até [markClosed]) não é podada enquanto estiver aberta, mesmo que já
 * não esteja entre as mais recentes. Pode haver várias telas vivas, e mais de uma da mesma conversa, por isso as
 * abertas são contadas, e não só a última.
 *
 * A poda ([prune]) mexe só nas linhas de `message` e na marca de trecho antigo: as conversas (prévia, não lidas,
 * ponto de leitura), os rascunhos, as fixadas e as notificações não são tocados. Depois dela, a mídia de mensagem que
 * ficou sem mensagem é apagada do cache ([MediaCache.sweepMessageMedia]). As podas rodam uma de cada vez.
 */
class MessageRetention(
    db: AccountDb,
    private val io: CoroutineDispatcher,
    /** O cache de mídia da conta; sem ele só as linhas são podadas. */
    private val media: MediaCache? = null,
) {
    private val q = db.chatQueries
    private val meta = db.metaQueries

    /** As conversas abertas por último, a mais recente primeiro; nulo até ser lida do banco. */
    private val recent = MutableStateFlow<List<String>?>(null)

    /** Quantas telas vivas cada conversa tem. */
    private val openCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val recentLock = Mutex()
    private val pruneLock = Mutex()

    /** Uma tela de [channelId] passou a existir. */
    fun markOpen(channelId: String) = openCounts.update { it + (channelId to ((it[channelId] ?: 0) + 1)) }

    /** A tela de [channelId] que estava aberta deixou de existir. */
    fun markClosed(channelId: String) = openCounts.update { counts ->
        val remaining = (counts[channelId] ?: return@update counts) - 1
        if (remaining > 0) counts + (channelId to remaining) else counts - channelId
    }

    /** As conversas abertas por último, a mais recente primeiro; na primeira vez lê do banco. */
    suspend fun recentConversations(): List<String> {
        recent.value?.let { return it }
        return recentLock.withLock { recent.value ?: loadRecent() }
    }

    /** Só com [recentLock]. */
    private suspend fun loadRecent(): List<String> = withContext(io) {
        val stored = meta.get(RECENT_KEY).executeAsOneOrNull()
        stored?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.distinct()?.take(MAX_CONVERSATIONS).orEmpty()
    }.also { recent.value = it }

    /** [channelId] foi aberta agora: passa a ser a mais recente, e a quarta sai da lista. */
    suspend fun noteOpened(channelId: String) = recentLock.withLock {
        val current = recent.value ?: loadRecent()
        val updated = (listOf(channelId) + current.filter { it != channelId }).take(MAX_CONVERSATIONS)
        if (updated == current) return@withLock
        recent.value = updated
        withContext(io) { meta.put(RECENT_KEY, updated.joinToString(SEPARATOR)) }
    }

    /**
     * [channelId] guarda mensagens novas? Sim se está entre as mais recentes ou tem tela aberta. Só vale depois de
     * [recentConversations] (que lê as recentes do banco); pode ser chamado de dentro da transação que grava a mensagem,
     * para a resposta ser a do momento da gravação.
     */
    fun keeps(channelId: String): Boolean = channelId in openCounts.value || channelId in recent.value.orEmpty()

    /**
     * Logo depois de gravar uma mensagem de [channelId], que está entre as mais recentes mas sem tela aberta: corta o
     * que passa do limite. Devolve true se cortou. Só dentro de [io], na mesma transação da gravação.
     */
    fun trimIfClosed(channelId: String): Boolean {
        if (channelId in openCounts.value) return false
        return trim(channelId)
    }

    /**
     * Roda uma vez por sessão, na abertura da conta: arruma a mídia guardada pelas versões antigas ([migrateMedia]) e
     * poda, o que cobre uma atualização do app e um processo que morreu antes de podar. Devolve as conversas que
     * perderam mensagens.
     */
    suspend fun startup(): Set<String> = pruneLock.withLock {
        migrateMedia()
        pruneLocked()
    }

    /** Poda o que está guardado e varre a mídia sobrando. Devolve as conversas que perderam mensagens. */
    suspend fun prune(): Set<String> = pruneLock.withLock { pruneLocked() }

    private suspend fun pruneLocked(): Set<String> {
        recentConversations()
        // O que vale (as recentes e as abertas) é lido dentro da transação: uma conversa aberta depois da leitura só
        // grava depois dela, e o que gravar já fica fora desta poda.
        val touched = withContext(io) { q.transactionWithResult { pruneRows() } }
        sweepMedia()
        return touched
    }

    /** Só dentro de [io] e de uma transação. */
    private fun pruneRows(): Set<String> {
        val recentNow = recent.value.orEmpty().toSet()
        val openNow = openCounts.value.keys
        val channels = HashSet(q.selectMessageChannels().executeAsList())
        meta.keysWithPrefix(DETACHED_KEY).executeAsList().mapTo(channels) { it.removePrefix(DETACHED_KEY) }
        val touched = HashSet<String>()
        for (channel in channels) {
            if (channel in openNow) continue
            // Um trecho antigo não tem as mensagens mais novas: de conversa fechada ele não vale nada, e a próxima
            // abertura carrega o fim do histórico.
            val detached = meta.get(DETACHED_KEY + channel).executeAsOneOrNull() != null
            if (channel !in recentNow || detached) {
                q.deleteChannelMessages(channel)
                meta.remove(DETACHED_KEY + channel)
                touched += channel
            } else if (trim(channel)) {
                touched += channel
            }
        }
        return touched
    }

    /** Só dentro de [io]. Mantém as [MESSAGES_PER_CONVERSATION] mais novas de [channelId]; true se apagou alguma. */
    private fun trim(channelId: String): Boolean {
        if (q.countChannelMessages(channelId).executeAsOne() <= MESSAGES_PER_CONVERSATION) return false
        q.trimChannelMessages(channelId, MESSAGES_PER_CONVERSATION)
        return true
    }

    private suspend fun sweepMedia() {
        val cache = media ?: return
        val keep = withContext(io) {
            val keys = HashSet<String>()
            for (row in q.selectMessageMedia().executeAsList()) {
                keys += messageMediaKeys(decodeList(AttachmentDto.serializer(), row.attachments), decodeList(EmbedDto.serializer(), row.embeds))
            }
            keys
        }
        cache.sweepMessageMedia(keep)
    }

    /**
     * As versões anteriores guardavam toda a mídia numa pasta só, e como os nomes dos arquivos são HMAC não há como
     * separar nela o que era de mensagem. Uma vez por conta (a marca fica na tabela `meta`), apaga dessa pasta tudo o
     * que não se sabe de quem é. O que se sabe se mantém, para não custar pedidos ao servidor (regra 15): o avatar
     * atual de cada usuário que a conta conhece e a imagem de cada emoji da última lista. O resto volta quando a
     * interface o pedir: banners e avatares de webhook com um pedido cada, que de webhook já se faz uma vez por sessão.
     * Se isto for cancelado no meio, a marca não é gravada e a migração roda de novo.
     */
    private suspend fun migrateMedia() {
        val cache = media ?: return
        if (withContext(io) { meta.get(MEDIA_LAYOUT_KEY).executeAsOneOrNull() } == MEDIA_LAYOUT) return
        val keep = withContext(io) {
            val names = HashSet<String>()
            q.selectUsers().executeAsList().mapTo(names) { avatarMediaName(it.id, it.avatar_version) }
            names += EmojiCatalog.cachedMediaKeys(meta)
            names
        }
        cache.sweepOutsideMessageArea(keep)
        withContext(io) { meta.put(MEDIA_LAYOUT_KEY, MEDIA_LAYOUT) }
    }

    private fun <T> decodeList(serializer: KSerializer<T>, text: String): List<T> = try {
        PapoJson.decodeFromString(ListSerializer(serializer), text)
    } catch (e: SerializationException) {
        emptyList()
    }

    companion object {
        /** Quantas conversas, as abertas por último, guardam mensagens no aparelho. */
        const val MAX_CONVERSATIONS = 3

        /** Quantas mensagens, as mais novas, cada uma dessas conversas guarda. */
        const val MESSAGES_PER_CONVERSATION = 120L

        /** Prefixo, na tabela `meta`, da marca de canal cujo trecho guardado não chega à mensagem mais nova. */
        internal const val DETACHED_KEY = "detached:"

        /** Chave, na tabela `meta`, das conversas abertas por último. */
        internal const val RECENT_KEY = "retention.recent"

        /** Chave, na tabela `meta`, de a mídia da pasta única das versões antigas já ter sido migrada. */
        internal const val MEDIA_LAYOUT_KEY = "media.layout"

        /** Valor da marca: a versão do formato da pasta de mídia (2 = mídia de mensagem em subpasta própria). */
        private const val MEDIA_LAYOUT = "2"

        private const val SEPARATOR = ","
    }
}
