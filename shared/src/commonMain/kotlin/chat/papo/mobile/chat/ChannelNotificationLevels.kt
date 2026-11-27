package chat.papo.mobile.chat

import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.core.classifyMutation
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChannelUserSettingDto
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.db.account.ChatQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Por que a gravação do nível no servidor não valeu. Nenhuma é repetida sozinha. */
enum class LevelFailureReason {
    /** 403: a conta não pode mudar este canal. */
    Forbidden,

    /** 404: o canal não existe mais. */
    NotFound,

    /** 429: esperar e escolher de novo à mão (regra 15). */
    RateLimited,

    /** Outro 4xx: o servidor entendeu e recusou. */
    Rejected,

    /** Sem conexão: o servidor não recebeu nada. */
    NotSent,

    /** A sessão não vale mais. */
    Unauthorized,

    /** O servidor pode ter gravado: os canais foram relidos para conferir, e nada foi reenviado. */
    Uncertain,
}

/** A falha da última gravação, com o canal a que ela se refere. */
data class LevelFailure(val channelId: String, val reason: LevelFailureReason)

/** O estado da gravação do nível no servidor: o canal que está sendo gravado e a última falha. */
data class LevelSave(val savingChannelId: String? = null, val failure: LevelFailure? = null)

/**
 * O nível de notificação que o servidor guarda para cada canal desta conta (`notification_settings`): é o que vale nos
 * canais sincronizados, no app, no web e no TUI.
 */
interface ServerChannelLevels {
    /** O nível de cada canal conhecido. Canal sem valor guardado vale [NotificationLevel.OnlyMentions], como no servidor. */
    val levels: StateFlow<Map<String, NotificationLevel>>

    val save: StateFlow<LevelSave>

    /** Grava o nível do canal no servidor, sem esperar. O resultado aparece em [save] e, se valeu, em [levels]. */
    fun set(channelId: String, level: NotificationLevel)

    /** Esquece a última falha (a folha que a mostrava foi fechada). */
    fun clearFailure()
}

/** Sem servidor: nenhum nível conhecido e nada a gravar. Serve a quem só quer as configurações locais. */
object NoServerChannelLevels : ServerChannelLevels {
    override val levels: StateFlow<Map<String, NotificationLevel>> = MutableStateFlow(emptyMap())
    override val save: StateFlow<LevelSave> = MutableStateFlow(LevelSave())
    override fun set(channelId: String, level: NotificationLevel) = Unit
    override fun clearFailure() = Unit
}

/** O nível que o servidor devolve para um canal: sem valor, ou com um que o app não conhece, vale "só menções". */
fun serverLevelOf(wire: String?): NotificationLevel = NotificationLevel.fromWire(wire) ?: NotificationLevel.OnlyMentions

/**
 * Grava o nível de um canal em `POST /channels/{id}/user/{eu}/settings` e guarda o que o servidor devolveu no banco da
 * conta, de onde saem os níveis. É a única coisa que vai ao servidor entre as configurações de notificação: silêncio e
 * supressão ficam no aparelho.
 *
 * Nenhuma gravação é repetida sozinha. Um 429 pede que a pessoa escolha de novo; um resultado incerto (falha de rede
 * depois do envio, 5xx, resposta que não confirma o pedido) relê os canais para conferir. Uma gravação por vez.
 */
class ChannelNotificationLevels(
    private val api: ChatApi,
    private val queries: ChatQueries,
    private val userId: String,
    channels: StateFlow<List<Channel>>,
    /** Relê os canais no servidor (`ChatStore.reloadChannels`). */
    private val reload: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : ServerChannelLevels {
    private val lock = Mutex()

    override val levels: StateFlow<Map<String, NotificationLevel>> =
        channels.map(::levelsOf).stateIn(scope, SharingStarted.Eagerly, levelsOf(channels.value))

    private val _save = MutableStateFlow(LevelSave())
    override val save: StateFlow<LevelSave> = _save.asStateFlow()

    override fun set(channelId: String, level: NotificationLevel) {
        scope.launch { save(channelId, level) }
    }

    override fun clearFailure() {
        _save.value = _save.value.copy(failure = null)
    }

    /** Grava e devolve o resultado: [MutationResult.Done] só quando o servidor confirmou o que se pediu. */
    suspend fun save(channelId: String, level: NotificationLevel): MutationResult = lock.withLock {
        _save.value = LevelSave(savingChannelId = channelId)
        var status: Int? = null
        var result: MutationResult = MutationResult.Done
        try {
            result = try {
                val body = api.setChannelNotification(channelId, userId, level.wire)
                if (confirms(body, channelId, level)) {
                    withContext(io) { queries.setChannelNotification(level.wire, channelId) }
                    MutationResult.Done
                } else {
                    MutationResult.Uncertain
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = (e as? ApiException)?.status
                classifyMutation(e)
            }
            // A mudança pode ter sido gravada: conferir no servidor, que é uma leitura, e não mandar de novo.
            if (result == MutationResult.Uncertain) reloadQuietly()
        } finally {
            _save.value = LevelSave(failure = failureOf(result, status, channelId))
        }
        result
    }

    /** O servidor diz que gravou o nível pedido, para este usuário e este canal. */
    private fun confirms(body: ChannelUserSettingDto, channelId: String, level: NotificationLevel) =
        body.channelId == channelId && body.userId == userId && body.notificationSettings == level.wire

    private suspend fun reloadQuietly() {
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fica o que havia no banco; a próxima leitura dos canais corrige.
        }
    }

    private fun failureOf(result: MutationResult, status: Int?, channelId: String): LevelFailure? {
        val reason = when (result) {
            MutationResult.Done -> return null
            MutationResult.RateLimited -> LevelFailureReason.RateLimited
            MutationResult.NotSent -> LevelFailureReason.NotSent
            MutationResult.Unauthorized -> LevelFailureReason.Unauthorized
            MutationResult.Uncertain -> LevelFailureReason.Uncertain
            is MutationResult.Rejected -> when (status) {
                403 -> LevelFailureReason.Forbidden
                404 -> LevelFailureReason.NotFound
                else -> LevelFailureReason.Rejected
            }
        }
        return LevelFailure(channelId, reason)
    }

    private fun levelsOf(list: List<Channel>): Map<String, NotificationLevel> =
        list.associate { it.id to serverLevelOf(it.notification) }
}
