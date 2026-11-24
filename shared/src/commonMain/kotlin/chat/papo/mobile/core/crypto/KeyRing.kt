package chat.papo.mobile.core.crypto

/**
 * Chaves de uso derivadas da chave mestra. Existe só enquanto o app está
 * desbloqueado; [destroy] zera a chave mestra da memória.
 */
class KeyRing(private val master: ByteArray, private val crypto: Crypto) {
    init {
        require(master.size == Crypto.KEY_SIZE) { "chave mestra deve ter ${Crypto.KEY_SIZE} bytes" }
    }

    private var destroyed = false

    /** Chave do banco que guarda as contas e os cookies de sessão. */
    fun registryDbKey(): ByteArray = derive("papo/v1/db/registry")

    /** Chave do banco de dados de uma conta. */
    fun accountDbKey(accountId: String): ByteArray = derive("papo/v1/db/account/$accountId")

    /** Chave do cache de mídia de uma conta. */
    fun mediaKey(accountId: String): ByteArray = derive("papo/v1/media/$accountId")

    fun destroy() {
        destroyed = true
        master.fill(0)
    }

    private fun derive(label: String): ByteArray {
        check(!destroyed) { "chaveiro já destruído" }
        return Hkdf.derive(crypto, master, salt = null, info = label.encodeToByteArray(), length = Crypto.KEY_SIZE)
    }
}
