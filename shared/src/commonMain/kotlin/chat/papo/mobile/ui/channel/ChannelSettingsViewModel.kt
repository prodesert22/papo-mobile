package chat.papo.mobile.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelEditResult
import chat.papo.mobile.chat.ChannelNameProblem
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.PermissionSnapshot
import chat.papo.mobile.chat.canManageChannels
import chat.papo.mobile.chat.channelNameProblem
import chat.papo.mobile.chat.channelTopicFits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A edição de um canal como a tela a vê. [name] e [topic] são o que está nos campos; [channel] é o canal guardado agora
 * (nulo até ser lido). Nada aqui sai do aparelho antes de [ChannelSettingsViewModel.save].
 */
data class ChannelSettingsState(
    val channel: Channel? = null,
    val name: String = "",
    val topic: String = "",
    /** A conta tem `manage_channels` com certeza ([canManageChannels]); sem isso não há o que salvar. */
    val canManage: Boolean = false,
    /** O canal existia e saiu do aparelho (apagado por outro, ou um 404 ao salvar). */
    val gone: Boolean = false,
    /** Um pedido está em andamento: os campos e o botão esperam a resposta. */
    val busy: Boolean = false,
    /** A última tentativa que não valeu; nada é repetido sozinho. */
    val failure: ChannelEditResult? = null,
    /** O servidor confirmou: a tela fecha. */
    val saved: Boolean = false,
) {
    /** Categoria não tem descrição. */
    val hasTopic: Boolean get() = channel != null && channel.type != ChannelType.Category

    val nameProblem: ChannelNameProblem? get() = channelNameProblem(name)
    val topicFits: Boolean get() = !hasTopic || channelTopicFits(topic)

    /** A descrição que vai no pedido: nula (fica como está) se não mudou ou se o canal não a tem; `""` a apaga. */
    val topicToSend: String?
        get() {
            val current = channel ?: return null
            val typed = topic.trim()
            return if (!hasTopic || typed == current.topic.orEmpty()) null else typed
        }

    val changed: Boolean get() = channel != null && (name.trim() != channel.name || topicToSend != null)

    val editable: Boolean get() = channel != null && canManage && !gone && !busy && !saved

    val canSave: Boolean get() = editable && nameProblem == null && topicFits && changed
}

/**
 * Nome e descrição de um canal. Os campos começam com o que está guardado e só vão ao servidor quando a pessoa salva,
 * uma mudança por vez. Perder a permissão com a tela aberta tira o botão de salvar.
 */
class ChannelSettingsViewModel(
    private val chat: ChatStore,
    private val me: String,
    private val channelId: String,
) : ViewModel() {
    /** O que só esta tela sabe: o texto dos campos e o andamento do pedido. */
    private data class Form(
        val name: String = "",
        val topic: String = "",
        val loaded: Boolean = false,
        val gone: Boolean = false,
        val busy: Boolean = false,
        val failure: ChannelEditResult? = null,
        val saved: Boolean = false,
    )

    private val form = MutableStateFlow(Form())

    init {
        adopt(chat.channels.value)
    }

    val state: StateFlow<ChannelSettingsState> =
        combine(form, chat.channels, chat.access, ::compose)
            .stateIn(viewModelScope, SharingStarted.Eagerly, compose(form.value, chat.channels.value, chat.access.value))

    init {
        viewModelScope.launch { chat.channels.collect(::adopt) }
    }

    /**
     * Copia o canal para os campos na primeira vez que ele aparece (o banco pode ainda não ter sido lido quando a tela
     * reabre); se ele some depois de lido, marca que acabou. Depois disso o que a pessoa digitou não é tocado.
     */
    private fun adopt(channels: List<Channel>) {
        val channel = channels.firstOrNull { it.id == channelId }
        form.update { f ->
            when {
                channel != null && !f.loaded -> f.copy(name = channel.name, topic = channel.topic.orEmpty(), loaded = true)
                channel == null && f.loaded -> f.copy(gone = true)
                else -> f
            }
        }
    }

    private fun compose(form: Form, channels: List<Channel>, access: PermissionSnapshot) = ChannelSettingsState(
        channel = channels.firstOrNull { it.id == channelId },
        name = form.name,
        topic = form.topic,
        canManage = canManageChannels(access, me),
        gone = form.gone,
        busy = form.busy,
        failure = form.failure,
        saved = form.saved,
    )

    fun setName(value: String) = edit { it.copy(name = value, failure = null) }

    fun setTopic(value: String) = edit { it.copy(topic = value, failure = null) }

    private fun edit(change: (Form) -> Form) = form.update { if (it.busy || it.saved) it else change(it) }

    /** Envia o que mudou. Não faz nada sem permissão, com campo inválido, sem mudança ou com outro pedido em andamento. */
    fun save() {
        val current = compose(form.value, chat.channels.value, chat.access.value)
        if (!current.canSave) return
        // Marca antes de lançar: um segundo toque no mesmo quadro já encontra `busy` e não manda um segundo pedido.
        form.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            val result = chat.updateChannel(channelId, current.name.trim(), current.topicToSend)
            form.update {
                it.copy(
                    busy = false,
                    failure = result.takeUnless { r -> r == ChannelEditResult.Saved },
                    saved = result == ChannelEditResult.Saved,
                )
            }
        }
    }
}
