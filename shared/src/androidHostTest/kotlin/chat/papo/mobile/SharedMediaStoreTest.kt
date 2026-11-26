package chat.papo.mobile

import chat.papo.mobile.platform.SharedMedia
import chat.papo.mobile.platform.SharedMediaStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** O depósito em memória que o provedor de compartilhamento lê: uma leitura por aplicativo, prazo, limpeza e capacidade. */
class SharedMediaStoreTest {
    private val clock = TestTimeSource()
    private val store = SharedMediaStore(timeSource = clock)
    private val photo = byteArrayOf(1, 2, 3, 4)

    private fun put(bytes: ByteArray = photo, name: String = "foto.jpg") = store.put(name, "image/jpeg", bytes)

    @Test
    fun oTokenTemAFormaDeUmTokenEDuasChamadasDaoTokensDiferentes() {
        val first = put()
        val second = put()
        assertTrue(SharedMediaStore.isToken(first))
        assertEquals(32, first.length)
        assertNotEquals(first, second)
    }

    @Test
    fun soTemFormaDeTokenOQueOStoreGera() {
        assertFalse(SharedMediaStore.isToken(""))
        assertFalse(SharedMediaStore.isToken("../../x"))
        assertFalse(SharedMediaStore.isToken("a".repeat(31)))
        assertFalse(SharedMediaStore.isToken("a".repeat(33)))
        assertFalse(SharedMediaStore.isToken("A".repeat(32)))
        assertFalse(SharedMediaStore.isToken("g".repeat(32)))
        assertTrue(SharedMediaStore.isToken("0123456789abcdef".repeat(2)))
    }

    @Test
    fun informaNomeTipoETamanhoSemLerOConteudo() {
        val token = store.put("viagem.png", "image/png", ByteArray(2_048))
        val info = assertNotNull(store.info(token))
        assertEquals("viagem.png", info.name)
        assertEquals("image/png", info.mimeType)
        assertEquals(2_048L, info.size)
        // Perguntar quantas vezes quiser não gasta a leitura.
        assertNotNull(store.info(token))
        assertNotNull(store.take(token, reader = 1))
    }

    @Test
    fun cadaLeitorLeUmaVezEOutroLeitorAindaLe() {
        val token = put()
        assertContentEquals(photo, store.take(token, reader = 10_001))
        // O mesmo aplicativo de novo: o pedido repetido não leva o conteúdo.
        assertNull(store.take(token, reader = 10_001))
        // A folha de compartilhamento leu a prévia, o aplicativo escolhido ainda lê o seu.
        assertContentEquals(photo, store.take(token, reader = 10_002))
        assertNull(store.take(token, reader = 10_002))
    }

    @Test
    fun entregaOMesmoArrayQueRecebeuSemCopiar() {
        val token = put()
        assertSame(photo, store.take(token, reader = 1))
    }

    @Test
    fun tokenQueNaoExisteNaoDaNada() {
        assertNull(store.info("0".repeat(32)))
        assertNull(store.take("0".repeat(32), reader = 1))
    }

    @Test
    fun expiraDoisMinutosDepoisDePostoEOQueAindaNaoVenceuValeAteLa() {
        val token = put()
        clock += 1.minutes + 59.seconds
        assertNotNull(store.info(token))
        clock += 2.seconds
        assertNull(store.info(token))
        assertNull(store.take(token, reader = 1))
    }

    @Test
    fun expiradoAntesDeLerNaoEntregaOConteudo() {
        val token = put()
        clock += 2.minutes
        assertNull(store.take(token, reader = 1))
    }

    @Test
    fun limparEsqueceTudoComoNoBloqueioDoApp() {
        val first = put()
        val second = put()
        store.clear()
        assertNull(store.info(first))
        assertNull(store.take(second, reader = 1))
        // Depois de limpo o depósito volta a servir.
        assertNotNull(store.take(put(), reader = 1))
    }

    @Test
    fun descartarEsqueceSoOTokenPedido() {
        val gone = put()
        val kept = put()
        store.discard(gone)
        assertNull(store.info(gone))
        assertNotNull(store.info(kept))
        store.discard("0".repeat(32)) // um token que não existe não faz nada
        assertNotNull(store.info(kept))
    }

    @Test
    fun passandoDaCapacidadeSaiOMaisAntigo() {
        val small = SharedMediaStore(timeSource = clock, capacity = 2)
        val oldest = small.put("a", "image/png", photo)
        val middle = small.put("b", "image/png", photo)
        val newest = small.put("c", "image/png", photo)
        assertNull(small.info(oldest))
        assertNotNull(small.info(middle))
        assertNotNull(small.info(newest))
    }

    @Test
    fun purgeSoltaSoOQueVenceu() {
        val old = put()
        clock += 90.seconds
        val recent = put()
        clock += 40.seconds // o primeiro tem 130 s, o segundo 40 s
        store.purge()
        assertNull(store.info(old))
        assertNotNull(store.info(recent))
    }
}

/** O tipo que o compartilhamento declara: ele vem do servidor, então só vale o que tem a forma de um tipo. */
class SharedMediaSendTypeTest {
    @Test
    fun tipoComAFormaDeTipoSegueComoVeio() {
        assertEquals("image/jpeg", SharedMedia.sendType("image/jpeg"))
        assertEquals("image/svg+xml", SharedMedia.sendType("image/svg+xml"))
        assertEquals("video/x-matroska", SharedMedia.sendType("video/x-matroska"))
        assertEquals("IMAGE/PNG", SharedMedia.sendType("IMAGE/PNG"))
    }

    @Test
    fun oQueNaoTemAFormaDeUmTipoViraArquivoQualquer() {
        for (text in listOf("", "image", "image/", "/png", "image/*", "image/png; charset=x", " image/png", "image/png\n", "a/b/c", "../etc/passwd")) {
            assertEquals("application/octet-stream", SharedMedia.sendType(text), text)
        }
    }
}
