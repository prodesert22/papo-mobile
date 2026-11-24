package chat.papo.mobile

import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.CryptoException
import chat.papo.mobile.core.crypto.Hkdf
import chat.papo.mobile.core.crypto.KeyRing
import chat.papo.mobile.core.crypto.hexToBytes
import chat.papo.mobile.core.crypto.toHex
import chat.papo.mobile.platform.JcaCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CryptoTest {
    private val crypto = JcaCrypto()

    @Test
    fun hkdfVetoresDaRfc5869() {
        // Caso 1
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.derive(
                crypto,
                ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
                salt = "000102030405060708090a0b0c".hexToBytes(),
                info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
                length = 42,
            ).toHex(),
        )
        // Caso 3: sem salt e sem info
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hkdf.derive(
                crypto,
                ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
                salt = null,
                info = ByteArray(0),
                length = 42,
            ).toHex(),
        )
    }

    @Test
    fun sealEOpenFazemIdaEVolta() {
        val key = crypto.randomBytes(Crypto.KEY_SIZE)
        val plain = "mensagem secreta".encodeToByteArray()
        val aad = "bloco-0".encodeToByteArray()
        val sealed = crypto.seal(key, plain, aad)

        assertEquals(Crypto.NONCE_SIZE + plain.size + Crypto.TAG_SIZE, sealed.size)
        assertContentEquals(plain, crypto.open(key, sealed, aad))
        assertNotEquals(sealed.toHex(), crypto.seal(key, plain, aad).toHex(), "nonce deve mudar a cada vez")
    }

    @Test
    fun openDetectaAdulteracaoChaveErradaEAadErrado() {
        val key = crypto.randomBytes(Crypto.KEY_SIZE)
        val sealed = crypto.seal(key, ByteArray(64) { it.toByte() }, "a".encodeToByteArray())

        val tampered = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException> { crypto.open(key, tampered, "a".encodeToByteArray()) }
        assertFailsWith<CryptoException> { crypto.open(crypto.randomBytes(32), sealed, "a".encodeToByteArray()) }
        assertFailsWith<CryptoException> { crypto.open(key, sealed, "b".encodeToByteArray()) }
        assertFailsWith<CryptoException> { crypto.open(key, sealed.copyOf(10), "a".encodeToByteArray()) }
    }

    @Test
    fun chaveiroDerivaChavesDistintasEEstaveis() {
        val master = ByteArray(Crypto.KEY_SIZE) { it.toByte() }
        val ring = KeyRing(master.copyOf(), crypto)
        val keys = listOf(
            ring.registryDbKey(), ring.accountDbKey("a"), ring.accountDbKey("b"), ring.mediaKey("a"), ring.mediaKey("b"),
        ).map { it.toHex() }

        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertEquals(64, it.length) }
        assertEquals(keys[1], KeyRing(master.copyOf(), crypto).accountDbKey("a").toHex())

        ring.destroy()
        assertFailsWith<IllegalStateException> { ring.registryDbKey() }
    }
}
