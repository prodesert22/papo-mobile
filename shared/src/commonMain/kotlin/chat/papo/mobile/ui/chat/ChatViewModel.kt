package chat.papo.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Located
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.encodeMentions
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Mensagem que a conversa deve mostrar ao abrir, como um resultado de busca. */
data class MessageTarget(val id: String, val createdAtIso: String? = null)

/** Por que a mensagem pedida ao abrir não pôde ser mostrada. */
enum class FocusProblem { Missing, TooOld }

data class ChatUiState(
    val replyTo: Message? = null,
    val editing: Message? = null,
    val files: List<OutgoingFile> = emptyList(),
    val sending: Boolean = false,
    val loadingOlder: Boolean = false,
    val reachedStart: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: UiError? = null,
    val pinnedList: List<Message>? = null,
    /** Procurando no histórico a mensagem pedida ao abrir. */
    val locating: Boolean = false,
    /** Mensagem pedida ao abrir, já ao alcance da lista: a tela rola até ela e a destaca. */
    val focusId: String? = null,
    val focusProblem: FocusProblem? = null,
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
            Located.Missing -> _state.update { it.copy(locating = false, focusProblem = FocusProblem.Missing) }
            Located.TooOld -> _state.update { it.copy(locating = false, focusProblem = FocusProblem.TooOld) }
            null -> _state.update { it.copy(locating = false) }
        }
    }

    /** A tela desta conversa voltou a ser a visível. */
    fun onVisible() = chat.setViewing(channelId)

    fun clearFocus() = _state.update { it.copy(focusId = null) }

    fun dismissFocusProblem() = _state.update { it.copy(focusProblem = null) }

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

    fun startReply(message: Message) = _state.update { it.copy(replyTo = message, editing = null) }

    fun startEdit(message: Message) = _state.update { it.copy(editing = message, replyTo = null, files = emptyList()) }

    fun cancelContext() = _state.update { it.copy(replyTo = null, editing = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Envia (ou salva a edição). Chama [onSent] quando o rascunho pode ser limpo. */
    fun submit(text: String, onSent: () -> Unit) {
        val s = _state.value
        if (s.sending || (text.isBlank() && s.files.isEmpty())) return
        val content = encodeMentions(text, pickedMentions)
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val ok = attempt {
                val editing = s.editing
                if (editing != null) chat.edit(editing.id, content) else chat.send(channelId, content, s.replyTo?.id, s.files)
            }
            if (ok) {
                pickedMentions.clear()
                _state.update { it.copy(replyTo = null, editing = null, files = emptyList()) }
                onSent()
            }
            _state.update { it.copy(sending = false) }
        }
    }

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
