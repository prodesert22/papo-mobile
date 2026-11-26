package chat.papo.mobile.account

import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.AuthApi
import chat.papo.mobile.data.api.AuthCookieStore
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.api.ServerDto
import chat.papo.mobile.media.VideoPosterExtractor
import chat.papo.mobile.security.AppPrefs
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Resultado de consultar um endereço antes de entrar nele. */
class ServerProbe(val url: ServerUrl, val info: ServerDto)

class InvalidServerUrl(val problem: ServerUrl.Problem) : Exception("Endereço de servidor inválido")

sealed interface AddAccountResult {
    val account: Account

    /** Conta nova gravada. */
    class Added(override val account: Account) : AddAccountResult

    /** O par (servidor, usuário) já existia; a sessão dele foi renovada. */
    class Updated(override val account: Account) : AddAccountResult
}

/**
 * Contas do app e a sessão de cada uma. Cada conta tem cliente HTTP, cookie e
 * dados próprios; o mesmo servidor pode ter várias contas.
 */
class AccountManager(
    private val store: AccountStore,
    private val engine: HttpClientEngine,
    private val prefs: AppPrefs,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val openResources: (accountId: String) -> AccountResources,
    private val deleteLocalData: (accountId: String) -> Unit,
    private val posterExtractor: VideoPosterExtractor? = null,
) {
    private var foreground = true

    private val mutex = Mutex()
    private val sessions = MutableStateFlow<Map<String, AccountSession>>(emptyMap())

    val accounts: StateFlow<List<Account>> = store.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _activeId = MutableStateFlow(prefs.getString(PREF_ACTIVE))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** As sessões das contas gravadas já foram criadas. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun session(accountId: String): AccountSession? = sessions.value[accountId]

    val sessionMap: StateFlow<Map<String, AccountSession>> = sessions.asStateFlow()

    suspend fun load() = mutex.withLock {
        val stored = store.all()
        sessions.value = stored.associate { (account, cookie) -> account.id to createSession(account, cookie) }
        if (stored.none { it.first.id == _activeId.value }) select(stored.firstOrNull()?.first?.id)
        _loaded.value = true
    }

    fun select(accountId: String?) {
        prefs.putString(PREF_ACTIVE, accountId)
        _activeId.value = accountId
    }

    /** Normaliza o endereço e consulta o servidor (endpoints públicos). */
    suspend fun probe(input: String): ServerProbe {
        val url = when (val parsed = ServerUrl.parse(input)) {
            is ServerUrl.Parsed.Ok -> parsed.url
            is ServerUrl.Parsed.Invalid -> throw InvalidServerUrl(parsed.problem)
        }
        val client = PapoClient(engine, url, MemoryCookieStore())
        try {
            return ServerProbe(url, AuthApi(client).server())
        } finally {
            client.close()
        }
    }

    /** Começa o login em um servidor já consultado. */
    fun begin(probe: ServerProbe): PendingAccount = PendingAccount(probe)

    suspend fun setAppearance(accountId: String, label: String?, color: Int?) =
        store.setAppearance(accountId, label, color)

    /** Atualiza nome/ícone do servidor e o nome do usuário a partir do backend. */
    suspend fun refreshInfo(accountId: String) {
        val session = session(accountId) ?: return
        val server = session.auth.server()
        store.setServer(accountId, server.name, server.iconBytes())
        val me = session.auth.whoami()
        store.setIdentity(accountId, me.username, me.nickname)
    }

    /** Sai da conta no servidor (melhor esforço) e apaga tudo o que é dela no aparelho. */
    suspend fun remove(accountId: String) = mutex.withLock {
        val session = sessions.value[accountId]
        if (session != null) {
            runCatching { session.auth.logout() }
            session.close()
            sessions.value = sessions.value - accountId
        }
        withContext(NonCancellable) {
            store.delete(accountId)
            withContext(dispatchers.io) { deleteLocalData(accountId) }
        }
        if (_activeId.value == accountId) select(store.all().firstOrNull()?.first?.id)
    }

    /** Remove a conta sem prender quem chamou (a tela já saiu). */
    fun removeInBackground(accountId: String) {
        scope.launch { runCatching { remove(accountId) } }
    }

    fun close() {
        sessions.value.values.forEach { it.close() }
        sessions.value = emptyMap()
    }

    private fun createSession(account: Account, cookie: String?): AccountSession {
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        val cookies = StoredCookie(account.id, cookie)
        val client = PapoClient(engine, account.server, cookies)
        val session = AccountSession(account.id, account.userId, client, sessionScope, openResources(account.id), dispatchers.io, dispatchers.background, posterExtractor)
        if (foreground) session.start()
        return session
    }

    /** Com o app fora da tela o tempo real é desligado; ao voltar, religa e ressincroniza. */
    fun setForeground(active: Boolean) {
        foreground = active
        sessions.value.values.forEach { if (active) it.start() else it.pause() }
    }

    /** Cookie de uma conta: valor em memória, gravado no registro cifrado a cada mudança. */
    private inner class StoredCookie(private val accountId: String, private var value: String?) : AuthCookieStore {
        override fun get(): String? = value

        override suspend fun set(cookie: String?) {
            if (cookie == value) return
            value = cookie
            withContext(NonCancellable) { store.setCookie(accountId, cookie) }
        }
    }

    /**
     * Login em andamento num servidor. O cookie fica só em memória até o login
     * terminar; aí vira uma conta nova ou renova a que já existia.
     */
    inner class PendingAccount internal constructor(val probe: ServerProbe) {
        private val cookies = MemoryCookieStore()
        private val client = PapoClient(engine, probe.url, cookies)
        private val api = AuthApi(client)

        /** Servidor não público: a senha do servidor precisa ser validada antes de login/registro. */
        var needsServerPassword: Boolean = !probe.info.public
            private set

        suspend fun submitServerPassword(password: String) {
            api.loginServer(password)
            needsServerPassword = false
        }

        suspend fun login(username: String, password: String): AddAccountResult {
            val response = api.login(username.trim(), password)
            val result = finish()
            if (response.connectionViolation) _securityAlerts.value += result.account.id
            return result
        }

        suspend fun register(username: String, password: String): AddAccountResult {
            api.register(username.trim(), password)
            return login(username, password)
        }

        fun cancel() = client.close()

        private suspend fun finish(): AddAccountResult = mutex.withLock {
            val me = api.whoami()
            val cookie = cookies.get() ?: throw ApiException(401, "Sessão não iniciada", null, null)
            val existing = store.find(probe.url, me.id)
            client.close()

            val account: Account
            if (existing != null) {
                sessions.value[existing.id]?.close()
                store.setCookie(existing.id, cookie)
                store.setIdentity(existing.id, me.username, me.nickname)
                store.setServer(existing.id, probe.info.name, probe.info.iconBytes())
                account = store.get(existing.id) ?: existing
            } else {
                account = Account(
                    id = Uuid.random().toString(),
                    server = probe.url,
                    userId = me.id,
                    username = me.username,
                    nickname = me.nickname,
                    serverName = probe.info.name.ifBlank { probe.url.host },
                    serverIcon = probe.info.iconBytes(),
                    label = null,
                    color = null,
                    sessionExpired = false,
                )
                store.insert(account, cookie, Clock.System.now().toEpochMilliseconds())
            }
            sessions.value = sessions.value + (account.id to createSession(account, cookie))
            select(account.id)
            if (existing != null) AddAccountResult.Updated(account) else AddAccountResult.Added(account)
        }
    }

    private val _securityAlerts = MutableStateFlow<Set<String>>(emptySet())
    /** Contas em que o servidor detectou reuso do token de sessão e revogou todas as conexões. */
    val securityAlerts: StateFlow<Set<String>> = _securityAlerts.asStateFlow()

    fun dismissSecurityAlert(accountId: String) {
        _securityAlerts.value -= accountId
    }

    private companion object {
        const val PREF_ACTIVE = "accounts.active"
    }
}

internal fun ServerDto.iconBytes(): ByteArray? =
    iconBlob?.takeIf { it.isNotBlank() }?.let { runCatching { Base64.decode(it) }.getOrNull() }
