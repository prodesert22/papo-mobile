package chat.papo.mobile.platform

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import chat.papo.mobile.core.crypto.toHex
import chat.papo.mobile.data.db.DatabaseFactory
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Bancos SQLCipher no diretório de bancos do app. */
class AndroidDatabaseFactory(private val context: Context) : DatabaseFactory {
    init {
        System.loadLibrary("sqlcipher")
    }

    override fun open(name: String, key: ByteArray, schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver {
        // `x'<hex>'` entrega a chave bruta ao SQLCipher, sem passar pelo PBKDF2
        // (a chave já vem de HKDF sobre a chave mestra).
        val passphrase = "x'${key.toHex()}'".toByteArray(Charsets.US_ASCII)
        return AndroidSqliteDriver(
            schema = schema,
            context = context,
            name = name,
            factory = SupportOpenHelperFactory(passphrase),
        )
    }

    override fun delete(name: String) {
        context.deleteDatabase(name)
    }

    override fun deleteAll() {
        context.databaseList().forEach { context.deleteDatabase(it) }
    }
}
