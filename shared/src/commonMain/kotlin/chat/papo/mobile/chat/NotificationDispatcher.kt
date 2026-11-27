package chat.papo.mobile.chat

import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.ws.WsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Onde o despachante acha as conversas e os nomes da conta; só o que a conta pode ler. */
interface ConversationDirectory {
    /** O canal [id], se a conta o lê; nulo para uma categoria, um canal que ela não vê ou um id desconhecido. */
    fun channel(id: String): Channel?

    /** A conversa direta [id], se existe. */
    fun dm(id: String): Dm?

    /** O nome de exibição do usuário [id], se o app o conhece. */
    fun userName(id: String): String?
}

/**
 * Decide, para uma conta, quais mensagens que chegam pelo WebSocket viram notificação do sistema, e mantém a
 * notificação de cada conversa em dia (a tira quando a conversa é aberta, lida ou apagada). Há um por [AccountSession]
 * e ele só conhece a conta dele: o mesmo id de conversa em outra conta é outra coisa (regra 4).
 *
 * As regras são as de [shouldNotify] e [shouldNotifyDm]; o que este tipo acrescenta é saber o [MessageKind]. O servidor
 * é quem diz se uma mensagem é gatilho (menção, resposta, `@everyone`): isso está na linha da caixa, que só existe
 * depois do `new_notification` e da leitura seguinte da caixa. Por isso a decisão espera, sem pedir nada à rede (regra
 * 15; é a leitura da própria caixa que traz a linha):
 * 1. a menção a este usuário no texto da mensagem já basta, e não se espera;
 * 2. senão espera até [ANNOUNCE_WINDOW] o `new_notification` da mensagem; sem ele, a mensagem é comum;
 * 3. com ele, espera até [INBOX_READ_WAIT] a leitura da caixa que começa depois dele e usa a linha da mensagem se ela
 *    veio; sem linha (nível "todas" no servidor manda um id efêmero) ou com a leitura falha, a mensagem é comum.
 * Conversas diretas notificam toda mensagem, então não esperam. Nada espera quando nenhum tipo de mensagem notificaria
 * (nível Nada, conversa aberta, sem permissão).
 *
 * Tudo roda no escopo da sessão, numa thread só, como o [ChatStore]. As chamadas ao [notifier] saem por uma fila, na
 * ordem em que foram decididas, para uma retirada nunca ser desfeita por uma notificação que ainda estava saindo; e
 * uma conversa troca de notificação no máximo uma vez por [MIN_UPDATE_INTERVAL]: o que chega nesse tempo vira uma
 * troca só, com tudo. A lista das últimas mensagens vive só na memória e começa vazia a cada sessão. O texto das
 * mensagens nunca vai para um registro (regra 5).
 */
class NotificationDispatcher(
    private val accountId: String,
    private val userId: String,
    /** O nome da conta como está agora (o usuário pode trocar o rótulo): servidor e `@usuário`. */
    private val accountLabel: () -> String,
    private val events: Flow<WsEvent>,
    private val readMarks: Flow<ReadMark>,
    private val inbox: InboxView,
    private val prefs: NotificationPrefs,
    private val directory: ConversationDirectory,
    /** O canal que está na tela agora. */
    private val viewing: StateFlow<String?>,
    /** As menções não lidas de uma conversa (as de canal ou as mensagens não lidas de uma conversa direta). */
    private val mentionCount: (conversationId: String) -> Int,
    private val notifier: SystemNotifier,
    private val texts: NotificationTexts,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Tracked(
        val messageId: String,
        val createdUs: Long?,
        val line: NotificationLine,
        /** Entra na contagem de menções da notificação (menção, resposta, `@everyone` não suprimido, conversa direta). */
        val counted: Boolean,
        val target: NotificationTarget,
    )

    private class Conversation(val kind: ConversationKind, var title: String) {
        val tracked = ArrayList<Tracked>()
        var lastPost: TimeMark? = null
        var scheduled: Job? = null
    }

    /** Uma mensagem esperando a decisão: [announced] recebe a leitura da caixa a esperar quando o `new_notification` chega. */
    private class Pending {
        val announced = CompletableDeferred<Long>()
        var deleted = false
    }

    private val conversations = HashMap<String, Conversation>()
    private val pending = HashMap<String, Pending>()
    private val outbox = KChannel<suspend () -> Unit>(KChannel.UNLIMITED)
    private var started = false

    /** Começa a ouvir. Chame antes de o WebSocket ligar, para não perder o primeiro evento. */
    fun start() {
        if (started) return
        started = true
        scope.launch { for (job in outbox) runSafely(job) }
        scope.launch { events.collect(::onEvent) }
        scope.launch { readMarks.collect(::onReadMark) }
        scope.launch { viewing.collect { id -> if (id != null) cancel(id) } }
        scope.launch { inbox.state.collect { onInbox(it) } }
    }

    private fun onEvent(event: WsEvent) {
        when (event) {
            is WsEvent.MessageCreated -> onMessage(event)
            is WsEvent.NewNotification -> event.messageId?.let { pending[it]?.announced?.complete(inbox.readsStarted.value + 1) }
            is WsEvent.MessageDeleted -> onDeleted(event)
            else -> Unit
        }
    }

    private fun onMessage(event: WsEvent.MessageCreated) {
        if (event.authorId == userId) return
        val entry = Pending()
        pending[event.id] = entry
        scope.launch {
            try {
                process(event, entry)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // O conteúdo da mensagem é de fora e a plataforma pode falhar: uma mensagem que não deu para tratar fica
                // sem notificação, e não derruba o app nem as seguintes.
            } finally {
                pending.remove(event.id)
            }
        }
    }

    private suspend fun process(event: WsEvent.MessageCreated, entry: Pending) {
        val dm = directory.dm(event.channelId)
            ?: if (directory.channel(event.channelId) == null) awaitDm(event.channelId) else null
        if (dm != null) {
            val effective = prefs.effectiveForDm(dm.id)
            if (!shouldNotifyDm(effective, conversationOpen = isOpen(dm.id), permissionGranted = notifier.isPermissionGranted())) return
            track(dm.id, ConversationKind.Dm, notificationName(directory.userName(dm.userId), texts.unknownUser), event, row = null, counted = true)
            return
        }
        val channel = directory.channel(event.channelId) ?: return
        if (!canNotifyAnything(channel.id)) return
        val (kind, row) = kindOf(event, entry)
        if (entry.deleted) return
        // Tudo é lido de novo depois da espera: a conversa pode ter sido aberta, ou o canal silenciado, nesse tempo.
        val effective = prefs.effectiveFor(channel.id)
        val allowed = shouldNotify(kind, effective, conversationOpen = isOpen(channel.id), permissionGranted = notifier.isPermissionGranted())
        if (!allowed) return
        track(channel.id, ConversationKind.Channel, notificationName(channel.name, ""), event, row, counted = countsAsMention(kind, effective))
    }

    /**
     * Uma conversa direta fechada (ou nova) só volta a existir no app com o `dm_update`, que o servidor manda logo depois
     * do `message`. Para uma conversa que o app ainda não conhece, espera até [ANNOUNCE_WINDOW] que ela apareça como
     * conversa direta; olha só a memória, sem pedir nada à rede. Devolve nulo se ela virou canal ou não apareceu.
     */
    private suspend fun awaitDm(conversationId: String): Dm? = withTimeoutOrNull(ANNOUNCE_WINDOW) {
        var dm = directory.dm(conversationId)
        while (dm == null && directory.channel(conversationId) == null) {
            delay(DIRECTORY_POLL)
            dm = directory.dm(conversationId)
        }
        dm
    }

    /** Alguma mensagem deste canal notificaria, qualquer que seja o tipo dela? Senão, não adianta esperar para saber o tipo. */
    private fun canNotifyAnything(channelId: String): Boolean {
        val effective = prefs.effectiveFor(channelId)
        val open = isOpen(channelId)
        val granted = notifier.isPermissionGranted()
        return MessageKind.entries.any { shouldNotify(it, effective, conversationOpen = open, permissionGranted = granted) }
    }

    private suspend fun kindOf(event: WsEvent.MessageCreated, entry: Pending): Pair<MessageKind, NotificationItem?> {
        if (userId in mentionedIds(event.content.orEmpty())) return MessageKind.Mention to inbox.rowOf(event.id)
        inbox.rowOf(event.id)?.let { return kindOfRow(it) to it }
        val readToWait = withTimeoutOrNull(ANNOUNCE_WINDOW) { entry.announced.await() } ?: return MessageKind.Plain to null
        withTimeoutOrNull(INBOX_READ_WAIT) { inbox.readsFinished.first { it >= readToWait } }
        val row = inbox.rowOf(event.id)
        return (row?.let(::kindOfRow) ?: MessageKind.Plain) to row
    }

    private fun kindOfRow(row: NotificationItem): MessageKind = classify(row, userId, isDm = false).toMessageKind()

    private fun isOpen(conversationId: String): Boolean = viewing.value == conversationId

    private fun track(
        conversationId: String,
        kind: ConversationKind,
        title: String,
        event: WsEvent.MessageCreated,
        row: NotificationItem?,
        counted: Boolean,
    ) {
        val conversation = conversations.getOrPut(conversationId) { Conversation(kind, title) }
        conversation.title = title
        if (conversation.tracked.any { it.messageId == event.id }) return
        val author = notificationName(directory.userName(event.authorId.orEmpty()) ?: event.externalAuthor?.name, texts.unknownUser)
        val line = NotificationLine(
            author = author,
            text = notificationText(event.content, event.attachments.isNotEmpty(), directory::userName, texts),
            at = parseInstant(event.createdAt),
        )
        // A linha da caixa tem o instante da notificação, um pouco depois do da mensagem: é o que acha a mensagem fora do que está guardado.
        val target = row?.let { NotificationTarget(event.id, it.createdAt, approximate = true) }
            ?: NotificationTarget(event.id, event.createdAt, approximate = false)
        conversation.tracked += Tracked(event.id, isoMicros(event.createdAt), line, counted, target)
        // Uma mensagem que esperou a caixa pode ser decidida depois de uma mais nova: a ordem é a do envio, não a da decisão.
        conversation.tracked.sortWith(compareBy({ it.createdUs ?: Long.MAX_VALUE }, { it.messageId }))
        if (conversation.tracked.size > MAX_TRACKED) conversation.tracked.removeAt(0)
        schedulePost(conversationId)
    }

    private fun onDeleted(event: WsEvent.MessageDeleted) {
        pending[event.id]?.deleted = true
        val conversation = conversations[event.channelId] ?: return
        if (conversation.tracked.removeAll { it.messageId == event.id }) refreshOrCancel(event.channelId, conversation)
    }

    private fun onReadMark(mark: ReadMark) {
        // Sem nada guardado desta sessão a notificação pode ser de antes (o app travou e voltou): a leitura a tira.
        val conversation = conversations[mark.channelId] ?: return cancel(mark.channelId)
        val markAt = mark.createdAt?.let(::isoMicros)?.let { MessagePosition(it, mark.messageId) }
        val changed = conversation.tracked.removeAll { item ->
            item.messageId == mark.messageId || (markAt != null && item.createdUs != null && MessagePosition(item.createdUs, item.messageId) <= markAt)
        }
        if (changed) refreshOrCancel(mark.channelId, conversation)
    }

    private fun onInbox(state: InboxState) {
        if (conversations.isEmpty()) return
        val read = state.items.filter { it.read }.mapTo(HashSet()) { it.messageId }
        if (read.isEmpty()) return
        for ((id, conversation) in conversations.entries.toList()) {
            if (conversation.tracked.removeAll { it.messageId in read }) refreshOrCancel(id, conversation)
        }
    }

    private fun refreshOrCancel(conversationId: String, conversation: Conversation) {
        if (conversation.tracked.isEmpty()) cancel(conversationId) else schedulePost(conversationId)
    }

    private fun cancel(conversationId: String) {
        conversations.remove(conversationId)?.scheduled?.cancel()
        outbox.trySend { notifier.cancelConversation(accountId, conversationId) }
    }

    /** Troca a notificação já, ou, se a última troca foi há menos de [MIN_UPDATE_INTERVAL], quando esse tempo passar. */
    private fun schedulePost(conversationId: String) {
        val conversation = conversations[conversationId] ?: return
        if (conversation.scheduled != null) return
        val wait = conversation.lastPost?.let { MIN_UPDATE_INTERVAL - it.elapsedNow() }
        if (wait == null || !wait.isPositive()) {
            enqueuePost(conversationId, conversation)
            return
        }
        conversation.scheduled = scope.launch {
            delay(wait)
            conversation.scheduled = null
            enqueuePost(conversationId, conversation)
        }
    }

    private fun enqueuePost(conversationId: String, conversation: Conversation) {
        val last = conversation.tracked.lastOrNull() ?: return
        val notification = ConversationNotification(
            accountId = accountId,
            accountLabel = accountLabel(),
            conversationId = conversationId,
            kind = conversation.kind,
            title = conversation.title,
            messages = conversation.tracked.takeLast(MAX_LINES).map { it.line },
            mentionCount = maxOf(mentionCount(conversationId), conversation.tracked.count { it.counted }),
            target = last.target,
        )
        conversation.lastPost = timeSource.markNow()
        outbox.trySend { notifier.post(notification) }
    }

    /** Uma falha do sistema ao mostrar ou tirar uma notificação não derruba a fila; nada do conteúdo vai para o registro. */
    private suspend fun runSafely(job: suspend () -> Unit) {
        try {
            job()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun InboxView.rowOf(messageId: String): NotificationItem? = state.value.items.firstOrNull { it.messageId == messageId }

    companion object {
        /** Quanto se espera o `new_notification` de uma mensagem antes de dá-la por comum. */
        val ANNOUNCE_WINDOW = 1500.milliseconds

        /** De quanto em quanto se olha se uma conversa direta ainda desconhecida já apareceu. */
        val DIRECTORY_POLL = 100.milliseconds

        /** Quanto se espera a leitura da caixa que vem depois do `new_notification` (ela começa cerca de 1 s depois dele). */
        val INBOX_READ_WAIT = 3.seconds

        /** O intervalo mínimo entre duas trocas da notificação de uma conversa. */
        val MIN_UPDATE_INTERVAL = 1.seconds

        /** Quantas mensagens a notificação mostra. */
        const val MAX_LINES = 5

        /** Quantas mensagens a sessão guarda de cada conversa para contar menções e saber quais já foram lidas. */
        private const val MAX_TRACKED = 50
    }
}
