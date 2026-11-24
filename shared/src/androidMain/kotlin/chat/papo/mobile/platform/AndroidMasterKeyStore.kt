package chat.papo.mobile.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.security.AuthPrompt
import chat.papo.mobile.security.KeyProtection
import chat.papo.mobile.security.KeyResult
import chat.papo.mobile.security.MasterKeyStore
import chat.papo.mobile.security.UserAuthAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Chave mestra embrulhada por uma chave AES-GCM do Android Keystore.
 *
 * Com o bloqueio ligado, a chave do Keystore exige autenticação a cada uso
 * (biometria forte ou credencial do aparelho): o sistema só libera a operação
 * de decifrar depois do [BiometricPrompt], então sem autenticar não há como
 * obter a chave mestra, mesmo com acesso aos arquivos do app.
 *
 * Arquivo: `versão (1) | modo (1) | nonce (12) | chave mestra cifrada`.
 */
class AndroidMasterKeyStore(
    private val context: Context,
    private val crypto: Crypto,
    private val activities: ActivityHolder,
    /** Nome do cofre: define o arquivo e os aliases no Keystore. Os testes usam outro para não tocar no do app. */
    name: String = "master",
) : MasterKeyStore {
    private val file = File(context.noBackupFilesDir, "$name.key")
    private val aliasDevice = "papo.$name.device"
    private val aliasUserAuth = "papo.$name.userauth"
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun protection(): KeyProtection {
        val bytes = readFile() ?: return KeyProtection.None
        return if (bytes[1] == MODE_USER_AUTH) KeyProtection.UserAuth else KeyProtection.Device
    }

    override fun userAuthAvailability(): UserAuthAvailability =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> UserAuthAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> UserAuthAvailability.NotEnrolled
            else -> UserAuthAvailability.Unsupported
        }

    override suspend fun create(): KeyResult = withContext(Dispatchers.IO) {
        val master = crypto.randomBytes(Crypto.KEY_SIZE)
        try {
            wipe()
            val cipher = encryptCipher(deviceKey(create = true))
            writeFile(MODE_DEVICE, cipher.iv, cipher.doFinal(master))
            KeyResult.Success(master)
        } catch (e: Exception) {
            master.fill(0)
            KeyResult.Failed(e.message ?: "Falha ao criar a chave do aparelho")
        }
    }

    override suspend fun unlock(prompt: AuthPrompt): KeyResult {
        val bytes = readFile() ?: return KeyResult.Failed("Chave do aparelho ausente")
        val nonce = bytes.copyOfRange(2, 2 + Crypto.NONCE_SIZE)
        val wrapped = bytes.copyOfRange(2 + Crypto.NONCE_SIZE, bytes.size)
        val userAuth = bytes[1] == MODE_USER_AUTH
        return try {
            val key = (if (userAuth) userAuthKey(create = false) else deviceKey(create = false))
                ?: return KeyResult.Invalidated
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(Crypto.TAG_SIZE * 8, nonce))
            if (!userAuth) return KeyResult.Success(cipher.doFinal(wrapped))
            when (val auth = authenticate(cipher, prompt)) {
                is AuthOutcome.Ok -> KeyResult.Success(auth.cipher.doFinal(wrapped))
                AuthOutcome.Cancelled -> KeyResult.Cancelled
                is AuthOutcome.Error -> KeyResult.Failed(auth.message)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            KeyResult.Invalidated
        } catch (e: UnrecoverableKeyException) {
            KeyResult.Invalidated
        } catch (e: javax.crypto.AEADBadTagException) {
            KeyResult.Invalidated
        } catch (e: Exception) {
            KeyResult.Failed(e.message ?: "Falha ao abrir a chave do aparelho")
        }
    }

    override suspend fun protect(masterKey: ByteArray, userAuth: Boolean, prompt: AuthPrompt): KeyResult {
        return try {
            if (userAuth) {
                deleteKey(aliasUserAuth)
                val cipher = encryptCipher(userAuthKey(create = true)!!)
                when (val auth = authenticate(cipher, prompt)) {
                    is AuthOutcome.Ok -> {
                        writeFile(MODE_USER_AUTH, auth.cipher.iv, auth.cipher.doFinal(masterKey))
                        deleteKey(aliasDevice)
                        KeyResult.Success(masterKey)
                    }
                    AuthOutcome.Cancelled -> {
                        deleteKey(aliasUserAuth)
                        KeyResult.Cancelled
                    }
                    is AuthOutcome.Error -> {
                        deleteKey(aliasUserAuth)
                        KeyResult.Failed(auth.message)
                    }
                }
            } else {
                deleteKey(aliasDevice)
                val cipher = encryptCipher(deviceKey(create = true)!!)
                writeFile(MODE_DEVICE, cipher.iv, cipher.doFinal(masterKey))
                deleteKey(aliasUserAuth)
                KeyResult.Success(masterKey)
            }
        } catch (e: Exception) {
            KeyResult.Failed(e.message ?: "Falha ao trocar a proteção da chave")
        }
    }

    override fun wipe() {
        file.delete()
        deleteKey(aliasDevice)
        deleteKey(aliasUserAuth)
    }

    private fun encryptCipher(key: SecretKey?): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    private fun deviceKey(create: Boolean): SecretKey? =
        existingKey(aliasDevice) ?: if (create) generateKey(aliasDevice) { } else null

    private fun userAuthKey(create: Boolean): SecretKey? =
        existingKey(aliasUserAuth) ?: if (!create) null else generateKey(aliasUserAuth) {
            setUserAuthenticationRequired(true)
            // 0 = autenticação a cada operação, amarrada ao CryptoObject do pedido.
            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // A credencial do aparelho já é aceita; cadastrar outra digital não precisa invalidar a chave.
            setInvalidatedByBiometricEnrollment(false)
        }

    private fun existingKey(alias: String): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    private fun generateKey(alias: String, configure: KeyGenParameterSpec.Builder.() -> Unit): SecretKey {
        fun build(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply(configure)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }
        return try {
            build(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            build(strongBox = false)
        }
    }

    private fun deleteKey(alias: String) {
        runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
    }

    private fun readFile(): ByteArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val minimum = 2 + Crypto.NONCE_SIZE + Crypto.KEY_SIZE + Crypto.TAG_SIZE
        return bytes.takeIf { it.size >= minimum && it[0] == VERSION }
    }

    private fun writeFile(mode: Byte, nonce: ByteArray, wrapped: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(VERSION, mode) + nonce + wrapped)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "não foi possível gravar a chave embrulhada" }
        }
    }

    private sealed interface AuthOutcome {
        class Ok(val cipher: Cipher) : AuthOutcome
        data object Cancelled : AuthOutcome
        class Error(val message: String) : AuthOutcome
    }

    private suspend fun authenticate(cipher: Cipher, prompt: AuthPrompt): AuthOutcome = withContext<AuthOutcome>(Dispatchers.Main) {
        val activity = activities.current
            ?: return@withContext AuthOutcome.Error("O app precisa estar aberto para autenticar")
        suspendCancellableCoroutine<AuthOutcome> { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher
                    if (continuation.isActive) {
                        continuation.resume(
                            if (unlocked != null) AuthOutcome.Ok(unlocked) else AuthOutcome.Error("Autenticação sem chave"),
                        )
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    continuation.resume(if (cancelled) AuthOutcome.Cancelled else AuthOutcome.Error(errString.toString()))
                }
            }
            val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(prompt.title)
                .setSubtitle(prompt.subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build()
            biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            continuation.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val MODE_DEVICE: Byte = 1
        const val MODE_USER_AUTH: Byte = 2
    }
}
