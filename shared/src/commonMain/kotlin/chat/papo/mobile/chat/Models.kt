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
    /** Quando a última mensagem foi enviada, em ISO 8601; é a atividade mais recente do canal. */
    val lastMessageAt: String?,
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

/**
 * O canal de texto mais ao topo da lista de canais: o primeiro que [groupChannels] mostra, com os canais soltos antes
 * das categorias. Quem chama passa os canais que a lista mostra ([visibleChannels]), então o que a conta não pode ler
 * não conta; canal de voz, categoria e conversa direta também não.
 */
fun topTextChannel(channels: List<Channel>): Channel? =
    groupChannels(channels).firstNotNullOfOrNull { group -> group.channels.firstOrNull { it.type == ChannelType.Text } }

private val mentionPattern = Regex("""@mention\(<@([0-9a-fA-F-]{36})>\)""")

/** Troca `@mention(<@id>)` pelo nome de exibição. */
fun renderMentions(content: String, nameOf: (String) -> String?): String =
    mentionPattern.replace(content) { match -> "@" + (nameOf(match.groupValues[1]) ?: "usuário") }

/** Ids dos usuários mencionados em [content]. */
fun mentionedIds(content: String): List<String> = mentionPattern.findAll(content).map { it.groupValues[1] }.toList()

/** Nome da menção a todos: o servidor casa o `@everyone` literal no texto, sem o formato `@mention(<@id>)`. */
const val EVERYONE_NAME = "everyone"

/** O `@everyone` como se escreve. */
const val EVERYONE_MENTION = "@$EVERYONE_NAME"

/**
 * Há um `@everyone` em [at] de [text]? Sem diferenciar maiúsculas e sem letra, dígito ou `_` ASCII logo
 * depois, como o `\b` do servidor e do web: `@everyones` e `@everyone_x` não valem, `@everyoneé` vale. O que
 * vem antes não importa aqui: quem chama decide (o Markdown aceita em qualquer lugar, o destaque pede início ou espaço).
 */
internal fun everyoneAt(text: String, at: Int): Boolean {
    val end = at + EVERYONE_MENTION.length
    if (end > text.length || !text.regionMatches(at, EVERYONE_MENTION, 0, EVERYONE_MENTION.length, ignoreCase = true)) {
        return false
    }
    if (end == text.length) return true
    val next = text[end]
    return !(next in 'a'..'z' || next in 'A'..'Z' || next in '0'..'9' || next == '_')
}

/** Bloco de código cercado por três crases, a mesma regra que o Markdown usa para mostrá-lo. */
private val codeFence = Regex("""```[^\n`]*\n?([\s\S]*?)```""")

/**
 * Troca `@nome` pelo formato canônico do backend (`@mention(<@id>)`): os nomes escolhidos no
 * autocompletar ([picked], username para id) e os digitados à mão, quando exatamente um usuário de
 * [users] tem aquele username (sem diferenciar maiúsculas; com dois, fica texto). O `@` tem de poder
 * abrir uma menção (ver [mentionMayStartAt]), então `a@b.com` não muda, e nada muda dentro de código
 * em linha ou em bloco. Um ponto no fim do nome é pontuação da frase: `@ana.` é a menção `@ana`.
 * Um username com espaço (`@yog Sothoth`) vale inteiro quando o texto depois do `@` começa com ele e o
 * nome acaba ali (sem letra, dígito ou `_` logo depois); entre vários, o mais longo.
 *
 * `@everyone` fica como texto: o servidor o lê literal. Só vira menção o usuário de nome `everyone` que foi
 * escolhido na lista ([picked]); digitado à mão, o nome nunca troca o aviso a todos por um usuário.
 */
fun encodeMentions(text: String, picked: Map<String, String>, users: Collection<User>): String {
    val found = findMentions(text, picked, users)
    if (found.isEmpty()) return text
    val out = StringBuilder(text.length + 40 * found.size)
    var last = 0
    for (mention in found) {
        out.append(text, last, mention.start).append("@mention(<@").append(mention.id).append(">)")
        last = mention.end
    }
    return out.append(text, last, text.length).toString()
}

/** Uma menção de usuário no rascunho: o `@nome` vai de [start] até antes de [end] e é de [id]. */
data class DraftMention(val start: Int, val end: Int, val id: String)

/**
 * As menções de usuário de [text], na ordem, com a mesma regra de [encodeMentions]: é o que ela troca por
 * `@mention(<@id>)`, e o compositor destaca exatamente isso.
 */
fun findMentions(text: String, picked: Map<String, String>, users: Collection<User>): List<DraftMention> {
    if ('@' !in text) return emptyList()
    val byUsername = users.groupBy { it.username.lowercase() }
    fun idOf(name: String): String? =
        picked[name] ?: if (name.equals(EVERYONE_NAME, ignoreCase = true)) null else byUsername[name.lowercase()]?.singleOrNull()?.id
    // Os nomes com espaço não cabem no corte por palavra de [mentionAt]: são procurados inteiros, do mais longo ao mais curto.
    val spaced = buildMap {
        for ((name, ids) in byUsername) {
            if (name.any(Char::isWhitespace)) ids.singleOrNull()?.let { put(name, it.id) }
        }
        for ((name, id) in picked) if (name.any(Char::isWhitespace)) put(name.lowercase(), id)
    }.entries.sortedByDescending { it.key.length }
    val code = codeRanges(text)
    val found = ArrayList<DraftMention>()
    var nextCode = 0
    var i = 0
    while (i < text.length) {
        val codeRange = code.getOrNull(nextCode)
        if (codeRange != null && i == codeRange.first) {
            i = codeRange.last + 1
            nextCode++
            continue
        }
        val mention = if (text[i] == '@' && mentionMayStartAt(text, i)) spacedMentionAt(text, i, spaced) ?: mentionAt(text, i, ::idOf) else null
        if (mention == null) {
            i++
        } else {
            found += DraftMention(i, mention.end, mention.id)
            i = mention.end
        }
    }
    return found
}

/**
 * Onde [text] tem um `@everyone` que a mensagem mostra como aviso a todos: terminando ali ([everyoneAt]), em qualquer
 * lugar como o servidor o lê, menos dentro de código, que o Markdown mostra como código. Cada trecho vai do `@` ao fim.
 */
fun findEveryone(text: String): List<IntRange> {
    if ('@' !in text) return emptyList()
    val code = codeRanges(text)
    val found = ArrayList<IntRange>()
    var at = text.indexOf('@')
    while (at >= 0) {
        if (everyoneAt(text, at) && code.none { at in it }) {
            found += at until at + EVERYONE_MENTION.length
        }
        at = text.indexOf('@', at + 1)
    }
    return found
}

private class FoundMention(val id: String, val end: Int)

/** O nome de [spaced] (minúsculo para id, do mais longo ao mais curto) que começa depois do `@` em [at] e acaba ali. */
private fun spacedMentionAt(text: String, at: Int, spaced: List<Map.Entry<String, String>>): FoundMention? {
    for ((name, id) in spaced) {
        val end = at + 1 + name.length
        if (end > text.length || !text.regionMatches(at + 1, name, 0, name.length, ignoreCase = true)) continue
        if (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) continue
        return FoundMention(id, end)
    }
    return null
}

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
internal fun codeRanges(text: String): List<IntRange> {
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
