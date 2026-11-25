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
    /** Webhook que enviou a mensagem; nulo nas mensagens de gente. */
    val webhookId: String?,
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
    /** Está guardada; [position] é quantas mensagens mais novas o trecho guardado tem antes dela. */
    data class Found(val position: Int) : Located

    /** O servidor não a tem mais neste canal: foi apagada ou nunca foi dele. */
    data object Missing : Located

    /**
     * A procura para trás parou no limite de páginas sem achá-la nem chegar ao começo do canal:
     * ela pode existir mais para trás. As páginas lidas ficam guardadas, e uma nova procura continua dali.
     */
    data object NotReached : Located
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

/** Bloco de código cercado por três crases, a mesma regra que o Markdown usa para mostrá-lo. */
private val codeFence = Regex("""```[^\n`]*\n?([\s\S]*?)```""")

/**
 * Troca `@nome` pelo formato canônico do backend (`@mention(<@id>)`): os nomes escolhidos no
 * autocompletar ([picked], username para id) e os digitados à mão, quando exatamente um usuário de
 * [users] tem aquele username (sem diferenciar maiúsculas; com dois, fica texto). O `@` tem de poder
 * abrir uma menção (ver [mentionMayStartAt]), então `a@b.com` não muda, e nada muda dentro de código
 * em linha ou em bloco. Um ponto no fim do nome é pontuação da frase: `@ana.` é a menção `@ana`.
 */
fun encodeMentions(text: String, picked: Map<String, String>, users: Collection<User>): String {
    if ('@' !in text) return text
    val byUsername = users.groupBy { it.username.lowercase() }
    fun idOf(name: String): String? = picked[name] ?: byUsername[name.lowercase()]?.singleOrNull()?.id
    val code = codeRanges(text)
    val out = StringBuilder(text.length + 40)
    var nextCode = 0
    var i = 0
    while (i < text.length) {
        val codeRange = code.getOrNull(nextCode)
        if (codeRange != null && i == codeRange.first) {
            out.append(text, codeRange.first, codeRange.last + 1)
            i = codeRange.last + 1
            nextCode++
            continue
        }
        val mention = if (text[i] == '@' && mentionMayStartAt(text, i)) mentionAt(text, i, ::idOf) else null
        if (mention == null) {
            out.append(text[i])
            i++
        } else {
            out.append("@mention(<@").append(mention.id).append(">)")
            i = mention.end
        }
    }
    return out.toString()
}

private class FoundMention(val id: String, val end: Int)

/** O nome que começa depois do `@` em [at], se for de alguém; o fim dele é onde a menção termina. */
private fun mentionAt(text: String, at: Int, idOf: (String) -> String?): FoundMention? {
    var end = at + 1
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] in "_.-")) end++
    val token = text.substring(at + 1, end)
    for (name in listOf(token, token.trimEnd('.'))) {
        val id = if (name.isEmpty()) null else idOf(name)
        if (id != null) return FoundMention(id, at + 1 + name.length)
    }
    return null
}

/** Trechos de [text] que o Markdown mostra como código, na ordem: blocos cercados e código em linha. */
private fun codeRanges(text: String): List<IntRange> {
    val ranges = ArrayList<IntRange>()
    var from = 0
    for (fence in codeFence.findAll(text)) {
        inlineCodeRanges(text, from, fence.range.first, ranges)
        ranges += fence.range
        from = fence.range.last + 1
    }
    inlineCodeRanges(text, from, text.length, ranges)
    return ranges
}

/** Código em linha entre [from] e [to]: duas crases na mesma linha com algo no meio, como o Markdown as lê. */
private fun inlineCodeRanges(text: String, from: Int, to: Int, out: MutableList<IntRange>) {
    var i = from
    while (i < to) {
        if (text[i] != '`') {
            i++
            continue
        }
        val lineEnd = text.indexOf('\n', i).let { if (it in 0 until to) it else to }
        val close = text.indexOf('`', i + 1)
        if (close > i + 1 && close < lineEnd) {
            out += i..close
            i = close + 1
        } else {
            i++
        }
    }
}
