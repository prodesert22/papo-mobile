package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.ProfileCache
import chat.papo.mobile.chat.ProfileStamp
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.platform.JcaCrypto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Os perfis lidos na sessão: um pedido por usuário enquanto a leitura vale, e o avatar que vem nele no cache de mídia.
 * O motor HTTP falso responde em outra thread (`waitUntil`) e o relógio da validade é o virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileCacheTest {
    private val crypto = JcaCrypto()
    private val key = crypto.randomBytes(32)
    private val picture = byteArrayOf(5, 4, 3, 2, 1)
    private val sha = "b".repeat(64)

    /** Um servidor Papo com o perfil de qualquer usuário; [nickname] e [avatarBlob] mudam o que ele responde. */
    private class Server(private val sha: String) {
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var status = HttpStatusCode.OK
        @Volatile var nickname = "Ana Lima"
        @Volatile var avatarBlob: String? = null
        @Volatile var gate: CompletableDeferred<Unit>? = null

        val engine = MockEngine { request ->
            requests += request.url.encodedPath
            gate?.await()
            val id = request.url.encodedPath.removePrefix("/users/").removeSuffix("/profile")
            if (status.isSuccess()) {
                val blob = avatarBlob?.let { "\"$it\"" } ?: "null"
                respond(
                    """{"id":"$id","username":"ana","nickname":"$nickname","avatar_blob":$blob,"avatar_format":"PNG",""" +
                        """"banner_media":"$sha","description":"Gosto de café","status_message":"Em reunião",""" +
                        """"created_at":"2026-01-04T10:30:00Z","roles":[{"id":"r1","name":"Moderação","color":"#E86CA5"}]}""",
                    status,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    """{"type":"t","title":"Erro","status":${status.value},"detail":"falhou"}""",
                    status,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
        }
    }

    /** O que o app sabe do usuário agora; os testes o mudam como os eventos `presence_update` e `avatar_update` mudariam. */
    private var stamp = ProfileStamp(presence = null, avatarVersion = 3L, nickname = "Ana Lima")

    private fun TestScope.cache() = MediaCache(TestResources.tempDir(), key, crypto, StandardTestDispatcher(testScheduler))

    private fun TestScope.profiles(
        server: Server,
        media: MediaCache = cache(),
        scope: CoroutineScope = backgroundScope,
        stampOf: (String) -> ProfileStamp = { stamp },
    ) = ProfileCache(
        api = ChatApi(PapoClient(server.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))),
        media = media,
        scope = scope,
        background = StandardTestDispatcher(testScheduler),
        stampOf = stampOf,
        timeSource = testScheduler.timeSource,
        validFor = 5.minutes,
    )

    @Test
    fun segundaLeituraDentroDaValidadeNaoVaiAoServidor() = runTest {
        val server = Server(sha)
        val profiles = profiles(server)

        val first = profiles.get("u2")
        val second = profiles.get("u2")

        assertEquals(first, second)
        assertEquals("Ana Lima", first.displayName)
        assertEquals("Gosto de café", first.description)
        assertEquals(sha, first.bannerSha)
        assertEquals(listOf("/users/u2/profile"), server.requests.toList())
    }

    @Test
    fun cadaUsuarioTemASuaLeitura() = runTest {
        val server = Server(sha)
        val profiles = profiles(server)

        profiles.get("u2")
        profiles.get("u3")
        profiles.get("u2")

        assertEquals(listOf("/users/u2/profile", "/users/u3/profile"), server.requests.toList())
    }

    @Test
    fun depoisDeCincoMinutosLeDeNovo() = runTest {
        val server = Server(sha)
        val profiles = profiles(server)
        profiles.get("u2")

        advanceTimeBy(4.minutes + 59.seconds)
        profiles.get("u2")
        assertEquals(1, server.requests.size)

        advanceTimeBy(2.seconds)
        server.nickname = "Ana Souza"
        assertEquals("Ana Souza", profiles.get("u2").nickname)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun mudarAPresencaOApelidoOuOAvatarInvalidaALeitura() = runTest {
        val server = Server(sha)
        val profiles = profiles(server)
        profiles.get("u2")

        stamp = stamp.copy(presence = Presence("online", null))
        profiles.get("u2")
        assertEquals(2, server.requests.size)

        // O mesmo estado de novo: a leitura nova vale.
        profiles.get("u2")
        assertEquals(2, server.requests.size)

        stamp = stamp.copy(presence = Presence("away", "volto já"))
        profiles.get("u2")
        assertEquals(3, server.requests.size)

        stamp = stamp.copy(avatarVersion = 4L)
        profiles.get("u2")
        assertEquals(4, server.requests.size)

        stamp = stamp.copy(nickname = "Ana Souza")
        profiles.get("u2")
        assertEquals(5, server.requests.size)
    }

    @Test
    fun mudancaDeOutroUsuarioNaoInvalidaALeitura() = runTest {
        val server = Server(sha)
        val states = mutableMapOf("u2" to stamp, "u3" to stamp)
        val profiles = profiles(server, stampOf = { states.getValue(it) })
        profiles.get("u2")

        states["u3"] = stamp.copy(presence = Presence("busy", null))
        profiles.get("u2")

        assertEquals(1, server.requests.size)
    }

    @Test
    fun refreshLeDoServidorMesmoDentroDaValidade() = runTest {
        val server = Server(sha)
        val profiles = profiles(server)
        profiles.get("u2")

        server.nickname = "Ana Souza"

        assertEquals("Ana Souza", profiles.get("u2", refresh = true).nickname)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun pedidosSimultaneosDoMesmoUsuarioDividemUmaLeitura() = runTest {
        val server = Server(sha).also { it.gate = CompletableDeferred() }
        val profiles = profiles(server)

        val callers = List(5) { async { profiles.get("u2") } }
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()
        server.gate!!.complete(Unit)

        val results = callers.map { it.await() }
        assertTrue(results.all { it == results.first() })
        assertEquals(1, server.requests.size)
    }

    @Test
    fun cancelarQuemPediuPrimeiroNaoDeixaOsOutrosSemRespostaNemRepetePedido() = runTest {
        val server = Server(sha).also { it.gate = CompletableDeferred() }
        val profiles = profiles(server)
        val first = launch { profiles.get("u2") }
        val second = async { profiles.get("u2") }
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()

        first.cancel()
        runCurrent()
        server.gate!!.complete(Unit)

        assertEquals("Ana Lima", second.await().displayName)
        assertTrue(first.isCancelled)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun fecharASessaoNoMeioDaLeituraCancelaQuemEspera() = runTest {
        val server = Server(sha).also { it.gate = CompletableDeferred() }
        val session = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val profiles = profiles(server, scope = session)
        val waiter = async { runCatching { profiles.get("u2") } }
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()

        session.cancel()
        runCurrent()

        assertTrue(waiter.await().exceptionOrNull() is CancellationException)
    }

    @Test
    fun usuarioInexistenteFalhaComOErroDoServidorENaoFicaGuardado() = runTest {
        val server = Server(sha).also { it.status = HttpStatusCode.NotFound }
        val profiles = profiles(server)

        val error = assertFailsWith<ApiException> { profiles.get("u2") }
        assertTrue(error.isNotFound)

        // Nada de "não achado" guardado: se o servidor passa a ter o usuário, a próxima leitura o traz.
        server.status = HttpStatusCode.OK
        assertEquals("Ana Lima", profiles.get("u2").displayName)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun erroDoServidorChegaAQuemPediuENaoFicaGuardado() = runTest {
        val server = Server(sha).also { it.status = HttpStatusCode.InternalServerError }
        val profiles = profiles(server)

        assertEquals(500, assertFailsWith<ApiException> { profiles.get("u2") }.status)
        assertEquals(500, assertFailsWith<ApiException> { profiles.get("u2", refresh = true) }.status)

        server.status = HttpStatusCode.OK
        assertEquals("Ana Lima", profiles.get("u2").displayName)
        assertEquals(3, server.requests.size)
    }

    @Test
    fun avatarDoPerfilVaiParaOCacheDeMidiaComONomeDoAvatarDasMensagens() = runTest {
        val server = Server(sha).also { it.avatarBlob = Base64.encode(picture) }
        val media = cache()
        val profiles = profiles(server, media)

        profiles.get("u2")

        assertContentEquals(picture, media.peek("avatar/u2/3"))
        // O avatar de outra versão ou de outro usuário não existe.
        assertNull(media.peek("avatar/u2/4"))
        assertNull(media.peek("avatar/u3/3"))
    }

    @Test
    fun semVersaoConhecidaOAvatarFicaDeForaMasOPerfilChega() = runTest {
        val server = Server(sha).also { it.avatarBlob = Base64.encode(picture) }
        val media = cache()
        val profiles = profiles(server, media, stampOf = { ProfileStamp(null, avatarVersion = null, nickname = null) })

        assertEquals("Ana Lima", profiles.get("u2").displayName)

        assertNull(media.peek("avatar/u2/0"))
    }

    @Test
    fun avatarVazioOuIlegivelNaoGuardaNadaENaoDerrubaOPerfil() = runTest {
        val server = Server(sha)
        val media = cache()
        val profiles = profiles(server, media)

        for (blob in listOf(null, "", "   ", "%%%não é base64%%%")) {
            server.avatarBlob = blob
            assertEquals("Ana Lima", profiles.get("u2", refresh = true).displayName)
            assertNull(media.peek("avatar/u2/3"), "blob=$blob")
        }
    }

    @Test
    fun avatarNovoSubstituiOGuardadoDaMesmaVersao() = runTest {
        val server = Server(sha).also { it.avatarBlob = Base64.encode(picture) }
        val media = cache()
        val profiles = profiles(server, media)
        profiles.get("u2")

        server.avatarBlob = Base64.encode(byteArrayOf(9, 9))
        profiles.get("u2", refresh = true)

        assertContentEquals(byteArrayOf(9, 9), media.peek("avatar/u2/3"))
    }

    @Test
    fun duasContasNaoCompartilhamPerfilNemAvatar() = runTest {
        val first = Server(sha).also { it.avatarBlob = Base64.encode(picture); it.nickname = "Ana Lima" }
        val second = Server(sha).also { it.nickname = "Ana Souza" }
        val firstMedia = cache()
        val secondMedia = cache()
        val firstProfiles = profiles(first, firstMedia)
        val secondProfiles = profiles(second, secondMedia)

        assertEquals("Ana Lima", firstProfiles.get("u2").displayName)
        assertEquals("Ana Souza", secondProfiles.get("u2").displayName)

        assertEquals(1, first.requests.size)
        assertEquals(1, second.requests.size)
        assertContentEquals(picture, firstMedia.peek("avatar/u2/3"))
        assertNull(secondMedia.peek("avatar/u2/3"))
    }
}
