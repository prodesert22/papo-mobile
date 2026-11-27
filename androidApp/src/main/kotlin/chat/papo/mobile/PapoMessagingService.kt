package chat.papo.mobile

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * O serviço do Firebase Cloud Messaging. O papo-push manda mensagens com bloco de notificação: com o app em segundo plano
 * ou fechado o Android as mostra sozinho (canal, ícone e cor vêm do manifesto) e nenhum código do app roda; o toque abre
 * a `MainActivity` com os dados como extras.
 */
class PapoMessagingService : FirebaseMessagingService() {
    /**
     * Roda sem sessão de conta e até sem o app desbloqueado: só publica o token. Cada conta o registra no servidor dela
     * quando a sessão existe. O token não vai para log.
     *
     * O Firebase 26 marcou este método como obsoleto em favor de `onRegistered`, que entrega o id da instalação (envio
     * direto, só com `firebase_messaging_installation_id_enabled` no manifesto). O papo-push manda para o token do FCM
     * (`PushDeviceRequest.token`), então é o `onNewToken` que serve, e ele continua sendo chamado por padrão.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        (application as PapoApplication).pushTokens.onNewToken(token)
    }

    /**
     * Só chega aqui com o app na frente, e então o WebSocket já tratou da mensagem (a notificação do app, ou nenhuma, se a
     * conversa está aberta ou silenciada): o push é descartado de propósito, sem ler o conteúdo. Uma mensagem só de dados
     * com o app em segundo plano o servidor não manda hoje; se vier, também não há o que fazer com ela.
     */
    override fun onMessageReceived(message: RemoteMessage) = Unit
}
