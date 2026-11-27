package chat.papo.mobile.ui.notifications

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.LevelFailureReason
import chat.papo.mobile.chat.LevelSave
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.chat.effective
import chat.papo.mobile.chat.effectiveDm
import chat.papo.mobile.core.dayKey
import chat.papo.mobile.core.formatDateTime
import chat.papo.mobile.core.formatTime
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * O que a folha de um canal mostra. Os campos `server*` são o que vale quando o canal está em "Usar padrão do
 * servidor", para a opção dizer o valor entre parênteses; os do canal são nulos nesse caso.
 */
data class ChannelNotificationsState(
    /** O silêncio que vale agora, do canal ou herdado do servidor. */
    val mute: ActiveMute?,
    /** O nível do canal vai e vem do servidor (a sincronização que vale). */
    val synced: Boolean,
    /** O nível que o servidor guarda para o canal; é o que vale enquanto [synced]. */
    val serverLevel: NotificationLevel,
    /** A escolha deste aparelho, quando não sincronizado; nulo é "Usar padrão do servidor". */
    val level: NotificationLevel?,
    /** O nível geral deste aparelho, que vale para o canal sem escolha própria quando não sincronizado. */
    val defaultLevel: NotificationLevel,
    val suppressEveryone: Boolean?,
    val serverSuppressEveryone: Boolean,
    val suppressMentions: Boolean?,
    val serverSuppressMentions: Boolean,
    /** A sincronização do canal; nulo segue [serverSync]. */
    val sync: Boolean?,
    val serverSync: Boolean,
    /** O nível deste canal está sendo gravado no servidor. */
    val saving: Boolean,
    /** Por que a última gravação deste canal não valeu, se não valeu. */
    val failure: LevelFailureReason?,
) {
    /** Só o silêncio do próprio canal pode ser reativado ali. */
    val canUnmute: Boolean get() = mute?.source == MuteSource.Channel
}

/** O que a folha de uma conversa direta mostra: só o silêncio dela (o do servidor não vale). */
data class DmNotificationsState(val mute: ActiveMute?) {
    val canUnmute: Boolean get() = mute != null
}

/** O que a tela de notificações da conta e o menu de silêncio do servidor mostram. */
data class ServerNotificationsState(
    /** O nível geral deste aparelho: vale para os canais sem sincronização. */
    val level: NotificationLevel,
    val suppressEveryone: Boolean,
    val suppressMentions: Boolean,
    /** O silêncio do servidor que vale agora; a origem é sempre [MuteSource.Server]. */
    val mute: ActiveMute?,
    val sync: Boolean,
) {
    val canUnmute: Boolean get() = mute != null
}

fun channelNotificationsState(
    channelId: String,
    server: ServerNotificationSettings,
    channel: ChannelNotificationSettings,
    serverLevel: NotificationLevel,
    save: LevelSave,
    now: Instant,
): ChannelNotificationsState {
    val effective = effective(server, channel, now, serverLevel)
    return ChannelNotificationsState(
        mute = effective.mute,
        synced = effective.sync,
        serverLevel = serverLevel,
        level = channel.level,
        defaultLevel = server.level,
        suppressEveryone = channel.suppressEveryone,
        serverSuppressEveryone = server.suppressEveryone,
        suppressMentions = channel.suppressMentions,
        serverSuppressMentions = server.suppressMentions,
        sync = channel.sync,
        serverSync = server.sync,
        saving = save.savingChannelId == channelId,
        failure = save.failure?.takeIf { it.channelId == channelId }?.reason,
    )
}

fun dmNotificationsState(server: ServerNotificationSettings, dm: DmNotificationSettings, now: Instant): DmNotificationsState =
    DmNotificationsState(mute = effectiveDm(server, dm, now).mute)

fun serverNotificationsState(server: ServerNotificationSettings, now: Instant): ServerNotificationsState = ServerNotificationsState(
    level = server.level,
    suppressEveryone = server.suppressEveryone,
    suppressMentions = server.suppressMentions,
    mute = server.mute?.takeIf { it.isActive(now) }?.let { ActiveMute(MuteSource.Server, it.until) },
    sync = server.sync,
)

/** O fim de um silêncio como o usuário lê: só a hora se é hoje, senão a data e a hora. */
fun Instant.muteEndText(now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    if (dayKey(zone) == now.dayKey(zone)) formatTime(zone) else formatDateTime(zone)

/**
 * O que cada escolha da tela chama em [NotificationPrefs]. Nulo em "Usar padrão do servidor". Os tempos de silêncio
 * contam a partir de [now]; reativar volta ao padrão do canal (nulo), e o do servidor, a "não silenciado". Níveis e
 * sincronização têm regras próprias (servidor ou aparelho, cópia ao ligar e desligar) e ficam em [NotificationPrefs].
 */
class NotificationEdits(
    private val prefs: NotificationPrefs,
    private val now: () -> Instant = { Clock.System.now() },
) {
    fun setServerLevel(level: NotificationLevel) = prefs.updateServer { it.copy(level = level) }
    fun setServerSuppressEveryone(value: Boolean) = prefs.updateServer { it.copy(suppressEveryone = value) }
    fun setServerSuppressMentions(value: Boolean) = prefs.updateServer { it.copy(suppressMentions = value) }
    fun setServerSync(value: Boolean) = prefs.setServerSync(value)
    fun muteServer(duration: MuteDuration) = prefs.updateServer { it.copy(mute = duration.from(now())) }
    fun unmuteServer() = prefs.updateServer { it.copy(mute = null) }

    fun setChannelLevel(channelId: String, level: NotificationLevel?) = prefs.setChannelLevel(channelId, level)
    fun setChannelSuppressEveryone(channelId: String, value: Boolean?) = prefs.updateChannel(channelId) { it.copy(suppressEveryone = value) }
    fun setChannelSuppressMentions(channelId: String, value: Boolean?) = prefs.updateChannel(channelId) { it.copy(suppressMentions = value) }
    fun setChannelSync(channelId: String, value: Boolean?) = prefs.setChannelSync(channelId, value)
    fun muteChannel(channelId: String, duration: MuteDuration) = prefs.updateChannel(channelId) { it.copy(mute = duration.from(now())) }
    fun unmuteChannel(channelId: String) = prefs.updateChannel(channelId) { it.copy(mute = null) }

    fun muteDm(dmId: String, duration: MuteDuration) = prefs.updateDm(dmId) { it.copy(mute = duration.from(now())) }
    fun unmuteDm(dmId: String) = prefs.updateDm(dmId) { it.copy(mute = null) }
}
