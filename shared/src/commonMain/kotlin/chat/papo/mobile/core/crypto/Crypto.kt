package chat.papo.mobile.core.crypto

/** Falha ao abrir um dado cifrado: chave errada ou conteúdo adulterado. */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Primitivas criptográficas fornecidas pela plataforma (JCA no Android,
 * CommonCrypto/CryptoKit no iOS). O código comum só compõe essas primitivas.
 */
interface Crypto {
    fun randomBytes(size: Int): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** AES-256-GCM. Devolve `nonce (12) || ciphertext || tag (16)`. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = EMPTY): ByteArray

    /** Inverso de [seal]. Lança [CryptoException] se a autenticação falhar. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray = EMPTY): ByteArray

    companion object {
        val EMPTY = ByteArray(0)
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
    }
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val HASH_SIZE = 32

    fun derive(crypto: Crypto, ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_SIZE) { "tamanho de saída inválido: $length" }
        val prk = crypto.hmacSha256(salt?.takeIf { it.isNotEmpty() } ?: ByteArray(HASH_SIZE), ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = crypto.hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(HASH_SIZE, length - offset)
            previous.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        prk.fill(0)
        return out
    }
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0f]
    }
    return chars.concatToString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex com tamanho ímpar" }
    return ByteArray(length / 2) { i ->
        val hi = HEX.indexOf(this[i * 2].lowercaseChar())
        val lo = HEX.indexOf(this[i * 2 + 1].lowercaseChar())
        require(hi >= 0 && lo >= 0) { "caractere hex inválido" }
        ((hi shl 4) or lo).toByte()
    }
}
