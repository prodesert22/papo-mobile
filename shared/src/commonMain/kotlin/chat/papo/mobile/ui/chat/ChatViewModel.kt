package chat.papo.mobile.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.coerceIn
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.AttachmentProblem
import chat.papo.mobile.chat.ConversationAccess
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.Draft
import chat.papo.mobile.chat.EmojiPickerModel
import chat.papo.mobile.chat.Located
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.PendingMessage
import chat.papo.mobile.chat.PermissionSnapshot
import chat.papo.mobile.chat.checkLimits
import chat.papo.mobile.chat.conversationAccess
import chat.papo.mobile.chat.encodeMentions
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Mensagem que a conversa deve mostrar ao abrir, como um resultado de busca, com o instante dela dado pelo servidor. */
data class MessageTarget(val id: String, val createdAtIso: String)

data class ChatUiState(
    val replyTo: Message? = null,
    val editing: Message? = null,
    val files: List<OutgoingFile> = emptyList(),
    /** Por que o último lote de arquivos escolhidos não entrou por inteiro; some quando o usuário fecha o aviso ou escolhe de novo. */
    val attachProblem: AttachmentProblem? = null,
    /** Salvando a edição de uma mensagem (editar segue síncrono; enviar não trava o compositor). */
    val savingEdit: Boolean = false,
    val loadingOlder: Boolean = false,
    val reachedStart: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: UiError? = null,
    val pinnedList: List<Message>? = null,
    /** Procurando no histórico a mensagem pedida ao abrir. */
    val locating: Boolean = false,
    /** Mensagem pedida ao abrir, já ao alcance da lista: a tela rola até ela e a destaca. */
    val focusId: String? = null,
    /** A mensagem procurada (pedida ao abrir ou original de uma resposta) não existe mais na conversa. */
    val targetMissing: Boolean = false,
    /** Lendo um trecho antigo: carregando o que vem depois dele. */
    val loadingNewer: Boolean = false,
    /** Voltando do trecho antigo para as mensagens recentes. */
    val returning: Boolean = false,
    /** Muda a cada volta às mensagens recentes, para a tela rolar até o fim. */
    val latestTick: Int = 0,
    /** Ids de mensagens que o servidor não tem mais: a prévia de quem as respondeu avisa e deixa de ser tocável. */
    val missingReplies: Set<String> = emptySet(),
    /** Id da original que a procura não alcançou no limite de páginas: a tela oferece continuar de onde parou. */
    val replyNotReached: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val session: AccountSession,
    val channelId: String,
    private val target: MessageTarget? = null,
) : ViewModel() {
    private val chat = session.chat
    private val limit = MutableStateFlow(PAGE)

    val messages: StateFlow<List<Message>> = limit
        .flatMapLatest { chat.messages(channelId, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** O que o usuário mandou nesta conversa e o servidor ainda não confirmou, do mais antigo ao mais novo. */
    val pending: StateFlow<List<PendingMessage>> = chat.outbox
        .map { all -> all.filter { it.channelId == channelId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, chat.outbox.value.filter { it.channelId == channelId })

    private val _state = MutableStateFlow(ChatUiState(locating = target != null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /**
     * O que a conta pode fazer nesta conversa (canal ou conversa direta), segundo as permissões que a sessão conhece.
     * Já nasce calculado com o que há agora; sem dados as respostas são "desconhecido", que oferece tudo.
     */
    val access: StateFlow<ConversationAccess> = combine(chat.access, chat.dms, ::accessTo)
        .stateIn(viewModelScope, SharingStarted.Eagerly, accessTo(chat.access.value, chat.dms.value))

    /** Nomes escolhidos no autocompletar deste rascunho, para virar menção no envio. */
    private val pickedMentions = HashMap<String, String>()

    init {
        chat.setViewing(channelId)
        var located = false
        whileReadable {
            coroutineScope {
                this@coroutineScope.launch { quietly { chat.loadPinned(channelId) } }
                attempt { chat.refreshLatest(channelId) }
                _state.update { it.copy(loadedOnce = true, reachedStart = !chat.hasOlder(channelId)) }
                // A mensagem pedida só se procura uma vez: se a leitura se perder no meio, a procura recomeça quando voltar.
                if (!located) {
                    target?.let { locate(it) }
                    located = true
                }
            }
        }
    }

    /** Mensagens com uma reação em voo; só a thread principal mexe nele. */
    private val reacting = HashSet<String>()

    init {
        // O servidor avisa quando uma mensagem é apagada: quem a respondeu passa a mostrar isso sem percorrer o histórico.
        viewModelScope.launch {
            session.ws.events.collect { if (it is WsEvent.MessageDeleted) onMessageDeleted(it) }
        }
    }

    private suspend fun locate(target: MessageTarget) {
        var located: Located? = null
        attempt { located = chat.locate(channelId, target.id, target.createdAtIso) }
        settle(target.id, located)
    }

    /** Aplica o resultado de uma procura: rola até a mensagem, avisa que sumiu ou que a procura parou. [located] nulo é falha (já no erro). */
    private fun settle(id: String, located: Located?) {
        when (located) {
            is Located.Found -> {
                // A janela da tela precisa alcançar a mensagem e sobrar contexto antes dela.
                limit.update { maxOf(it, located.position + PAGE) }
                _state.update { it.copy(locating = false, focusId = id) }
            }
            Located.Missing -> _state.update {
                it.copy(locating = false, targetMissing = true, missingReplies = it.missingReplies.plusBounded(id))
            }
            Located.NotReached -> _state.update { it.copy(locating = false, replyNotReached = id) }
            null -> _state.update { it.copy(locating = false) }
        }
    }

    /** Leva a tela à mensagem que [message] responde, procurando para trás no histórico se ela não está carregada. */
    fun openReply(message: Message) {
        searchOriginal(message.replyTo ?: return)
    }

    /** Segue a procura que parou no limite de páginas, a partir da mensagem mais antiga já carregada. */
    fun continueReplySearch() {
        searchOriginal(_state.value.replyNotReached ?: return)
    }

    fun dismissReplyNotReached() = _state.update { it.copy(replyNotReached = null) }

    /**
     * Leva a tela à mensagem [target] desta conversa (a barra de áudio), procurando-a no histórico se ela não está carregada.
     * Uma procura por vez: com outra em andamento, ou sem acesso à leitura, nada acontece.
     */
    fun showMessage(target: MessageTarget) {
        val s = _state.value
        if (s.locating || s.returning || readDenied) return
        _state.update { it.copy(locating = true, replyNotReached = null, focusId = null) }
        viewModelScope.launch { locate(target) }
    }

    private fun searchOriginal(id: String) {
        val s = _state.value
        // Uma procura por vez, e a original já dada como apagada não se procura de novo.
        if (s.locating || s.returning || id in s.missingReplies || readDenied) return
        _state.update { it.copy(locating = true, replyNotReached = null, focusId = null) }
        viewModelScope.launch {
            var located: Located? = null
            attempt { located = chat.locateOlder(channelId, id) }
            settle(id, located)
        }
    }

    internal fun onMessageDeleted(event: WsEvent.MessageDeleted) {
        if (event.channelId == channelId) noteMissing(event.id)
    }

    private fun noteMissing(id: String) = _state.update { it.copy(missingReplies = it.missingReplies.plusBounded(id)) }

    /** Duplo toque: alterna o 👍 padrão na mensagem. Ignorado enquanto a reação anterior dela ainda está em voo. */
    fun quickReact(message: Message) {
        if (!reacting.add(message.id)) return
        viewModelScope.launch {
            try {
                // A linha da tela pode estar um passo atrás do banco: decide o "já reagi" pelo que está guardado.
                val current = messages.value.firstOrNull { it.id == message.id } ?: message
                attempt { chat.toggleReaction(current, null, EmojiPickerModel.DEFAULT_REACTIONS.first()) }
            } finally {
                reacting.remove(message.id)
            }
        }
    }

    /** Lendo um trecho antigo, a rolagem chegou perto do fim dele: carrega o que vem depois. */
    fun loadNewer() {
        val s = _state.value
        if (s.loadingNewer || s.returning || s.locating || s.focusId != null || channelId !in chat.detached.value || readDenied) return
        _state.update { it.copy(loadingNewer = true) }
        viewModelScope.launch {
            var added = 0
            attempt { added = chat.loadNewer(channelId) }
            // A janela da tela conta a partir da mais nova: cresce junto, para o que estava à vista não sair dela.
            limit.update { it + added }
            _state.update { it.copy(loadingNewer = false) }
        }
    }

    /** Deixa o trecho antigo e volta para as mensagens recentes. */
    fun goToLatest() {
        if (_state.value.returning || readDenied) return
        _state.update { it.copy(returning = true, focusId = null) }
        viewModelScope.launch {
            if (attempt { chat.refreshLatest(channelId) }) {
                limit.value = PAGE
                _state.update { it.copy(reachedStart = !chat.hasOlder(channelId), latestTick = it.latestTick + 1) }
            }
            _state.update { it.copy(returning = false) }
        }
    }

    /** A tela desta conversa voltou a ser a visível. */
    fun onVisible() = chat.setViewing(channelId)

    /**
     * Termina o destaque. Com [id], só vale se o destaque ainda é dessa mensagem: a tela chama isto ao cancelar o efeito
     * do destaque anterior, e sem a conferência esse aviso atrasado apagaria o destaque que acabou de começar.
     */
    fun clearFocus(id: String? = null) = _state.update { if (id == null || it.focusId == id) it.copy(focusId = null) else it }

    fun dismissTargetMissing() = _state.update { it.copy(targetMissing = false) }

    fun loadOlder() {
        val s = _state.value
        // Enquanto a mensagem pedida é procurada, é a procura que pagina o histórico.
        if (s.loadingOlder || s.reachedStart || !s.loadedOnce || s.locating || readDenied) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            // Primeiro mostra o que já está guardado; só vai ao servidor quando acaba.
            if (messages.value.size < limit.value) attempt { chat.loadOlder(channelId) }
            limit.value += PAGE
            _state.update { it.copy(loadingOlder = false, reachedStart = !chat.hasOlder(channelId) && messages.value.size < limit.value - PAGE) }
        }
    }

    fun onTyping() {
        viewModelScope.launch { runCatching { chat.notifyTyping(channelId) } }
    }

    fun pickMention(username: String, userId: String) {
        pickedMentions[username] = userId
    }

    /**
     * Põe os arquivos escolhidos no compositor, um a um e na ordem: o que respeita os limites de uma mensagem entra, o
     * que não respeita fica de fora e o primeiro motivo vira [ChatUiState.attachProblem]. O texto e o que já estava
     * anexado ficam como estavam.
     */
    fun addFiles(picked: List<OutgoingFile>) = _state.update { state ->
        var accepted = state.files
        var problem: AttachmentProblem? = null
        for (file in picked) {
            val candidate = accepted + file
            val refused = checkLimits(candidate)
            if (refused == null) accepted = candidate else if (problem == null) problem = refused
        }
        state.copy(files = accepted, attachProblem = problem)
    }

    fun removeFile(file: OutgoingFile) = _state.update { it.copy(files = it.files - file) }

    fun dismissAttachProblem() = _state.update { it.copy(attachProblem = null) }

    fun startReply(message: Message) {
        if (_state.value.editing != null) finishEdit()
        _state.update { it.copy(replyTo = message) }
        saveDraft()
    }

    fun startEdit(message: Message) {
        // Editar uma segunda mensagem em seguida volta ao mesmo rascunho de antes, não ao texto da primeira.
        if (stashed == null) stashed = StashedDraft(draft.text, _state.value.replyTo)
        val shown = renderMentions(message.content) { chat.users.value[it]?.username }
        replaceDraft(shown)
        _state.update { it.copy(editing = message, replyTo = null, files = emptyList()) }
    }

    fun cancelContext() {
        if (_state.value.editing != null) {
            finishEdit()
        } else {
            _state.update { it.copy(replyTo = null) }
            saveDraft()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // Permissões

    private fun accessTo(snapshot: PermissionSnapshot, dms: List<Dm>) =
        conversationAccess(snapshot, session.userId, channelId, isDm = dms.any { it.id == channelId })

    /** A leitura da conversa foi negada com certeza: nada dela se pede ao servidor, e o que estava guardado já foi apagado. */
    private val readDenied get() = access.value.readDenied

    /**
     * Roda [block] quando a leitura da conversa não está negada, ao abrir e de novo a cada vez que ela volta. Se for
     * negada, o [block] em andamento é cancelado e as fixadas que a tela mantinha abertas se descartam; o rascunho fica.
     */
    private fun whileReadable(block: suspend () -> Unit) {
        viewModelScope.launch {
            access.map { it.readDenied }.distinctUntilChanged().collectLatest { denied ->
                if (denied) hidePinned() else block()
            }
        }
    }

    /** Trabalho que pode falhar sem que ninguém precise saber; o cancelamento segue adiante. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // Rascunho

    /**
     * O campo do compositor: o texto, o cursor e a composição do teclado. Mora aqui, e não na tela, para sobreviver a sair
     * da conversa e voltar. A digitação o muda direto; [draftEdited] avisa o que isso significa para o rascunho.
     */
    val draftState = TextFieldState()

    /** O que está escrito no compositor, com o cursor. */
    val draft: TextFieldValue get() = TextFieldValue(draftState.text.toString(), draftState.selection)

    /** O texto do compositor que o rascunho guardado e o aviso de "digitando" já levaram em conta. */
    private var settledText = ""

    /** Texto e resposta de antes de uma edição, para voltar a eles quando ela acabar. Enquanto existe, nada é guardado. */
    private var stashed: StashedDraft? = null

    /** O usuário já mexeu no texto: o que o banco devolver não pode sobrescrevê-lo. */
    private var textChanged = false

    private class StashedDraft(val text: String, val replyTo: Message?)

    init {
        viewModelScope.launch { restoreDraft() }
    }

    /** O compositor foi mudado por quem não é o teclado (emoji, menção escolhida): põe [value] nele e guarda o rascunho. */
    fun onDraftChange(value: TextFieldValue) {
        writeDraft(value)
        draftEdited()
    }

    /**
     * O texto do compositor mudou (digitação, emoji, menção escolhida): guarda o rascunho e avisa que está digitando. Sem
     * mudança no texto (só o cursor andou) não faz nada, e pode ser chamada mais de uma vez para a mesma mudança.
     */
    fun draftEdited() {
        val text = draft.text
        if (text == settledText) return
        settledText = text
        textChanged = true
        if (text.isNotEmpty()) onTyping()
        saveDraft()
    }

    /** Põe [value] no campo; só troca o texto se ele mudou, para não interromper a composição do teclado à toa. */
    private fun writeDraft(value: TextFieldValue) {
        draftState.edit {
            if (toString() != value.text) replace(0, length, value.text)
            selection = value.selection.coerceIn(0, length)
        }
    }

    /** O texto veio de dentro (restaurar, editar, voltar de uma edição, enviar): é o novo ponto de partida, não digitação. */
    private fun replaceDraft(text: String) {
        writeDraft(TextFieldValue(text, TextRange(text.length)))
        settledText = text
    }

    /** Guarda o compositor como rascunho da conversa; durante uma edição o que vale é o de antes dela. */
    private fun saveDraft() {
        if (stashed != null) return
        session.drafts.save(channelId, Draft(draft.text, _state.value.replyTo?.id))
    }

    /** Lê o rascunho guardado e o põe no compositor, sem pisar no que o usuário já escreveu enquanto o banco respondia. */
    private suspend fun restoreDraft() {
        val (saved, reply) = try {
            val saved = session.drafts.load(channelId) ?: return
            saved to saved.replyTo?.let { chat.storedMessage(channelId, it) } // null se a mensagem sumiu
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // sem ler o banco a conversa abre vazia, como se não houvesse rascunho
        }
        if (stashed != null) return
        // Digitação que o campo ainda não contou (o aviso vem no quadro seguinte) também vale como "já mexeu".
        if (!textChanged && draft.text == settledText) replaceDraft(saved.text)
        if (_state.value.replyTo == null) _state.update { it.copy(replyTo = reply) }
    }

    /** A edição acabou, salva ou cancelada: o compositor volta ao que havia antes dela. */
    private fun finishEdit() {
        val before = stashed
        stashed = null
        replaceDraft(before?.text.orEmpty())
        _state.update { it.copy(editing = null, replyTo = before?.replyTo) }
    }

    /**
     * Manda o texto do compositor para a fila de envio, que o mostra na hora, ou salva a edição. Ao
     * enfileirar o compositor e o rascunho guardado são limpos de imediato (se o app fechasse antes de
     * gravar, o texto já enviado voltaria como rascunho); ao editar, só depois de salvar.
     */
    fun submit() {
        val s = _state.value
        val text = draft.text
        if (s.savingEdit || (text.isBlank() && s.files.isEmpty())) return
        // Arquivo que o provedor já perdeu não vai: o usuário o tira antes, e o que escreveu fica onde está.
        if (s.files.any { it.unavailable }) return
        // Sem permissão de envio só se edita: o servidor deixa o autor editar a própria mensagem sem olhar o envio.
        if (s.editing == null && !access.value.send) return
        val content = encodeMentions(text, pickedMentions, chat.users.value.values)
        val editing = s.editing
        if (editing == null) {
            chat.enqueue(channelId, content, s.replyTo?.id, s.files)
            pickedMentions.clear()
            _state.update { it.copy(replyTo = null, files = emptyList(), attachProblem = null, error = null) }
            replaceDraft("")
            session.drafts.save(channelId, null)
            session.drafts.flush()
            return
        }
        _state.update { it.copy(savingEdit = true, error = null) }
        viewModelScope.launch {
            val ok = attempt { chat.edit(editing.id, content) }
            if (ok) {
                pickedMentions.clear()
                finishEdit()
            }
            _state.update { it.copy(savingEdit = false) }
        }
    }

    /** Manda de novo uma mensagem que falhou; a incerta só vai com [confirmedDuplicate]. */
    fun retry(localId: String, confirmedDuplicate: Boolean = false) {
        chat.retry(localId, confirmedDuplicate)
    }

    /** Descarta uma mensagem da fila; se estava saindo, cancela o envio. */
    fun withdraw(localId: String) = chat.withdraw(localId)

    fun delete(message: Message) = launch {
        chat.delete(message.id)
        noteMissing(message.id)
    }

    fun react(message: Message, emojiId: String?, unicode: String?) = launch { chat.toggleReaction(message, emojiId, unicode) }

    fun setPinned(message: Message, pinned: Boolean) = launch { chat.setPinned(channelId, message.id, pinned) }

    fun markRead(message: Message) {
        if (readDenied) return
        viewModelScope.launch { runCatching { chat.markRead(channelId, message.id) } }
    }

    fun showPinned() {
        if (readDenied) return
        launch { _state.update { it.copy(pinnedList = chat.loadPinned(channelId)) } }
    }

    fun hidePinned() = _state.update { it.copy(pinnedList = null) }

    override fun onCleared() {
        chat.setViewing(null)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { attempt(block) }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Throwable) {
        // O servidor recusou: o que o app sabia das permissões pode estar velho. A loja já pede nas próprias chamadas e
        // limita a uma releitura a cada poucos segundos, então aqui não sai uma segunda.
        if (e is ApiException && e.isForbidden) chat.requestRefresh()
        _state.update { it.copy(error = e.toUiError()) }
        false
    }

    private companion object {
        const val PAGE = 60L
    }
}

private const val MAX_MISSING_REPLIES = 200

/** Acrescenta [id] sem passar de [max]: o que foi dado como apagado há mais tempo é esquecido, e uma nova procura confirma de novo. */
internal fun Set<String>.plusBounded(id: String, max: Int = MAX_MISSING_REPLIES): Set<String> {
    val all = this - id + id
    return if (all.size > max) all.drop(all.size - max).toSet() else all
}
