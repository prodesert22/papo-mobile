package chat.papo.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.toHex
import chat.papo.mobile.platform.ActivityHolder
import chat.papo.mobile.platform.AndroidMasterKeyStore
import chat.papo.mobile.platform.JcaCrypto
import chat.papo.mobile.security.AuthPrompt
import chat.papo.mobile.security.KeyProtection
import chat.papo.mobile.security.KeyResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A chave mestra no Android Keystore de verdade. Usa um cofre próprio
 * ("teste"), sem tocar na chave do app instalado.
 */
@RunWith(AndroidJUnit4::class)
class MasterKeyStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val crypto = JcaCrypto()
    private val prompt = AuthPrompt("teste", "teste")
    private val file get() = File(context.noBackupFilesDir, "$NAME.key")

    private fun store() = AndroidMasterKeyStore(context, crypto, ActivityHolder(), NAME)

    @Before
    @After
    fun clean() = store().wipe()

    @Test
    fun criaAbreESobreviveAUmNovoProcesso() = runTest {
        val store = store()
        assertEquals(KeyProtection.None, store.protection())

        val created = assertIs<KeyResult.Success>(store.create()).masterKey
        assertEquals(Crypto.KEY_SIZE, created.size)
        assertEquals(KeyProtection.Device, store.protection())

        assertContentEquals(created, assertIs<KeyResult.Success>(store.unlock(prompt)).masterKey)
        // Outra instância lê do disco e do Keystore, como numa nova execução do app.
        assertContentEquals(created, assertIs<KeyResult.Success>(store().unlock(prompt)).masterKey)
    }

    @Test
    fun oArquivoNaoContemAChaveEAChaveDoHardwareNaoSaiDoKeystore() = runTest {
        val master = assertIs<KeyResult.Success>(store().create()).masterKey
        val onDisk = file.readBytes()

        assertFalse(master.toHex() in onDisk.toHex(), "a chave mestra não pode estar em claro no arquivo")
        // versão + modo + nonce + chave cifrada + tag
        assertEquals(2 + Crypto.NONCE_SIZE + Crypto.KEY_SIZE + Crypto.TAG_SIZE, onDisk.size)

        val entry = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("papo.$NAME.device", null)
        assertEquals("AES", entry.algorithm)
        assertEquals(null, entry.encoded, "material da chave do hardware não pode ser exportável")
    }

    @Test
    fun cadaCofreTemUmaChaveDiferente() = runTest {
        val first = assertIs<KeyResult.Success>(store().create()).masterKey
        val second = assertIs<KeyResult.Success>(store().create()).masterKey
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun arquivoAdulteradoNaoAbre() = runTest {
        val store = store()
        store.create()
        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        // O app trata isso como chave perdida: apaga os dados locais e recomeça.
        assertEquals(KeyResult.Invalidated, store.unlock(prompt))
    }

    @Test
    fun arquivoSemAChaveDoHardwareNaoAbre() = runTest {
        val store = store()
        store.create()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("papo.$NAME.device")
        assertEquals(KeyResult.Invalidated, store.unlock(prompt))
    }

    @Test
    fun trocarAProtecaoSemBloqueioMantemAMesmaChave() = runTest {
        val store = store()
        val master = assertIs<KeyResult.Success>(store.create()).masterKey
        val before = file.readBytes()

        assertIs<KeyResult.Success>(store.protect(master.copyOf(), userAuth = false, prompt = prompt))

        assertEquals(KeyProtection.Device, store.protection())
        assertFalse(before.contentEquals(file.readBytes())) // reembrulhada com chave e nonce novos
        assertContentEquals(master, assertIs<KeyResult.Success>(store.unlock(prompt)).masterKey)
    }

    @Test
    fun apagarRemoveArquivoEChaves() = runTest {
        val store = store()
        store.create()
        store.wipe()

        assertEquals(KeyProtection.None, store.protection())
        assertFalse(file.exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("papo.$NAME.device"))
        assertIs<KeyResult.Failed>(store.unlock(prompt))
    }

    @Test
    fun disponibilidadeDeBiometriaRespondeSemPrecisarDeTela() {
        // O valor depende do aparelho; o que importa é não exigir Activity nem lançar.
        assertTrue(store().userAuthAvailability().name.isNotEmpty())
    }

    private companion object {
        const val NAME = "teste"
    }
}
