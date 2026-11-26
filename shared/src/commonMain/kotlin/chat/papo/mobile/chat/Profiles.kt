package chat.papo.mobile.chat

import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.RoleSummaryDto
import chat.papo.mobile.data.api.UserProfileDto
import chat.papo.mobile.media.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** O perfil de um usuário como a tela o mostra. O avatar não vem aqui: está no cache de mídia, como o das mensagens. */
data class UserProfile(
    val id: String,
    val username: String,
    val nickname: String?,
    val description: String?,
    /** Referência do banner (`GET /media/{sha}`); null sem banner ou se o valor não serve de caminho. */
    val bannerSha: String?,
    val statusMessage: String?,
    /** Quando a conta foi criada; null se o servidor não mandou ou mandou algo ilegível. */
    val memberSince: Instant?,
    val roles: List<RoleSummaryDto>,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
}

/** Letras, dígitos, `-` e `_`: o que cabe num segmento de caminho sem mudá-lo (um sha em hexadecimal ou base64 de URL). */
private val PATH_SEGMENT = Regex("[0-9A-Za-z_-]+")

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun UserProfileDto.toProfile() = UserProfile(
    id = id,
    username = username,
    nickname = nickname.trimmedOrNull(),
    description = description.trimmedOrNull(),
    bannerSha = bannerMedia?.takeIf { PATH_SEGMENT.matches(it) },
    statusMessage = statusMessage.trimmedOrNull(),
    memberSince = parseInstant(createdAt),
    roles = roles,
)

/** Nome, @usuário e recado que o alto do perfil mostra. */
data class ProfileHeader(val displayName: String, val username: String, val statusMessage: String?)

/**
 * O alto do perfil. O que o app sincroniza e recebe ao vivo ([known]) vale mais que o perfil lido, que pode ter até
 * cinco minutos e só cobre quem o app não conhece. Null enquanto não se sabe nada do usuário.
 */
fun profileHeader(known: User?, profile: UserProfile?): ProfileHeader? = when {
    known != null -> ProfileHeader(known.displayName, known.username, known.statusMessage.trimmedOrNull())
    profile != null -> ProfileHeader(profile.displayName, profile.username, profile.statusMessage)
    else -> null
}

/** Nome, no cache de mídia, do avatar de [userId] em [version]: o mesmo que o avatar das mensagens usa. */
fun avatarMediaName(userId: String, version: Long) = "avatar/$userId/$version"

/**
 * O que o app sabia de um usuário quando o perfil foi lido. Os eventos `presence_update` (presença, recado e apelido)
 * e `avatar_update` mudam o que o app guarda dele; se algo disso mudou depois da leitura, ela não vale mais.
 */
data class ProfileStamp(val presence: Presence?, val avatarVersion: Long?, val nickname: String?)

fun profileStamp(user: User?, presence: Presence?) = ProfileStamp(presence, user?.avatarVersion, user?.nickname)

/**
 * Perfis lidos na sessão de uma conta, só na memória: descrição e banner não têm evento no WebSocket, então a leitura
 * vale por [validFor] e some junto com a sessão. Antes disso, só uma mudança do que [stampOf] informa a invalida.
 * Quem pede junto, pelo mesmo usuário, divide um único pedido, que pertence ao [scope] da sessão e não a quem pediu
 * primeiro. Cada conta tem o seu [ProfileCache]: nada é compartilhado entre contas.
 *
 * O avatar que vem no perfil vai para o cache de mídia cifrado, com o nome que o avatar das mensagens usa, e a
 * imagem não é pedida de novo. Se o app não conhece o usuário, não há versão e nome certo, e o avatar fica de fora.
 */
class ProfileCache(
    private val api: ChatApi,
    private val media: MediaCache,
    private val scope: CoroutineScope,
    /** Onde a imagem do avatar é decodificada de base64. */
    private val background: CoroutineDispatcher,
    private val stampOf: (String) -> ProfileStamp,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val validFor: Duration = VALID_FOR,
) {
    private class Entry(val profile: UserProfile, val stamp: ProfileStamp, val readAt: TimeMark)

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()
    private val loading = HashMap<String, Deferred<Result<UserProfile>>>()

    /**
     * O perfil de [userId]: o guardado, se vale, senão o do servidor. Com [refresh] lê do servidor mesmo assim (o botão
     * de tentar de novo). Falha com o erro do pedido, como `ApiException` com 404 para um usuário que não existe.
     */
    suspend fun get(userId: String, refresh: Boolean = false): UserProfile {
        val pending = mutex.withLock {
            val entry = entries[userId]
            if (!refresh && entry != null && entry.readAt.elapsedNow() < validFor && entry.stamp == stampOf(userId)) {
                return entry.profile
            }
            loading.getOrPut(userId) { scope.async { read(userId) } }
        }
        return pending.await().getOrThrow()
    }

    /** O erro volta dentro do `Result`: um `async` que falha cancelaria o escopo da sessão, e quem o pediu já o recebe. */
    private suspend fun read(userId: String): Result<UserProfile> {
        try {
            val stamp = stampOf(userId)
            val dto = api.profile(userId)
            storeAvatar(userId, stamp, dto.avatarBlob)
            val profile = dto.toProfile()
            mutex.withLock { entries[userId] = Entry(profile, stamp, timeSource.markNow()) }
            return Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            withContext(NonCancellable) { mutex.withLock { loading.remove(userId) } }
        }
    }

    private suspend fun storeAvatar(userId: String, stamp: ProfileStamp, blob: String?) {
        val version = stamp.avatarVersion ?: return
        if (blob.isNullOrBlank()) return
        val bytes = withContext(background) { runCatching { Base64.decode(blob) }.getOrNull() } ?: return
        if (bytes.isNotEmpty()) media.put(avatarMediaName(userId, version), bytes)
    }

    companion object {
        val VALID_FOR = 5.minutes
    }
}
