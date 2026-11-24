package chat.papo.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.core.crypto.KeyRing
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.registry.RegistryDb
import chat.papo.mobile.platform.AndroidDatabaseFactory
import chat.papo.mobile.platform.JcaCrypto
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SQLCipher nativo no aparelho: o que vai para o disco e quem consegue abrir. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val crypto = JcaCrypto()
    private val factory = AndroidDatabaseFactory(context)
    private val keys = KeyRing(ByteArray(32) { it.toByte() }, crypto)

    @Before
    @After
    fun clean() {
        listOf(REGISTRY, ACCOUNT_A, ACCOUNT_B).forEach(factory::delete)
    }

    private fun insertAccount(db: RegistryDb) = db.accountQueries.insert(
        id = "conta-1",
        base_url = "https://papo.exemplo-secreto.test",
        user_id = "user-1",
        username = "usuario-secreto",
        nickname = null,
        server_name = "Servidor",
        server_icon = null,
        position = 0,
        auth_cookie = COOKIE,
        created_at = 1,
    )

    @Test
    fun oArquivoNoDiscoNaoRevelaNada() {
        val driver = factory.open(REGISTRY, keys.registryDbKey(), RegistryDb.Schema)
        insertAccount(RegistryDb(driver))
        driver.close()

        val file = context.getDatabasePath(REGISTRY)
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(bytes.size >= 4096)
        assertFalse(text.startsWith("SQLite format 3"), "o banco não pode ser um SQLite comum")
        for (secret in listOf(COOKIE, "usuario-secreto", "exemplo-secreto", "CREATE TABLE", "auth_cookie")) {
            assertFalse(secret in text, "\"$secret\" apareceu em claro no arquivo")
        }
    }

    @Test
    fun reabreComAMesmaChaveERecusaOutra() {
        factory.open(REGISTRY, keys.registryDbKey(), RegistryDb.Schema).also { insertAccount(RegistryDb(it)) }.close()

        val again = factory.open(REGISTRY, keys.registryDbKey(), RegistryDb.Schema)
        assertEquals(COOKIE, RegistryDb(again).accountQueries.selectById("conta-1").executeAsOne().auth_cookie)
        again.close()

        // A chave de outra conta (ou de outra chave mestra) não abre este banco.
        assertFails {
            val wrong = factory.open(REGISTRY, keys.accountDbKey("outra"), RegistryDb.Schema)
            try {
                RegistryDb(wrong).accountQueries.selectAll().executeAsList()
            } finally {
                wrong.close()
            }
        }
        val other = KeyRing(ByteArray(32) { 7 }, crypto)
        assertFails {
            val wrong = factory.open(REGISTRY, other.registryDbKey(), RegistryDb.Schema)
            try {
                RegistryDb(wrong).accountQueries.selectAll().executeAsList()
            } finally {
                wrong.close()
            }
        }
    }

    @Test
    fun cadaContaTemBancoEChaveProprios() {
        val a = factory.open(ACCOUNT_A, keys.accountDbKey("a"), AccountDb.Schema)
        val b = factory.open(ACCOUNT_B, keys.accountDbKey("b"), AccountDb.Schema)
        AccountDb(a).metaQueries.put("cursor", "da-conta-a")
        AccountDb(b).metaQueries.put("cursor", "da-conta-b")

        assertEquals("da-conta-a", AccountDb(a).metaQueries.get("cursor").executeAsOne())
        assertEquals("da-conta-b", AccountDb(b).metaQueries.get("cursor").executeAsOne())
        a.close()
        b.close()

        // Trocar as chaves entre as contas não abre nada.
        assertFails {
            val crossed = factory.open(ACCOUNT_A, keys.accountDbKey("b"), AccountDb.Schema)
            try {
                AccountDb(crossed).metaQueries.get("cursor").executeAsOne()
            } finally {
                crossed.close()
            }
        }
    }

    @Test
    fun esquemaDeConversaFuncionaNoSqliteDoAparelho() {
        // O esquema usa UPSERT (`ON CONFLICT DO UPDATE`), que depende da versão do SQLite embutido no SQLCipher.
        val driver = factory.open(ACCOUNT_A, keys.accountDbKey("a"), AccountDb.Schema)
        val q = AccountDb(driver).chatQueries
        q.upsertUser("u1", "ana", null, null, null, null, false, null)
        q.upsertUser("u1", "ana", "Ana Nova", null, null, null, false, "#FF0000")
        q.bumpAvatar("u1")
        val user = q.selectUser("u1").executeAsOne()
        assertEquals("Ana Nova", user.nickname)
        assertEquals(1L, user.avatar_version) // o UPSERT não zerou o que não era dele

        q.insertMessageIfAbsent("m1", "c1", "u1", "primeira", "2026-01-01T00:00:00Z", 1, null, null, null, "[]")
        q.insertMessageIfAbsent("m1", "c1", "u1", "duplicada", "2026-01-01T00:00:00Z", 1, null, null, null, "[]")
        assertEquals("primeira", q.selectMessages("c1", 10).executeAsList().single().content)
        driver.close()
    }

    @Test
    fun apagarRemoveOArquivo() {
        // O arquivo só nasce no primeiro acesso ao banco.
        factory.open(REGISTRY, keys.registryDbKey(), RegistryDb.Schema).also { insertAccount(RegistryDb(it)) }.close()
        assertTrue(context.getDatabasePath(REGISTRY).exists())
        factory.delete(REGISTRY)
        assertFalse(context.getDatabasePath(REGISTRY).exists())
    }

    private companion object {
        const val REGISTRY = "teste-registry.db"
        const val ACCOUNT_A = "teste-account-a.db"
        const val ACCOUNT_B = "teste-account-b.db"
        const val COOKIE = "COOKIE-SEGREDO-eyJhbGciOiJIUzI1NiJ9"
    }
}
