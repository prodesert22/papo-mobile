package chat.papo.mobile

import chat.papo.mobile.chat.NotificationOpen
import chat.papo.mobile.chat.NotificationOpenRequests
import chat.papo.mobile.chat.NotificationPermission
import chat.papo.mobile.chat.NotificationPermissionPrompt
import chat.papo.mobile.chat.NotificationTarget
import chat.papo.mobile.security.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O que vem de tocar numa notificação (conferência do pedido, fila de um pedido só) e a explicação única da permissão. */
class NotificationEntryTest {
    private val conta = "6f1d1b0a-1111-4111-8111-111111111111"
    private val canal = "7a2e2c1b-2222-4222-8222-222222222222"
    private val mensagem = "8b3f3d2c-3333-4333-8333-333333333333"
    private val instante = "2026-01-01T10:00:00Z"

    private fun parse(
        account: String? = conta,
        conversation: String? = canal,
        message: String? = mensagem,
        createdAt: String? = instante,
        approximate: Boolean = false,
    ) = NotificationOpen.parse(account, conversation, message, createdAt, approximate)

    // Conferência do pedido

    @Test
    fun pedidoCompletoVira() {
        assertEquals(NotificationOpen(conta, canal, NotificationTarget(mensagem, instante, approximate = false)), parse())
        assertEquals(true, parse(approximate = true)?.target?.approximate)
    }

    @Test
    fun semContaOuSemConversaNaoVale() {
        assertNull(parse(account = null))
        assertNull(parse(conversation = null))
    }

    @Test
    fun idForaDoFormatoNaoVale() {
        assertNull(parse(account = ""))
        assertNull(parse(conversation = "../../x"))
        assertNull(parse(account = "a b"))
        assertNull(parse(conversation = "x".repeat(65)))
    }

    @Test
    fun semMensagemOuComMensagemInvalidaAbreSoAConversa() {
        assertNull(parse(message = null)?.target)
        assertNull(parse(message = "a/b")?.target)
        assertEquals(canal, parse(message = null)?.conversationId)
    }

    @Test
    fun semInstanteLegivelAbreSoAConversa() {
        assertNull(parse(createdAt = null)?.target)
        assertNull(parse(createdAt = "ontem")?.target)
        assertNull(parse(createdAt = "2026-01-01T10:00:00Z" + " ".repeat(40))?.target)
        assertEquals(conta, parse(createdAt = "ontem")?.accountId)
    }

    // Fila de pedidos

    @Test
    fun umPedidoNovoTomaOLugarDoQueNaoFoiAtendido() {
        val requests = NotificationOpenRequests()
        val first = NotificationOpen(conta, canal, null)
        val second = NotificationOpen(conta, "outro", null)

        requests.submit(first)
        requests.submit(second)

        assertEquals(second, requests.pending.value)
    }

    @Test
    fun atenderUmPedidoOTira() {
        val requests = NotificationOpenRequests()
        val request = NotificationOpen(conta, canal, null)
        requests.submit(request)

        requests.consume(request)

        assertNull(requests.pending.value)
    }

    @Test
    fun atenderUmPedidoVelhoNaoTiraOQueChegouDepois() {
        val requests = NotificationOpenRequests()
        val old = NotificationOpen(conta, canal, null)
        val new = NotificationOpen(conta, "outro", null)
        requests.submit(old)
        requests.submit(new)

        requests.consume(old)

        assertEquals(new, requests.pending.value)
    }

    // Explicação única da permissão

    private class FakePrefs : AppPrefs {
        val values = HashMap<String, String?>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    private class FakePermission(granted: Boolean, override var canRequest: Boolean) : NotificationPermission {
        private val state = MutableStateFlow(granted)
        override val granted: StateFlow<Boolean> = state
        var requests = 0
        override suspend fun request(): Boolean {
            requests++
            return state.value
        }

        override fun openSettings() = Unit
        override fun refresh() = Unit
    }

    @Test
    fun explicaUmaVezQuandoHaPedidoAFazerESemPermissao() {
        val prompt = NotificationPermissionPrompt(FakePermission(granted = false, canRequest = true), FakePrefs())

        assertTrue(prompt.explaining.value)
    }

    @Test
    fun naoExplicaComAPermissaoJaConcedida() {
        val prompt = NotificationPermissionPrompt(FakePermission(granted = true, canRequest = false), FakePrefs())

        assertFalse(prompt.explaining.value)
    }

    @Test
    fun naoExplicaOndeOSistemaNaoTemPedido() {
        val prompt = NotificationPermissionPrompt(FakePermission(granted = false, canRequest = false), FakePrefs())

        assertFalse(prompt.explaining.value)
    }

    @Test
    fun aceitarMarcaComoVistaEPedeAoSistema() = runTest {
        val permission = FakePermission(granted = false, canRequest = true)
        val prefs = FakePrefs()
        val prompt = NotificationPermissionPrompt(permission, prefs)

        prompt.accept()

        assertFalse(prompt.explaining.value)
        assertEquals(1, permission.requests)
        assertFalse(NotificationPermissionPrompt(permission, prefs).explaining.value, "não volta na próxima abertura")
    }

    @Test
    fun adiarMarcaComoVistaSemPedirAoSistema() {
        val permission = FakePermission(granted = false, canRequest = true)
        val prefs = FakePrefs()
        val prompt = NotificationPermissionPrompt(permission, prefs)

        prompt.decline()

        assertFalse(prompt.explaining.value)
        assertEquals(0, permission.requests)
        assertFalse(NotificationPermissionPrompt(permission, prefs).explaining.value)
    }
}
