package chat.papo.mobile.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Draft
import chat.papo.mobile.chat.Located
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.PendingMessage
import chat.papo.mobile.chat.encodeMentions
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** A mensagem pedida ao abrir não existe mais na conversa. */
    val targetMissing: Boolean = false,
    /** Lendo um trecho antigo: carregando o que vem depois dele. */
    val loadingNewer: Boolean = false,
    /** Voltando do trecho antigo para as mensagens recentes. */
    val returning: Boolean = false,
    /** Muda a cada volta às mensagens recentes, para a tela rolar até o fim. */
    val latestTick: Int = 0,
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

    /** Nomes escolhidos no autocompletar deste rascunho, para virar menção no envio. */
    private val pickedMentions = HashMap<String, String>()

    init {
        chat.setViewing(channelId)
        viewModelScope.launch {
            attempt { chat.refreshLatest(channelId) }
            _state.update { it.copy(loadedOnce = true, reachedStart = !chat.hasOlder(channelId)) }
            target?.let { locate(it) }
        }
        viewModelScope.launch { runCatching { chat.loadPinned(channelId) } }
    }

    private suspend fun locate(target: MessageTarget) {
        var located: Located? = null
        attempt { located = chat.locate(channelId, target.id, target.createdAtIso) }
        when (val result = located) {
            is Located.Found -> {
                // A janela da tela precisa alcançar a mensagem e sobrar contexto antes dela.
                limit.update { maxOf(it, result.position + PAGE) }
                _state.update { it.copy(locating = false, focusId = target.id) }
            }
            Located.Missing -> _state.update { it.copy(locating = false, targetMissing = true) }
            null -> _state.update { it.copy(locating = false) }
        }
    }

    /** Lendo um trecho antigo, a rolagem chegou perto do fim dele: carrega o que vem depois. */
    fun loadNewer() {
        val s = _state.value
        if (s.loadingNewer || s.returning || s.locating || s.focusId != null || channelId !in chat.detached.value) return
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
        if (_state.value.returning) return
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

    fun clearFocus() = _state.update { it.copy(focusId = null) }

    fun dismissTargetMissing() = _state.update { it.copy(targetMissing = false) }

    fun loadOlder() {
        val s = _state.value
        // Enquanto a mensagem pedida é procurada, é a procura que pagina o histórico.
        if (s.loadingOlder || s.reachedStart || !s.loadedOnce || s.locating) return
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

    fun addFiles(files: List<OutgoingFile>) = _state.update { it.copy(files = (it.files + files).take(MAX_FILES)) }

    fun removeFile(file: OutgoingFile) = _state.update { it.copy(files = it.files - file) }

    fun startReply(message: Message) {
        if (_state.value.editing != null) finishEdit()
        _state.update { it.copy(replyTo = message) }
        saveDraft()
    }

    fun startEdit(message: Message) {
        // Editar uma segunda mensagem em seguida volta ao mesmo rascunho de antes, não ao texto da primeira.
        if (stashed == null) stashed = StashedDraft(draft.text, _state.value.replyTo)
        val shown = renderMentions(message.content) { chat.users.value[it]?.username }
        draft = TextFieldValue(shown, TextRange(shown.length))
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

    // Rascunho

    /** O que está escrito no compositor. Mora aqui, e não na tela, para sobreviver a sair da conversa e voltar. */
    var draft by mutableStateOf(TextFieldValue(""))
        private set

    /** Texto e resposta de antes de uma edição, para voltar a eles quando ela acabar. Enquanto existe, nada é guardado. */
    private var stashed: StashedDraft? = null

    /** O usuário já mexeu no texto: o que o banco devolver não pode sobrescrevê-lo. */
    private var textChanged = false

    private class StashedDraft(val text: String, val replyTo: Message?)

    init {
        viewModelScope.launch { restoreDraft() }
    }

    /** O compositor mudou (digitação, emoji, menção escolhida): guarda o rascunho e avisa que está digitando. */
    fun onDraftChange(value: TextFieldValue) {
        val changed = value.text != draft.text
        draft = value
        if (!changed) return
        textChanged = true
        if (value.text.isNotEmpty()) onTyping()
        saveDraft()
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
        if (!textChanged) draft = TextFieldValue(saved.text, TextRange(saved.text.length))
        if (_state.value.replyTo == null) _state.update { it.copy(replyTo = reply) }
    }

    /** A edição acabou, salva ou cancelada: o compositor volta ao que havia antes dela. */
    private fun finishEdit() {
        val before = stashed
        stashed = null
        val text = before?.text.orEmpty()
        draft = TextFieldValue(text, TextRange(text.length))
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
        val content = encodeMentions(text, pickedMentions, chat.users.value.values)
        val editing = s.editing
        if (editing == null) {
            chat.enqueue(channelId, content, s.replyTo?.id, s.files)
            pickedMentions.clear()
            _state.update { it.copy(replyTo = null, files = emptyList(), error = null) }
            draft = TextFieldValue("")
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

    fun delete(message: Message) = launch { chat.delete(message.id) }

    fun react(message: Message, emojiId: String?, unicode: String?) = launch { chat.toggleReaction(message, emojiId, unicode) }

    fun setPinned(message: Message, pinned: Boolean) = launch { chat.setPinned(channelId, message.id, pinned) }

    fun markRead(message: Message) {
        viewModelScope.launch { runCatching { chat.markRead(channelId, message.id) } }
    }

    fun showPinned() = launch { _state.update { it.copy(pinnedList = chat.loadPinned(channelId)) } }

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
        _state.update { it.copy(error = e.toUiError()) }
        false
    }

    private companion object {
        const val PAGE = 60L
        const val MAX_FILES = 10
    }
}
