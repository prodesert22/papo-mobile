package chat.papo.mobile.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.UserProfile
import chat.papo.mobile.chat.avatarMediaName
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.core.runMutation
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.UpdateAvatarRequest
import chat.papo.mobile.data.api.UpdateBannerRequest
import chat.papo.mobile.data.api.UpdateProfileRequest
import chat.papo.mobile.media.ImageReducer
import chat.papo.mobile.media.ImageReduction
import chat.papo.mobile.media.ImageRefusal
import chat.papo.mobile.media.PREVIEW_MAX_BYTES
import chat.papo.mobile.media.ProfileDraft
import chat.papo.mobile.media.ProfileField
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.ReducedImage
import chat.papo.mobile.media.formatFor
import chat.papo.mobile.media.profileDraftErrors
import chat.papo.mobile.media.readAtMost
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

/** Os pedidos que "Salvar" pode mandar, na ordem em que saem. */
enum class SaveStep { Text, Avatar, Banner }

/** O que aconteceu com um pedido de "Salvar". */
sealed interface StepOutcome {
    /** Está saindo agora. */
    data object Sending : StepOutcome

    data object Saved : StepOutcome

    /** O pedido saiu e não deu certo; o [result] diz como (rejeitado, limite de taxa, sem conexão, incerto). */
    data class Failed(val result: MutationResult) : StepOutcome

    /** Não saiu: um pedido antes dele falhou de um jeito que pede parar (limite de taxa, sem conexão, incerto, sessão). */
    data object Skipped : StepOutcome
}

/** Uma imagem pronta para enviar: [format] é o valor de `avatar_format` ou `banner_format` que o servidor espera. */
class PickedImage(val image: ReducedImage, val format: String)

/** O que fazer com o banner ao salvar. */
sealed interface BannerChange {
    data object Keep : BannerChange

    data class Replace(val picked: PickedImage) : BannerChange

    data object Remove : BannerChange
}

sealed interface EditLoad {
    data object Loading : EditLoad

    /** O perfil lido do servidor: o ponto de partida do que o usuário muda. */
    data class Ready(val profile: UserProfile) : EditLoad

    data class Failed(val error: UiError) : EditLoad
}

data class EditProfileState(
    val load: EditLoad = EditLoad.Loading,
    val draft: ProfileDraft = ProfileDraft("", "", "", ""),
    /** Os textos como o servidor os tem; o que difere deles em [draft] é o que "Salvar" manda. */
    val saved: ProfileDraft = ProfileDraft("", "", "", ""),
    val avatar: PickedImage? = null,
    val banner: BannerChange = BannerChange.Keep,
    /** O avatar ou o banner escolhido está sendo preparado. */
    val reducing: ProfileImageKind? = null,
    /** Por que a última imagem escolhida de cada tipo não serviu. */
    val refusals: Map<ProfileImageKind, ImageRefusal> = emptyMap(),
    val saving: Boolean = false,
    val outcomes: Map<SaveStep, StepOutcome> = emptyMap(),
) {
    val ready: Boolean get() = load is EditLoad.Ready
    val overLimit: List<ProfileField> get() = profileDraftErrors(draft)
    val textChanged: Boolean get() = draft.trimmed() != saved
    val hasBanner: Boolean get() = (load as? EditLoad.Ready)?.profile?.bannerSha != null

    /** Há o que salvar, tudo cabe nos limites e nada está em andamento. */
    val canSave: Boolean
        get() = ready && !saving && reducing == null && overLimit.isEmpty() &&
            (textChanged || avatar != null || banner != BannerChange.Keep)
}

/**
 * A edição do próprio perfil na conta de [session]. Parte do perfil lido agora do servidor (descrição e banner não têm
 * evento, então o guardado pode estar velho). "Salvar" manda só o que mudou, em pedidos separados, na ordem texto,
 * avatar e banner, e guarda o resultado de cada um. Nada se repete sozinho: depois de uma falha que pede parar (limite de
 * taxa, sem conexão, resposta incerta, sessão) os pedidos seguintes nem saem, e quem decide tentar de novo é o usuário.
 * Depois de uma resposta incerta, [reload] lê o perfil (um GET) para ver o que o servidor guardou.
 */
class EditProfileViewModel(
    private val session: AccountSession,
    private val reducer: ImageReducer,
    /** Onde o arquivo escolhido é lido: o provedor do sistema bloqueia a thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val me = session.userId
    private val _state = MutableStateFlow(EditProfileState())
    val state: StateFlow<EditProfileState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var pickJob: Job? = null

    init {
        load()
    }

    /** Lê o perfil de novo do servidor e recomeça os textos nele; o avatar e o banner escolhidos ficam. */
    fun reload() {
        if (!_state.value.saving) load()
    }

    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(load = EditLoad.Loading, outcomes = emptyMap()) }
        loadJob = viewModelScope.launch {
            val next = try {
                EditLoad.Ready(session.profiles.get(me, refresh = true))
            } catch (e: Throwable) {
                // toUiError relança o cancelamento: sair da tela ou recarregar não vira erro.
                EditLoad.Failed(e.toUiError())
            }
            _state.update { state ->
                if (next is EditLoad.Ready) state.copy(load = next, draft = next.profile.toDraft(), saved = next.profile.toDraft()) else state.copy(load = next)
            }
        }
    }

    fun edit(field: ProfileField, text: String) {
        if (_state.value.saving) return
        _state.update { state ->
            val draft = when (field) {
                ProfileField.Nickname -> state.draft.copy(nickname = text)
                ProfileField.StatusMessage -> state.draft.copy(statusMessage = text)
                ProfileField.Description -> state.draft.copy(description = text)
                ProfileField.Typing -> state.draft.copy(typing = text)
            }
            state.copy(draft = draft, outcomes = state.outcomes - SaveStep.Text)
        }
    }

    /**
     * Prepara a imagem [file] como avatar ou banner (lê, recorta, reduz) e a deixa à espera do "Salvar". Se não serve, o
     * motivo fica em [EditProfileState.refusals]. Escolher outra enquanto uma é preparada troca a anterior.
     */
    fun pick(kind: ProfileImageKind, file: OutgoingFile) {
        if (_state.value.saving) return
        pickJob?.cancel()
        _state.update { it.copy(reducing = kind, refusals = it.refusals - kind) }
        pickJob = viewModelScope.launch {
            val reduction = try {
                val bytes = withContext(io) { file.readAtMost(PREVIEW_MAX_BYTES) }
                if (bytes == null) ImageReduction.Refused(ImageRefusal.SourceTooLarge) else reducer.reduce(bytes, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImageReduction.Refused(ImageRefusal.Unreadable)
            }
            _state.update { state ->
                val done = state.copy(reducing = null)
                when (reduction) {
                    is ImageReduction.Refused -> done.copy(refusals = state.refusals + (kind to reduction.reason))
                    is ImageReduction.Ready -> {
                        // O redutor só devolve formatos que o servidor aceita; se algum dia não for, a imagem é recusada aqui.
                        val format = formatFor(reduction.image.mimeType)
                        val picked = format?.let { PickedImage(reduction.image, it) }
                        when {
                            picked == null -> done.copy(refusals = state.refusals + (kind to ImageRefusal.NotAnImage))
                            kind == ProfileImageKind.Avatar -> done.copy(avatar = picked, outcomes = state.outcomes - SaveStep.Avatar)
                            else -> done.copy(banner = BannerChange.Replace(picked), outcomes = state.outcomes - SaveStep.Banner)
                        }
                    }
                }
            }
        }
    }

    /** Desiste do avatar escolhido. */
    fun discardAvatar() {
        if (_state.value.saving) return
        _state.update { it.copy(avatar = null, refusals = it.refusals - ProfileImageKind.Avatar, outcomes = it.outcomes - SaveStep.Avatar) }
    }

    /** Desiste do banner escolhido ou da remoção dele. */
    fun discardBanner() {
        if (_state.value.saving) return
        _state.update { it.copy(banner = BannerChange.Keep, refusals = it.refusals - ProfileImageKind.Banner, outcomes = it.outcomes - SaveStep.Banner) }
    }

    /** Marca o banner para sair no "Salvar". */
    fun removeBanner() {
        if (_state.value.saving) return
        _state.update { it.copy(banner = BannerChange.Remove, outcomes = it.outcomes - SaveStep.Banner) }
    }

    /** Um pedido de "Salvar": o passo, o que ele manda e o que fazer depois que o servidor o aceita. */
    private class Planned(val step: SaveStep, val send: suspend () -> Unit, val afterSaved: suspend () -> Unit)

    /** Manda o que mudou, um pedido por vez. Sem efeito se não há o que salvar ou já está salvando. */
    fun save() {
        val start = _state.value
        if (!start.canSave) return
        val planned = plan(start)
        _state.update { it.copy(saving = true, outcomes = emptyMap()) }
        viewModelScope.launch {
            var stopped = false
            var anySaved = false
            for (item in planned) {
                if (stopped) {
                    setOutcome(item.step, StepOutcome.Skipped)
                    continue
                }
                setOutcome(item.step, StepOutcome.Sending)
                val result = runMutation(item.send)
                if (result is MutationResult.Done) {
                    anySaved = true
                    setOutcome(item.step, StepOutcome.Saved)
                    session.profiles.invalidate(me)
                    item.afterSaved()
                } else {
                    setOutcome(item.step, StepOutcome.Failed(result))
                    // Recusar (4xx) é sobre este pedido; as outras falhas dizem que os próximos também não passariam.
                    stopped = result !is MutationResult.Rejected
                }
            }
            if (anySaved) refreshProfile()
            _state.update { it.copy(saving = false) }
        }
    }

    private fun plan(state: EditProfileState): List<Planned> = buildList {
        if (state.textChanged) {
            val text = state.draft.trimmed()
            // A frase de digitação só vai se mudou: ausente, o servidor não mexe nela.
            val typing = text.typing.takeIf { it != state.saved.typing }
            add(
                Planned(
                    SaveStep.Text,
                    send = { session.api.updateProfile(me, UpdateProfileRequest(text.nickname, text.statusMessage, text.description, typing)) },
                    afterSaved = { _state.update { it.copy(saved = text, draft = text) } },
                ),
            )
        }
        state.avatar?.let { picked ->
            add(
                Planned(
                    SaveStep.Avatar,
                    send = { session.api.updateAvatar(me, UpdateAvatarRequest(Base64.encode(picked.image.bytes), picked.format)) },
                    afterSaved = {
                        // O próprio avatar muda na hora, sem esperar o `avatar_update`.
                        session.chat.avatarReplaced(me) { version -> session.media.put(avatarMediaName(me, version), picked.image.bytes) }
                        _state.update { it.copy(avatar = null) }
                    },
                ),
            )
        }
        when (val change = state.banner) {
            BannerChange.Keep -> Unit
            is BannerChange.Replace -> add(
                Planned(
                    SaveStep.Banner,
                    send = { session.api.updateBanner(me, UpdateBannerRequest(Base64.encode(change.picked.image.bytes), change.picked.format)) },
                    afterSaved = { _state.update { it.copy(banner = BannerChange.Keep) } },
                ),
            )
            BannerChange.Remove -> add(
                Planned(
                    SaveStep.Banner,
                    send = { session.api.updateBanner(me, UpdateBannerRequest("", "")) },
                    // Quem decide se ainda há banner é a leitura do perfil que vem depois de salvar.
                    afterSaved = { _state.update { it.copy(banner = BannerChange.Keep) } },
                ),
            )
        }
    }

    private fun setOutcome(step: SaveStep, outcome: StepOutcome) = _state.update { it.copy(outcomes = it.outcomes + (step to outcome)) }

    /** Lê o perfil de novo depois de salvar, para o banner e a frase de digitação mostrarem o que o servidor guardou. */
    private suspend fun refreshProfile() {
        try {
            val profile = session.profiles.get(me, refresh = true)
            _state.update { state ->
                // Os textos que ainda não foram salvos (um pedido falhou) continuam como o usuário os deixou.
                state.copy(load = EditLoad.Ready(profile), saved = if (state.textChanged) state.saved else profile.toDraft())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // O que o servidor guardou se vê ao reabrir; salvar já deu certo.
        }
    }

    private fun UserProfile.toDraft() = ProfileDraft(nickname.orEmpty(), statusMessage.orEmpty(), description.orEmpty(), typing.orEmpty())
}
