package chat.papo.mobile.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import chat.papo.mobile.chat.ConversationKind
import chat.papo.mobile.chat.ConversationNotification
import chat.papo.mobile.chat.SystemNotifier
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.notif_system_channel_dms
import chat.papo.mobile.resources.notif_system_channel_mentions
import chat.papo.mobile.resources.notif_system_channel_messages
import chat.papo.mobile.resources.notif_system_channel_push
import chat.papo.mobile.resources.notif_system_summary
import org.jetbrains.compose.resources.getString
import java.util.concurrent.ConcurrentHashMap

/**
 * O canal dos pushes que o Android mostra sozinho (`default_notification_channel_id` no manifesto do app, que repete este
 * valor). É um canal do app, não de uma conta: o push não diz de que conta veio.
 */
const val PUSH_CHANNEL_ID = "papo.push"

/**
 * As notificações do Android. Cada conta tem um grupo de canais ("servidor · @usuário") com três canais: menções e
 * respostas e mensagens diretas (importância alta, com selo no ícone) e mensagens (padrão, sem selo). Uma conversa com
 * menção sai num dos dois primeiros, com `setNumber(menções)`, e é isso que o selo soma; sem menção, no terceiro. Cada
 * conversa tem uma notificação (`MessagingStyle`) e as de uma conta se agrupam sob um resumo.
 *
 * Tudo se chama pela conta e pela conversa: a etiqueta de cada notificação é `papo.<conta>/c:<conversa>`, então nada de
 * uma conta cancela ou troca o de outra (regra 4), nem de duas contas do mesmo servidor. O som e a vibração ficam nas
 * configurações de cada canal do Android. [smallIcon] é o ícone de uma cor da barra de status.
 */
class AndroidSystemNotifier(context: Context, @param:DrawableRes private val smallIcon: Int) : SystemNotifier {
    private val app = context.applicationContext
    private val manager = NotificationManagerCompat.from(app)

    /** O rótulo com que os canais de cada conta foram criados; o grupo só é recriado se ele muda. */
    private val labels = ConcurrentHashMap<String, String>()

    override fun isPermissionGranted(): Boolean = manager.areNotificationsEnabled()

    override suspend fun post(notification: ConversationNotification) {
        // Sem a permissão o `notify` falharia; e as notificações desligadas nas configurações já a negam em `isPermissionGranted`.
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(notification.accountId, notification.accountLabel)
        val channel = when {
            notification.mentionCount <= 0 -> channelId(notification.accountId, MESSAGES)
            notification.kind == ConversationKind.Dm -> channelId(notification.accountId, DMS)
            else -> channelId(notification.accountId, MENTIONS)
        }
        manager.notify(tag(notification.accountId, notification.conversationId), NOTIFICATION_ID, conversation(notification, channel))
        postSummaryIfAbsent(notification)
    }

    /**
     * Cria o canal global dos pushes ([PUSH_CHANNEL_ID]) logo que o app abre: sem ele o Android mostraria o push no canal
     * "Diversos". Importância alta, com selo, como as menções. Criar de novo um canal que já existe não muda as escolhas
     * que a pessoa fez nele.
     */
    suspend fun ensurePushChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(PUSH_CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
                .setName(getString(Res.string.notif_system_channel_push))
                .setShowBadge(true)
                .build(),
        )
    }

    override fun cancelConversation(accountId: String, conversationId: String) {
        manager.cancel(tag(accountId, conversationId), NOTIFICATION_ID)
        dropSummaryIfAlone(accountId)
    }

    override fun cancelAccount(accountId: String) {
        for (active in activeOf(accountId)) manager.cancel(active.tag, active.id)
    }

    override fun removeAccount(accountId: String) {
        cancelAccount(accountId)
        manager.deleteNotificationChannelGroup(groupId(accountId))
        labels.remove(accountId)
    }

    private fun conversation(n: ConversationNotification, channel: String): android.app.Notification {
        val now = System.currentTimeMillis()
        // O "eu" do estilo só precisa de um nome; as mensagens aqui são sempre de outras pessoas.
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(n.accountLabel).build())
        if (n.kind == ConversationKind.Channel) {
            style.setConversationTitle("#" + n.title).setGroupConversation(true)
        }
        for (line in n.messages) {
            style.addMessage(line.text, line.at?.toEpochMilliseconds() ?: now, Person.Builder().setName(line.author).build())
        }
        val last = n.messages.last()
        val builder = NotificationCompat.Builder(app, channel)
            .setSmallIcon(smallIcon)
            .setStyle(style)
            .setContentTitle(if (n.kind == ConversationKind.Channel) "#" + n.title else last.author)
            .setContentText(last.text)
            .setSubText(n.accountLabel)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(groupKey(n.accountId))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setWhen(last.at?.toEpochMilliseconds() ?: now)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(NotificationIntents.open(app, n))
        if (n.mentionCount > 0) builder.setNumber(n.mentionCount)
        return builder.build()
    }

    /** O resumo do grupo da conta; o sistema só mostra o grupo recolhido com ele. Fica enquanto houver conversa. */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun postSummaryIfAbsent(n: ConversationNotification) {
        val tag = summaryTag(n.accountId)
        if (activeOf(n.accountId).any { it.tag == tag }) return
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            android.app.PendingIntent.getActivity(app, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val summary = NotificationCompat.Builder(app, channelId(n.accountId, MESSAGES))
            .setSmallIcon(smallIcon)
            .setContentTitle(n.accountLabel)
            .setContentText(getString(Res.string.notif_system_summary))
            .setGroup(groupKey(n.accountId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(tag, NOTIFICATION_ID, summary)
    }

    /** Saiu a última conversa da conta: o resumo sozinho não diz nada. */
    private fun dropSummaryIfAlone(accountId: String) {
        val summary = summaryTag(accountId)
        val active = activeOf(accountId)
        if (active.isNotEmpty() && active.all { it.tag == summary }) manager.cancel(summary, NOTIFICATION_ID)
    }

    private fun activeOf(accountId: String): List<StatusBarNotification> {
        val prefix = tagPrefix(accountId)
        return manager.activeNotifications.filter { it.packageName == app.packageName && it.tag?.startsWith(prefix) == true }
    }

    private suspend fun ensureChannels(accountId: String, label: String) {
        if (labels[accountId] == label) return
        val group = groupId(accountId)
        manager.createNotificationChannelGroup(NotificationChannelGroupCompat.Builder(group).setName(label).build())
        manager.createNotificationChannelsCompat(
            listOf(
                channel(accountId, MENTIONS, getString(Res.string.notif_system_channel_mentions), NotificationManager.IMPORTANCE_HIGH, badge = true),
                channel(accountId, DMS, getString(Res.string.notif_system_channel_dms), NotificationManager.IMPORTANCE_HIGH, badge = true),
                channel(accountId, MESSAGES, getString(Res.string.notif_system_channel_messages), NotificationManager.IMPORTANCE_DEFAULT, badge = false),
            ),
        )
        labels[accountId] = label
    }

    private fun channel(accountId: String, kind: String, name: String, importance: Int, badge: Boolean) =
        NotificationChannelCompat.Builder(channelId(accountId, kind), importance)
            .setName(name)
            .setGroup(groupId(accountId))
            .setShowBadge(badge)
            .build()

    private companion object {
        const val MENTIONS = "mentions"
        const val DMS = "dms"
        const val MESSAGES = "messages"

        /** Uma notificação por etiqueta: a etiqueta já distingue a conversa, então o número é fixo. */
        const val NOTIFICATION_ID = 1

        fun groupId(accountId: String) = "papo.$accountId"
        fun groupKey(accountId: String) = "papo.$accountId"
        fun channelId(accountId: String, kind: String) = "papo.$accountId.$kind"
        fun tagPrefix(accountId: String) = "papo.$accountId/"
        fun tag(accountId: String, conversationId: String) = tagPrefix(accountId) + "c:" + conversationId
        fun summaryTag(accountId: String) = tagPrefix(accountId) + "summary"
    }
}
