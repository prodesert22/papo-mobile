package chat.papo.mobile.chat

import kotlin.time.Instant

/**
 * Como uma linha da lista de conversas se mostra. [emphasized]: nome e ícone em destaque (branco, em negrito).
 * [dimmed]: nome e ícone esmaecidos, de uma conversa silenciada. [showDot]: o círculo de mensagem nova. [badge]: o
 * texto do número vermelho de menções, no lugar do círculo.
 */
data class RowStyle(val emphasized: Boolean, val dimmed: Boolean, val showDot: Boolean, val badge: String?)

/**
 * A regra visual de uma linha: lida fica comum; com mensagem nova e sem silêncio ganha destaque e o círculo; com
 * menções o destaque é o mesmo e o número vermelho toma o lugar do círculo; silenciada esmaece, perde destaque e
 * círculo, e o número de menções continua ([mentions] não depende do silêncio, [capped] diz que a contagem é parcial).
 */
fun rowStyle(unread: Boolean, mentions: Int, capped: Boolean, muted: Boolean): RowStyle {
    val badge = badgeText(mentions, capped)
    return RowStyle(
        emphasized = (unread || badge != null) && !muted,
        dimmed = muted,
        showDot = unread && !muted && badge == null,
        badge = badge,
    )
}

/**
 * A conta tem algo a destacar no ícone dela (o ponto de mensagem nova)? Algum canal que ela lê tem mensagem não lida
 * e não está silenciado (nem ele, nem o servidor), ou alguma conversa direta tem mensagem não lida e não está
 * silenciada. Silenciar o servidor não cala as conversas diretas. [channels] são os canais que a conta lê.
 */
fun hasUnreadDot(
    channels: List<Channel>,
    dms: List<Dm>,
    server: ServerNotificationSettings,
    channelSettings: Map<String, ChannelNotificationSettings>,
    dmSettings: Map<String, DmNotificationSettings>,
    now: Instant,
): Boolean =
    channels.any { it.unread && showsUnreadDot(effective(server, channelSettings[it.id] ?: ChannelNotificationSettings(), now)) } ||
        dms.any { it.unreadCount > 0 && showsUnreadDot(effectiveDm(server, dmSettings[it.id] ?: DmNotificationSettings(), now)) }
