package chat.papo.mobile.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Abre bancos SQLite cifrados (SQLCipher) com uma chave bruta de 256 bits.
 * Implementado pela plataforma.
 */
interface DatabaseFactory {
    fun open(name: String, key: ByteArray, schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver

    fun delete(name: String)

    fun deleteAll()

    companion object {
        const val REGISTRY = "registry.db"

        fun accountDbName(accountId: String) = "account-$accountId.db"
    }
}
