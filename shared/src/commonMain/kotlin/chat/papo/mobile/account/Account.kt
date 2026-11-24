package chat.papo.mobile.account

/** Uma conta em um servidor. A identidade é o par (servidor, usuário). */
data class Account(
    val id: String,
    val server: ServerUrl,
    val userId: String,
    val username: String,
    val nickname: String?,
    val serverName: String,
    val serverIcon: ByteArray?,
    /** Apelido dado pelo usuário para distinguir esta conta das outras. */
    val label: String?,
    /** Cor ARGB escolhida pelo usuário para esta conta. */
    val color: Int?,
    val sessionExpired: Boolean,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username

    /** Identificação sem ambiguidade: `usuário @ servidor`. */
    val handle: String get() = "$username @ ${server.display}"

    /** Nome mostrado na lista de servidores. */
    val title: String get() = label?.takeIf { it.isNotBlank() } ?: serverName

    override fun equals(other: Any?): Boolean =
        other is Account && id == other.id && server == other.server && userId == other.userId &&
            username == other.username && nickname == other.nickname && serverName == other.serverName &&
            serverIcon.contentEquals(other.serverIcon) && label == other.label && color == other.color &&
            sessionExpired == other.sessionExpired

    override fun hashCode(): Int = id.hashCode()
}

/** Cores oferecidas para marcar uma conta (ARGB). */
val AccountColors: List<Int> = listOf(
    0xFF5B8DEF.toInt(), 0xFF3DBE8B.toInt(), 0xFFE5A23C.toInt(), 0xFFE4572E.toInt(),
    0xFFB678E0.toInt(), 0xFFE86CA5.toInt(), 0xFF39B8C9.toInt(), 0xFF8A93A6.toInt(),
)
