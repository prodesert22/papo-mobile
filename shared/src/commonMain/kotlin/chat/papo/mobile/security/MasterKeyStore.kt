package chat.papo.mobile.security

/** Como a chave mestra está guardada no aparelho. */
enum class KeyProtection {
    /** Ainda não existe chave (primeira execução ou dados apagados). */
    None,

    /** Embrulhada por chave do hardware, sem exigir autenticação. */
    Device,

    /** Embrulhada por chave que só opera após biometria ou credencial do aparelho. */
    UserAuth,
}

enum class UserAuthAvailability { Available, NotEnrolled, Unsupported }

sealed interface KeyResult {
    class Success(val masterKey: ByteArray) : KeyResult

    /** O usuário fechou o pedido de autenticação. */
    data object Cancelled : KeyResult

    /** A chave do hardware foi invalidada de vez; os dados locais não abrem mais. */
    data object Invalidated : KeyResult

    data class Failed(val message: String) : KeyResult
}

data class AuthPrompt(val title: String, val subtitle: String)

/**
 * Guarda a chave mestra embrulhada por uma chave presa ao hardware.
 * Implementado pela plataforma (Android Keystore; Keychain/Secure Enclave no iOS).
 */
interface MasterKeyStore {
    fun protection(): KeyProtection

    fun userAuthAvailability(): UserAuthAvailability

    /** Gera uma chave mestra nova com proteção [KeyProtection.Device]. */
    suspend fun create(): KeyResult

    /** Recupera a chave mestra; com [KeyProtection.UserAuth] mostra o pedido de autenticação. */
    suspend fun unlock(prompt: AuthPrompt): KeyResult

    /**
     * Troca a proteção da chave mestra já aberta. Ativar [userAuth] pede
     * autenticação, o que confirma que o usuário consegue desbloquear depois.
     */
    suspend fun protect(masterKey: ByteArray, userAuth: Boolean, prompt: AuthPrompt): KeyResult

    /** Remove a chave embrulhada e as chaves do hardware. */
    fun wipe()
}

/** Preferências não sigilosas do app (ficam fora do banco cifrado). */
interface AppPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Apaga tudo o que o app gravou no aparelho, exceto a chave mestra. */
fun interface LocalDataWiper {
    fun wipeAll()
}
