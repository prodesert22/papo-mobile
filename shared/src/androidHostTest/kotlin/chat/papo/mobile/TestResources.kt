package chat.papo.mobile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.account.AccountResources
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.platform.JcaCrypto
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.nio.file.Files

object TestResources {
    fun driver(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AccountDb.Schema.create(it) }

    fun tempDir(): String = Files.createTempDirectory("papo-test").toFile().also { it.deleteOnExit() }.absolutePath

    fun inMemory(io: CoroutineDispatcher): AccountResources {
        val crypto = JcaCrypto()
        return AccountResources(driver(), MediaCache(tempDir(), crypto.randomBytes(32), crypto, io))
    }

    /** Sessão de conta sobre um motor HTTP falso; o WebSocket nunca conecta. */
    fun session(engine: HttpClientEngine, scope: TestScope, userId: String = "u1"): AccountSession {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        return AccountSession("conta", userId, client, scope.backgroundScope, inMemory(dispatcher), dispatcher)
    }
}
