package chat.papo.mobile.chat

import chat.papo.mobile.data.api.ChannelDto
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.RolePermissionsDto
import chat.papo.mobile.data.api.ServerDto
import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Tudo o que o app sabe das permissões de uma conta, reunido num [PermissionSnapshot] que a regra de
 * `Permissions.kt` lê. Quem o dono é, o catálogo de cargos e os cargos de cada usuário chegam por aqui;
 * as entradas de cada canal e a lista de usuários vêm do [ChatStore], que já faz essas leituras.
 *
 * Nada aqui chama as rotas de `/auth` (regra 15): o dono vem de `GET /server`, que é público, e os cargos do próprio
 * usuário vêm de `GET /users`. Cada fonte que falha mantém o último valor e nunca derruba a sincronização.
 * O snapshot fica na tabela `meta` do banco cifrado da conta (chave [KEY]), então a tela não "pisca" um
 * canal oculto a cada desbloqueio e o que se sabia continua valendo sem rede. Cada conta tem o seu
 * [AccessStore], com o seu banco: nada é compartilhado entre contas.
 *
 * Quem fecha o que ele abre é o escopo da sessão [scope]; o [AccessStore] não guarda nenhum recurso próprio.
 */
class AccessStore(
    private val meta: MetaQueries,
    private val api: ChatApi,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Lê `GET /server`. Sem ela o dono fica desconhecido e, sem o dono, nada é negado. */
    private val server: (suspend () -> ServerDto)? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val forbiddenInterval: Duration = FORBIDDEN_INTERVAL,
) {
    private val _snapshot = MutableStateFlow(PermissionSnapshot.EMPTY)
    val snapshot: StateFlow<PermissionSnapshot> = _snapshot.asStateFlow()

    /**
     * Eventos de cargo já aplicados e, por usuário, o número do último. Uma página de `GET /users` pedida
     * antes de um evento não pode desfazê-lo; [eventLock] guarda os dois junto do que eles mudam. A tabela
     * não é esvaziada: leituras que se sobrepõem terminam fora de ordem, e só o número de cada evento diz
     * quais delas o conhecem. Ela cresce com os usuários que já mudaram de cargo, o que é pouco.
     */
    private val eventLock = Mutex()
    private var events = 0L
    private val touched = HashMap<String, Long>()

    /** Uma releitura de `GET /roles` por vez: vários `role_add` do mesmo cargo novo pedem uma só. */
    private val rolesLock = Mutex()

    // Pode haver dois avisos de 403 ao mesmo tempo, de telas diferentes: no pior caso saem duas leituras.
    private var lastForbiddenRefresh: TimeMark? = null

    // Depois de todos os campos: o escopo pode usá-los já na primeira instrução.
    init {
        scope.launch { persistChanges(alreadyStored = restore()) }
    }

    /** Relê o dono (`GET /server`) e os cargos (`GET /roles`). Uma fonte que falha mantém o último valor. */
    suspend fun refreshMeta() = coroutineScope {
        launch { refreshOwner() }
        launch { refreshRoles() }
    }

    private suspend fun refreshOwner() {
        val source = server ?: return
        val info = try {
            source()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val owner = info.ownerId?.takeIf { it.isNotEmpty() }
        _snapshot.update { it.copy(ownerId = owner, ownerKnown = true) }
    }

    private suspend fun refreshRoles() {
        val list = try {
            api.roles()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val catalog = list.associate { it.id to RoleFlags(it.permissions.flags()) }
        _snapshot.update { it.copy(roles = catalog) }
    }

    /** Guarda as entradas de cada canal de uma resposta de `GET /channels`; um canal que não veio deixa de ter. */
    fun applyChannels(channels: List<ChannelDto>) {
        val grants = channels.associate { channel ->
            channel.id to channel.permissions.map {
                ChannelGrant(
                    roleId = it.roleId,
                    read = it.permissions.readChannel,
                    send = it.permissions.sendMessages,
                    delete = it.permissions.deleteMessages,
                    voice = it.permissions.connectVoice,
                )
            }
        }
        _snapshot.update { it.copy(grants = grants) }
    }

    /**
     * Antes de ler as páginas de `GET /users`: o número dos eventos de cargo até agora, que
     * [applyUsers] usa para não desfazer o que veio depois.
     */
    suspend fun beginUsersSync(): Long = eventLock.withLock { events }

    /**
     * Guarda os cargos de cada usuário lidos de `GET /users` desde o [since] de [beginUsersSync].
     * [complete] diz que todas as páginas chegaram: só então um usuário ausente vale "sem cargos". O
     * usuário que recebeu `role_add` ou `role_remove` depois de [since] fica como o evento deixou.
     */
    suspend fun applyUsers(roles: Map<String, Set<String>>, complete: Boolean, since: Long) = eventLock.withLock {
        val current = _snapshot.value.userRoles
        val merged = HashMap(roles)
        for ((userId, at) in touched) {
            if (at <= since) continue
            // Ausente aqui é "sem cargos" (leitura completa que o dropou) ou desconhecido: nunca o que a página velha diz.
            val kept = current[userId]
            if (kept != null) merged[userId] = kept else merged.remove(userId)
        }
        if (complete) merged.values.removeAll { it.isEmpty() }
        _snapshot.update { it.copy(userRoles = merged, usersComplete = complete) }
    }

    /** `role_add`: aplica na hora, sem esperar `GET /users`. Um cargo que o catálogo não tem pede uma releitura dele. */
    suspend fun roleAdded(userId: String, roleId: String) {
        changeRoles(userId) { it + roleId }
        val catalog = _snapshot.value.roles
        if (catalog == null || roleId !in catalog) scope.launch { refreshRolesFor(roleId) }
    }

    /** `role_remove`: aplica na hora, sem esperar `GET /users`. */
    suspend fun roleRemoved(userId: String, roleId: String) = changeRoles(userId) { it - roleId }

    private suspend fun changeRoles(userId: String, change: (Set<String>) -> Set<String>) = eventLock.withLock {
        val snapshot = _snapshot.value
        // Sem saber os outros cargos do usuário, o evento não pode ser aplicado: ficaria parecendo a lista inteira.
        val known = snapshot.userRoles[userId] ?: if (snapshot.usersComplete) emptySet() else return@withLock
        events++
        touched[userId] = events
        _snapshot.update { it.copy(userRoles = it.userRoles + (userId to change(known))) }
    }

    private suspend fun refreshRolesFor(roleId: String) = rolesLock.withLock {
        if (roleId in _snapshot.value.roles.orEmpty()) return@withLock
        refreshRoles()
    }

    /**
     * Se uma leitura de permissões pode ser pedida por causa de um 403: no máximo uma a cada
     * [forbiddenInterval], para que um servidor que recusa tudo não vire uma enxurrada de leituras.
     */
    fun allowForbiddenRefresh(): Boolean {
        val last = lastForbiddenRefresh
        if (last != null && last.elapsedNow() < forbiddenInterval) return false
        lastForbiddenRefresh = timeSource.markNow()
        return true
    }

    /** Lê o snapshot guardado. Devolve o que ficou em uso: nulo se não havia um, se estava estragado ou se já chegou algo da rede. */
    private suspend fun restore(): PermissionSnapshot? {
        val stored = try {
            withContext(io) { meta.get(KEY).executeAsOneOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val restored = stored?.let(::decode) ?: return null
        // O que já chegou da rede é mais novo que o guardado.
        _snapshot.update { if (it == PermissionSnapshot.EMPTY) restored else it }
        return restored.takeIf { _snapshot.value == it }
    }

    private suspend fun persistChanges(alreadyStored: PermissionSnapshot?) {
        var persisted = alreadyStored ?: PermissionSnapshot.EMPTY
        _snapshot.collect { current ->
            if (current == persisted || current == PermissionSnapshot.EMPTY) return@collect
            persisted = current
            bestEffort { withContext(io) { meta.put(KEY, encode(current)) } }
        }
    }

    /** O guardado é um conforto: se o banco falha, o app segue com o que tem na memória. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    internal companion object {
        const val KEY = "permissions"
        val FORBIDDEN_INTERVAL = 10.seconds

        /** Tolerante a campo novo; um valor fora do formato (ou de outra versão) é descartado ao ler. */
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(snapshot: PermissionSnapshot): String = json.encodeToString(Stored.serializer(), Stored.of(snapshot))

        /** O snapshot guardado em [text], ou null se o valor está estragado. */
        fun decode(text: String): PermissionSnapshot? = try {
            json.decodeFromString(Stored.serializer(), text).toSnapshot()
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun RolePermissionsDto.flags(): Set<RoleFlag> = buildSet {
            if (manageServer) add(RoleFlag.ManageServer)
            if (manageChannels) add(RoleFlag.ManageChannels)
            if (manageRoles) add(RoleFlag.ManageRoles)
            if (manageWebhooks) add(RoleFlag.ManageWebhooks)
            if (banMembers) add(RoleFlag.BanMembers)
            if (pinMessage) add(RoleFlag.PinMessage)
            if (everyoneMessage) add(RoleFlag.EveryoneMessage)
            if (sendAttachment) add(RoleFlag.SendAttachment)
        }
    }

    /** O [PermissionSnapshot] como vai para o banco: flags pelo nome, conjuntos como listas. */
    @Serializable
    private class Stored(
        val ownerId: String? = null,
        val ownerKnown: Boolean = false,
        val roles: Map<String, List<String>>? = null,
        val userRoles: Map<String, List<String>> = emptyMap(),
        val usersComplete: Boolean = false,
        val grants: Map<String, List<StoredGrant>>? = null,
    ) {
        fun toSnapshot() = PermissionSnapshot(
            ownerId = ownerId,
            ownerKnown = ownerKnown,
            roles = roles?.mapValues { (_, names) -> RoleFlags(names.mapNotNullTo(HashSet()) { name -> RoleFlag.entries.firstOrNull { it.name == name } }) },
            userRoles = userRoles.mapValues { it.value.toSet() },
            usersComplete = usersComplete,
            grants = grants?.mapValues { (_, list) -> list.map { ChannelGrant(it.role, it.read, it.send, it.delete, it.voice) } },
        )

        companion object {
            fun of(snapshot: PermissionSnapshot) = Stored(
                ownerId = snapshot.ownerId,
                ownerKnown = snapshot.ownerKnown,
                roles = snapshot.roles?.mapValues { (_, flags) -> flags.granted.map { it.name } },
                userRoles = snapshot.userRoles.mapValues { it.value.toList() },
                usersComplete = snapshot.usersComplete,
                grants = snapshot.grants?.mapValues { (_, list) -> list.map { StoredGrant(it.roleId, it.read, it.send, it.delete, it.voice) } },
            )
        }
    }

    @Serializable
    private class StoredGrant(
        val role: String,
        val read: Boolean = false,
        val send: Boolean = false,
        val delete: Boolean = false,
        val voice: Boolean = false,
    )
}
