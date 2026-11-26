package chat.papo.mobile.chat

import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.core.runMutation
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.UserSummaryDto
import chat.papo.mobile.data.db.account.ChatQueries
import chat.papo.mobile.data.ws.WsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Um usuário que a conta bloqueou, como a lista do servidor o descreve. */
data class BlockedUser(val id: String, val username: String, val nickname: String?) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username.ifBlank { id }
}

/** Esse erro 403 de `POST /dms` e `POST /messages` diz que há um bloqueio entre as duas pessoas, em qualquer sentido. */
fun isDmBlockedType(type: String?): Boolean = type?.endsWith("/dm-blocked") == true

/**
 * Quem a conta bloqueou. O servidor guarda a lista e não avisa por evento; por isso ela é lida uma vez, na primeira
 * conexão da sessão, de novo ao abrir a tela de bloqueados ([refresh]) e depois de cada mudança. Nada é periódico, e
 * nenhuma mudança é repetida sozinha: o resultado volta a quem chamou.
 *
 * Bloquear vale para conversas diretas, nos dois sentidos; as mensagens nos canais continuam visíveis. É uma lista por
 * sessão, então duas contas, mesmo no mesmo servidor, nunca compartilham o que bloquearam.
 */
class BlockList(
    private val api: ChatApi,
    private val queries: ChatQueries,
    private val connection: StateFlow<WsState>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    /** Uma operação por vez: a resposta de uma leitura nunca apaga uma mudança que terminou depois dela. */
    private val lock = Mutex()
    private var started = false

    private val _users = MutableStateFlow<List<BlockedUser>>(emptyList())
    val users: StateFlow<List<BlockedUser>> = _users.asStateFlow()

    private val _blocked = MutableStateFlow<Set<String>>(emptySet())

    /** Os ids que a conta bloqueou. */
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    /** Lê a lista na primeira vez que a sessão conecta; as reconexões seguintes não leem de novo. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            connection.first { it == WsState.Connected }
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fica o que havia; a tela de bloqueados lê de novo ao abrir, e a conversa direta recusada mostra o motivo.
            }
        }
    }

    /** Lê a lista no servidor. Falha como qualquer pedido; quem chama decide o que mostrar. */
    suspend fun refresh() = lock.withLock { load() }

    /**
     * Bloqueia [userId]. Com [MutationResult.Done] ele entra na lista e as conversas diretas com ele saem do banco
     * (o servidor as esconde de `GET /dms`, e a próxima sincronização concorda). Com qualquer outro resultado a lista
     * fica como estava.
     */
    suspend fun block(userId: String): MutationResult = lock.withLock {
        val result = runMutation { api.block(userId) }
        when (result) {
            MutationResult.Done -> {
                setUsers(_users.value.filterNot { it.id == userId } + BlockedUser(userId, "", null))
                withContext(io) { queries.deleteDmsWithUser(userId) }
                reloadQuietly()
            }
            // A mudança pode ter sido aplicada: conferir no servidor, que é uma leitura, e não mandar de novo.
            MutationResult.Uncertain -> reloadQuietly()
            else -> Unit
        }
        result
    }

    /** Desbloqueia [userId]. A conversa direta só volta quando alguém a abrir de novo. */
    suspend fun unblock(userId: String): MutationResult = lock.withLock {
        val result = runMutation { api.unblock(userId) }
        when (result) {
            MutationResult.Done -> {
                setUsers(_users.value.filterNot { it.id == userId })
                reloadQuietly()
            }
            MutationResult.Uncertain -> reloadQuietly()
            else -> Unit
        }
        result
    }

    private suspend fun load() = setUsers(api.blocks().map { it.toBlocked() })

    private suspend fun reloadQuietly() {
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A mudança já vale localmente; a próxima leitura corrige o que faltar.
        }
    }

    private fun setUsers(list: List<BlockedUser>) {
        _users.value = list
        _blocked.value = list.mapTo(HashSet()) { it.id }
    }

    private fun UserSummaryDto.toBlocked() = BlockedUser(id, username, nickname)
}
