package chat.papo.mobile.chat

/** Estado de presença como a lista de membros o separa, na ordem em que as seções aparecem. */
enum class MemberStatus {
    Online, Away, Busy, Offline;

    companion object {
        /** Sem presença anunciada, ou com um estado que o app não conhece, a pessoa conta como offline. */
        fun of(presence: Presence?): MemberStatus = when (presence?.status) {
            "online" -> Online
            "away" -> Away
            "busy" -> Busy
            else -> Offline
        }
    }
}

/** Um membro do servidor na lista; [presence] é nula quando o servidor não o anunciou. [isMe] marca quem usa a conta. */
data class Member(val user: User, val presence: Presence?, val isMe: Boolean) {
    /** Identifica a linha numa lista preguiçosa: cada usuário aparece uma vez só. */
    val id: String get() = user.id

    val status: MemberStatus get() = MemberStatus.of(presence)

    /** Mensagem de status, a da presença ao vivo antes da guardada no perfil; nula se estiver vazia. */
    val statusMessage: String? get() = (presence?.statusMessage ?: user.statusMessage)?.takeIf { it.isNotBlank() }
}

/** Membros de um mesmo [status], já em ordem alfabética. */
data class MemberSection(val status: MemberStatus, val members: List<Member>) {
    val count: Int get() = members.size
}

/** O que a lista de membros mostra. [total] é quantos membros há (sem os banidos), com ou sem busca. */
sealed interface MemberListing {
    val total: Int

    /** Sem busca: uma seção por estado, as vazias omitidas. */
    data class Grouped(override val total: Int, val sections: List<MemberSection>) : MemberListing

    /** Com busca: sem seções, os que casam com ela na ordem de [searchByName]. */
    data class Found(override val total: Int, val members: List<Member>) : MemberListing
}

/**
 * Monta a lista de membros do servidor. Banidos ficam de fora. Sem [query], agrupa por estado (disponível, ausente,
 * ocupado, offline), cada grupo em ordem alfabética do nome de exibição sem diferenciar acento nem maiúsculas, com
 * o usuário e depois o id desempatando. Com [query], devolve só quem casa com ela, pelo nome de exibição ou por
 * `@usuário`, com as mesmas regras de [searchByName]. [me] é o id de quem usa a conta.
 */
fun groupMembers(users: Collection<User>, presence: Map<String, Presence>, query: String, me: String): MemberListing {
    val members = sortedMembers(users, presence, me)
    if (query.isBlank()) {
        val byStatus = members.groupBy { it.status }
        return MemberListing.Grouped(members.size, MemberStatus.entries.mapNotNull { status -> byStatus[status]?.let { MemberSection(status, it) } })
    }
    return MemberListing.Found(members.size, searchByName(query, members) { listOf(it.user.displayName, it.user.username, "@${it.user.username}") })
}

/** Dobra cada nome uma vez só: a ordenação compara muito mais vezes do que há usuários. */
private fun sortedMembers(users: Collection<User>, presence: Map<String, Presence>, me: String): List<Member> {
    class Keyed(val member: Member, val name: String, val username: String)
    return users
        .filterNot { it.banned }
        .map { Keyed(Member(it, presence[it.id], isMe = it.id == me), foldForSearch(it.displayName), foldForSearch(it.username)) }
        .sortedWith(compareBy({ it.name }, { it.username }, { it.member.id }))
        .map { it.member }
}
