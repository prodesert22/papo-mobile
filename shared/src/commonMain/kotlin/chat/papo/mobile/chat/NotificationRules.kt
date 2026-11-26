package chat.papo.mobile.chat

/** O tipo de uma mensagem de outra pessoa, do ponto de vista de quem recebe. */
enum class MessageKind {
    /** `@usuário` ou resposta a uma mensagem minha. */
    Mention,

    /** `@everyone`, que não é menção. */
    Everyone,

    /** Qualquer outra. */
    Plain,
}

/** O tipo está desligado por "Suprimir menções" ou "Suprimir @everyone"? Mensagem comum nunca é suprimida. */
private fun isSuppressed(kind: MessageKind, effective: EffectiveNotifications): Boolean = when (kind) {
    MessageKind.Mention -> effective.suppressMentions
    MessageKind.Everyone -> effective.suppressEveryone
    MessageKind.Plain -> false
}

/**
 * Uma mensagem de outra pessoa num canal vira notificação do sistema?
 *
 * - [isOwn]: a mensagem é da própria pessoa; nunca notifica.
 * - [conversationOpen]: a conversa está aberta na tela com o app na frente.
 * - [permissionGranted]: o Android deixa o app notificar.
 *
 * Depois disso: tipo suprimido não notifica; nível Nada não notifica; menção e `@everyone` notificam mesmo com o
 * canal ou o servidor silenciado; mensagem comum só notifica sem silêncio e com o nível Todas.
 */
fun shouldNotify(
    kind: MessageKind,
    effective: EffectiveNotifications,
    isOwn: Boolean = false,
    conversationOpen: Boolean = false,
    permissionGranted: Boolean = true,
): Boolean {
    if (isOwn || conversationOpen || !permissionGranted) return false
    if (isSuppressed(kind, effective)) return false
    if (effective.level == NotificationLevel.Nothing) return false
    return when (kind) {
        MessageKind.Mention, MessageKind.Everyone -> true
        MessageKind.Plain -> !effective.muted && effective.level == NotificationLevel.All
    }
}

/** O mesmo para uma DM: silenciada não notifica; senão toda mensagem notifica. Nível e supressão não valem aqui. */
fun shouldNotifyDm(
    effective: EffectiveDm,
    isOwn: Boolean = false,
    conversationOpen: Boolean = false,
    permissionGranted: Boolean = true,
): Boolean {
    if (isOwn || conversationOpen || !permissionGranted) return false
    return !effective.muted
}

/**
 * A mensagem entra no número de menções? Menção e resposta entram se "Suprimir menções" está desligado, e
 * `@everyone` se "Suprimir @everyone" está desligado. Nível e silêncio não mudam o número.
 */
fun countsAsMention(kind: MessageKind, effective: EffectiveNotifications): Boolean =
    kind != MessageKind.Plain && !isSuppressed(kind, effective)

/** O canal mostra o círculo de "nova mensagem" (e nome em branco)? Silenciado, não. */
fun showsUnreadDot(effective: EffectiveNotifications): Boolean = !effective.muted

/** O mesmo para uma DM. */
fun showsUnreadDot(effective: EffectiveDm): Boolean = !effective.muted
