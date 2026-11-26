package chat.papo.mobile.account

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import chat.papo.mobile.data.db.registry.RegistryDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import chat.papo.mobile.data.db.registry.Account as AccountRow

/** Contas gravadas no banco cifrado de registro, junto com o cookie de sessão de cada uma. */
class AccountStore(db: RegistryDb, private val io: CoroutineDispatcher) {
    private val queries = db.accountQueries

    fun observe(): Flow<List<Account>> =
        queries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<Pair<Account, String?>> = withContext(io) {
        queries.selectAll().executeAsList().map { it.toModel() to it.auth_cookie }
    }

    suspend fun find(server: ServerUrl, userId: String): Account? = withContext(io) {
        queries.selectByServerUser(server.value, userId).executeAsOneOrNull()?.toModel()
    }

    suspend fun get(id: String): Account? = withContext(io) {
        queries.selectById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun insert(account: Account, cookie: String?, createdAt: Long) = withContext(io) {
        queries.transaction {
            val position = queries.nextPosition().executeAsOne()
            queries.insert(
                id = account.id,
                base_url = account.server.value,
                user_id = account.userId,
                username = account.username,
                nickname = account.nickname,
                server_name = account.serverName,
                server_icon = account.serverIcon,
                position = position,
                auth_cookie = cookie,
                created_at = createdAt,
            )
        }
    }

    suspend fun setCookie(id: String, cookie: String?) = withContext(io) {
        if (cookie != null) queries.updateCookie(cookie, id) else queries.markSessionExpired(id)
    }

    suspend fun setIdentity(id: String, username: String, nickname: String?) = withContext(io) {
        queries.updateIdentity(username, nickname, id)
    }

    suspend fun setServer(id: String, name: String, icon: ByteArray?) = withContext(io) {
        queries.updateServer(name, icon, id)
    }

    suspend fun setLabel(id: String, label: String?) = withContext(io) {
        queries.updateLabel(label?.trim()?.takeIf { it.isNotEmpty() }, id)
    }

    suspend fun setColor(id: String, color: Int?) = withContext(io) {
        queries.updateColor(color?.toLong(), id)
    }

    suspend fun delete(id: String) = withContext(io) { queries.delete(id) }

    private fun AccountRow.toModel() = Account(
        id = id,
        server = ServerUrl(base_url),
        userId = user_id,
        username = username,
        nickname = nickname,
        serverName = server_name,
        serverIcon = server_icon,
        label = label,
        color = color?.toInt(),
        sessionExpired = session_expired,
    )
}
