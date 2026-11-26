package chat.papo.mobile.chat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Quais mensagens viram notificação do sistema. Não muda o número de menções do selo nem o círculo de "nova
 * mensagem" (isso é do silêncio e da supressão). [wire] é o texto que o backend usará quando tiver o campo.
 */
enum class NotificationLevel(val wire: String) {
    All("all"),
    OnlyMentions("only_mentions"),
    Nothing("off");

    companion object {
        /** O nível de [wire], ou null se o texto não é de nenhum (valor de outra versão ou estragado). */
        fun fromWire(wire: String?): NotificationLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/** Um silêncio. [until] nulo quer dizer "até reativar". */
data class Mute(val until: Instant? = null) {
    /** Vale enquanto não chegou o fim: no instante exato do fim já acabou. */
    fun isActive(now: Instant): Boolean = until == null || until > now
}

/** Os tempos de silêncio que a pessoa pode escolher. */
enum class MuteDuration(val length: Duration?) {
    Minutes15(15.minutes),
    Hour1(1.hours),
    Hours3(3.hours),
    Hours8(8.hours),
    Hours24(24.hours),
    UntilReenabled(null);

    /** O silêncio que começa em [now] e dura isto. */
    fun from(now: Instant): Mute = Mute(length?.let { now + it })
}

/** O geral de uma conta: vale para todo canal que não escolheu o próprio valor. Os padrões são os do plano. */
data class ServerNotificationSettings(
    val level: NotificationLevel = NotificationLevel.OnlyMentions,
    val suppressEveryone: Boolean = false,
    val suppressMentions: Boolean = false,
    val mute: Mute? = null,
    val sync: Boolean = true,
)

/** Um canal. Campo nulo quer dizer "Usar padrão do servidor". */
data class ChannelNotificationSettings(
    val level: NotificationLevel? = null,
    val suppressEveryone: Boolean? = null,
    val suppressMentions: Boolean? = null,
    val mute: Mute? = null,
    val sync: Boolean? = null,
) {
    /** Tudo segue o servidor: não há o que guardar. */
    val isDefault: Boolean
        get() = level == null && suppressEveryone == null && suppressMentions == null && mute == null && sync == null
}

/** Uma conversa direta: só tem silêncio, e a sincronização segue o servidor se for nula. */
data class DmNotificationSettings(
    val mute: Mute? = null,
    val sync: Boolean? = null,
) {
    val isDefault: Boolean get() = mute == null && sync == null
}

/** De quem é o silêncio que vale. */
enum class MuteSource {
    /** O da própria conversa (canal ou DM). */
    Channel,

    /** O do servidor; nunca vale para DM. */
    Server,
}

/** Um silêncio que vale agora: de onde vem e quando acaba (nulo = até reativar). */
data class ActiveMute(val source: MuteSource, val until: Instant?)

/** O que vale para um canal depois de aplicar o padrão do servidor. */
data class EffectiveNotifications(
    val level: NotificationLevel,
    val suppressEveryone: Boolean,
    val suppressMentions: Boolean,
    val mute: ActiveMute?,
    /** A opção "Sincronizar com o backend" que vale; se o backend aceita é outra pergunta (`NotificationPrefs.isSyncing`). */
    val sync: Boolean,
) {
    val muted: Boolean get() = mute != null
}

/** O que vale para uma DM: só o silêncio (o do servidor não conta) e a sincronização. */
data class EffectiveDm(val mute: ActiveMute?, val sync: Boolean) {
    val muted: Boolean get() = mute != null
}

/** Os valores do [channel] sobre o padrão do [server] em [now]. Silêncio que já acabou conta como não silenciado. */
fun effective(server: ServerNotificationSettings, channel: ChannelNotificationSettings, now: Instant): EffectiveNotifications {
    val own = channel.mute?.takeIf { it.isActive(now) }?.let { ActiveMute(MuteSource.Channel, it.until) }
    val inherited = server.mute?.takeIf { it.isActive(now) }?.let { ActiveMute(MuteSource.Server, it.until) }
    return EffectiveNotifications(
        level = channel.level ?: server.level,
        suppressEveryone = channel.suppressEveryone ?: server.suppressEveryone,
        suppressMentions = channel.suppressMentions ?: server.suppressMentions,
        mute = own ?: inherited,
        sync = channel.sync ?: server.sync,
    )
}

/** Os valores de uma DM. Silenciar o servidor não cala as DMs. */
fun effectiveDm(server: ServerNotificationSettings, dm: DmNotificationSettings, now: Instant): EffectiveDm {
    val own = dm.mute?.takeIf { it.isActive(now) }?.let { ActiveMute(MuteSource.Channel, it.until) }
    return EffectiveDm(mute = own, sync = dm.sync ?: server.sync)
}
