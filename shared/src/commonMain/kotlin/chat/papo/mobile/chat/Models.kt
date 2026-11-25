package chat.papo.mobile.chat

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.ExternalAuthorDto
import chat.papo.mobile.data.api.ReactionSummaryDto
import chat.papo.mobile.data.api.UserReactionDto
import kotlin.time.Instant

enum class ChannelType {
    Text, Voice, Category;

    companion object {
        fun from(value: String) = when (value) {
            "voice" -> Voice
            "category" -> Category
            else -> Text
        }
    }
}

data class User(
    val id: String,
    val username: String,
    val nickname: String?,
    val statusMessage: String?,
    val typingPhrase: String?,
    val banned: Boolean,
    val roleColor: String?,
    val avatarVersion: Long,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
}

data class Channel(
    val id: String,
    val name: String,
    val type: ChannelType,
    val position: Int,
    val topic: String?,
    val parentId: String?,
    val lastMessageId: String?,
    val lastMessageText: String?,
    val lastMessageAuthor: String?,
    val lastReadMessage: String?,
    val notification: String?,
) {
    val unread: Boolean get() = type != ChannelType.Category && lastMessageId != null && lastMessageId != lastReadMessage
}

data class Dm(
    val id: String,
    val userId: String,
    val lastMessageId: String?,
    val lastMessageText: String?,
    val lastMessageAt: String?,
    val unreadCount: Int,
)

data class Message(
    val id: String,
    val channelId: String,
    val authorId: String?,
    val content: String,
    val createdAt: Instant?,
    val createdAtIso: String,
    val edited: Boolean,
    val replyTo: String?,
    val externalAuthor: ExternalAuthorDto?,
    val attachments: List<AttachmentDto>,
    val embeds: List<EmbedDto>,
    val reactions: List<ReactionSummaryDto>,
    val userReactions: List<UserReactionDto>,
) {
    fun reactedWith(reaction: ReactionSummaryDto): Boolean =
        userReactions.any { it.emojiId == reaction.emojiId && it.unicode == reaction.unicode }
}

/** Resultado de procurar uma mensagem no histórico de um canal. */
sealed interface Located {
    /** Está guardada; [position] é quantas mensagens mais novas o canal tem antes dela. */
    data class Found(val position: Int) : Located

    /** O histórico foi lido até antes dela e ela não apareceu: foi apagada ou não é deste canal. */
    data object Missing : Located

    /** Fica mais para trás do que o limite de páginas da busca. */
    data object TooOld : Located
}

/** online, offline, away ou busy. */
data class Presence(val status: String, val statusMessage: String?) {
    val online: Boolean get() = status != "offline"
}

/** Categoria com os canais que pertencem a ela; [category] null agrupa os canais soltos. */
data class ChannelGroup(val category: Channel?, val channels: List<Channel>)

/**
 * Monta a árvore de canais. Canais com `parent_id` vão para a categoria deles;
 * os antigos, sem esse campo, ficam na categoria que os antecede na ordem.
 */
fun groupChannels(channels: List<Channel>): List<ChannelGroup> {
    val sorted = channels.sortedWith(compareBy({ it.position }, { it.name }))
    val categories = sorted.filter { it.type == ChannelType.Category }
    val byId = categories.associateBy { it.id }
    val members = LinkedHashMap<String?, MutableList<Channel>>()
    var implicit: String? = null
    for (channel in sorted) {
        if (channel.type == ChannelType.Category) {
            implicit = channel.id
            members.getOrPut(channel.id) { mutableListOf() }
            continue
        }
        val parent = channel.parentId?.takeIf { it in byId } ?: implicit
        members.getOrPut(parent) { mutableListOf() }.add(channel)
    }
    val groups = mutableListOf<ChannelGroup>()
    members[null]?.takeIf { it.isNotEmpty() }?.let { groups += ChannelGroup(null, it) }
    for (category in categories) groups += ChannelGroup(category, members[category.id].orEmpty())
    return groups
}

private val mentionPattern = Regex("""@mention\(<@([0-9a-fA-F-]{36})>\)""")

/** Troca `@mention(<@id>)` pelo nome de exibição. */
fun renderMentions(content: String, nameOf: (String) -> String?): String =
    mentionPattern.replace(content) { match -> "@" + (nameOf(match.groupValues[1]) ?: "usuário") }

/** Ids dos usuários mencionados em [content]. */
fun mentionedIds(content: String): List<String> = mentionPattern.findAll(content).map { it.groupValues[1] }.toList()

/** Troca `@nome` pelo formato canônico do backend, só para os nomes escolhidos no autocompletar. */
fun encodeMentions(text: String, picked: Map<String, String>): String {
    var result = text
    for ((username, id) in picked.entries.sortedByDescending { it.key.length }) {
        result = Regex("@" + Regex.escape(username) + """(?![\w.-])""").replace(result, "@mention(<@$id>)")
    }
    return result
}
