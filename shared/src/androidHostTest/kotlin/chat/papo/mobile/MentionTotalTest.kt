package chat.papo.mobile

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AccountStore
import chat.papo.mobile.chat.MentionTotal
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.registry.RegistryDb
import chat.papo.mobile.security.AppPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** O total de menções de todas as contas, para o selo do ícone: a soma do que cada sessão conta sozinha. */
@OptIn(ExperimentalCoroutinesApi::class)
class MentionTotalTest {
    private class Prefs : AppPrefs {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private class Fixture(scope: TestScope) {
        val backend = FakeBackend()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val drivers = mutableMapOf<String, SqlDriver>()
        val manager = AccountManager(
            store = AccountStore(RegistryDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RegistryDb.Schema.create(it) }), dispatcher),
            engine = backend.engine,
            prefs = Prefs(),
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
            scope = scope.backgroundScope,
            openResources = { id -> TestResources.inMemory(dispatcher).also { drivers[id] = it.driver } },
            deleteLocalData = {},
        ).also { it.setForeground(false) }

        suspend fun login(host: String): String = manager.begin(manager.probe(host)).login("ana", "Senha@123").account.id

        fun unreadDm(accountId: String, id: String, unread: Int) {
            val db = AccountDb(drivers.getValue(accountId))
            db.chatQueries.upsertUser("u2", "bia", null, null, null, null, false, null)
            db.chatQueries.upsertDm(id, "u2", "2026-01-01T00:00:00Z", null, null, null, null, null, unread.toLong())
        }
    }

    @Test
    fun semContasOTotalEZero() = runTest {
        val f = Fixture(this)
        f.manager.load()
        runCurrent()

        assertEquals(MentionTotal(0, false), f.manager.mentionTotal.value)
    }

    @Test
    fun somaOQueCadaContaContaSozinhaEAcompanhaAsMudancas() = runTest {
        val f = Fixture(this)
        f.manager.load()
        val um = f.login("um.test")
        val dois = f.login("dois.test")
        f.unreadDm(um, "d1", 3)
        f.unreadDm(dois, "d1", 4) // o mesmo id de conversa em outro servidor é outra conversa
        runCurrent()

        val sessions = f.manager.sessionMap.value
        val total = f.manager.mentionTotal.first { it.count == 7 }

        assertEquals(3, sessions.getValue(um).mentions.mentions.value.total)
        assertEquals(4, sessions.getValue(dois).mentions.mentions.value.total)
        assertEquals(MentionTotal(7, false), total)

        f.unreadDm(um, "d1", 0)
        assertEquals(4, f.manager.mentionTotal.first { it.count == 4 }.count)
    }

    @Test
    fun contaRemovidaSaiDoTotal() = runTest {
        val f = Fixture(this)
        f.manager.load()
        val um = f.login("um.test")
        val dois = f.login("dois.test")
        f.unreadDm(um, "d1", 2)
        f.unreadDm(dois, "d1", 5)
        runCurrent()
        f.manager.mentionTotal.first { it.count == 7 }

        f.manager.remove(dois)

        assertEquals(2, f.manager.mentionTotal.first { it.count == 2 }.count)
    }
}
