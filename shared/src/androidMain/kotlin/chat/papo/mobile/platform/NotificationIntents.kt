package chat.papo.mobile.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import chat.papo.mobile.chat.ConversationNotification
import chat.papo.mobile.chat.NotificationOpen

/**
 * O toque numa notificação: o `PendingIntent` que a notificação leva e a leitura dele na activity. O que chega por um
 * `Intent` é de fora do app (a activity é exportada, como todo lançador): [parse] só aceita ids no formato dos do
 * servidor e ignora o resto; quem confere se a conta existe é a tela, depois do bloqueio.
 */
object NotificationIntents {
    private const val ACTION_OPEN = "chat.papo.mobile.action.OPEN_CONVERSATION"
    private const val EXTRA_ACCOUNT = "chat.papo.mobile.extra.ACCOUNT_ID"
    private const val EXTRA_CONVERSATION = "chat.papo.mobile.extra.CONVERSATION_ID"
    private const val EXTRA_MESSAGE = "chat.papo.mobile.extra.MESSAGE_ID"
    private const val EXTRA_CREATED_AT = "chat.papo.mobile.extra.CREATED_AT"
    private const val EXTRA_APPROXIMATE = "chat.papo.mobile.extra.APPROXIMATE"

    /**
     * Abre a activity do lançador na conversa de [notification]. O intent é explícito (o componente do lançador do
     * próprio app) e o `PendingIntent` imutável. O endereço dele tem a conta e a conversa: o sistema compara os
     * intents sem olhar os extras, e sem isso duas conversas dividiriam um `PendingIntent` e a segunda levaria à primeira.
     * A ação própria faz o sistema entregar o intent à activity que já está aberta, em vez de só trazer a tarefa à frente.
     */
    fun open(context: Context, notification: ConversationNotification): PendingIntent? {
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        val address = Uri.Builder().scheme("papo-notification").authority(notification.accountId).appendPath(notification.conversationId).build()
        val intent = Intent(ACTION_OPEN)
            .setComponent(launcher)
            .setData(address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ACCOUNT, notification.accountId)
            .putExtra(EXTRA_CONVERSATION, notification.conversationId)
            .putExtra(EXTRA_MESSAGE, notification.target.messageId)
            .putExtra(EXTRA_CREATED_AT, notification.target.createdAt)
            .putExtra(EXTRA_APPROXIMATE, notification.target.approximate)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** O pedido que [intent] leva, ou null se ele não é de uma notificação ou não passa pela conferência. */
    fun parse(intent: Intent?): NotificationOpen? {
        if (intent?.action != ACTION_OPEN) return null
        return NotificationOpen.parse(
            accountId = intent.getStringExtra(EXTRA_ACCOUNT),
            conversationId = intent.getStringExtra(EXTRA_CONVERSATION),
            messageId = intent.getStringExtra(EXTRA_MESSAGE),
            createdAt = intent.getStringExtra(EXTRA_CREATED_AT),
            approximate = intent.getBooleanExtra(EXTRA_APPROXIMATE, false),
        )
    }
}
