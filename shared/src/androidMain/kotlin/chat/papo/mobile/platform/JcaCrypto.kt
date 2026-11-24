package chat.papo.mobile.platform

import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.CryptoException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitivas criptográficas sobre a JCA do Android. */
class JcaCrypto : Crypto {
    private val random = SecureRandom()

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(Crypto.NONCE_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(Crypto.TAG_SIZE * 8, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < Crypto.NONCE_SIZE + Crypto.TAG_SIZE) throw CryptoException("dado cifrado truncado")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(Crypto.TAG_SIZE * 8, sealed, 0, Crypto.NONCE_SIZE)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            return cipher.doFinal(sealed, Crypto.NONCE_SIZE, sealed.size - Crypto.NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            throw CryptoException("falha ao abrir dado cifrado", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
