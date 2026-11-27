package chat.papo.mobile

import chat.papo.mobile.chat.WebhookAvatars
import chat.papo.mobile.chat.avatarMediaName
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.media.attachmentOriginalKey
import chat.papo.mobile.media.attachmentThumbKey
import chat.papo.mobile.media.embedAuthorKey
import chat.papo.mobile.media.embedImageKey
import chat.papo.mobile.media.posterKey
import chat.papo.mobile.platform.JcaCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A varredura da mídia de mensagem no [MediaCache]: o que sai, o que fica e o que nunca é tocado. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheSweepTest {
    private val crypto = JcaCrypto()
    private val key = crypto.randomBytes(32)
    private val directory = TestResources.tempDir()
    private val bytes = "imagem".encodeToByteArray()

    private fun TestScope.cache() = MediaCache(directory, key, crypto, StandardTestDispatcher(testScheduler))

    private fun messageFiles(): List<File> = File(directory, MediaCache.MESSAGE_AREA).listFiles().orEmpty().toList()

    private fun looseFiles(): List<File> = File(directory).listFiles().orEmpty().filter { it.isFile }

    @Test
    fun mensagemEOrestoVivemEmPastasDiferentes() = runTest(timeout = 5.seconds) {
        val cache = cache()
        cache.put(attachmentThumbKey("a1"), bytes)
        cache.put(avatarMediaName("u1", 1), bytes)

        assertEquals(1, messageFiles().size)
        assertEquals(1, looseFiles().size)
    }

    @Test
    fun varrerMantemSoOQueEstaNaLista() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val keep = setOf(attachmentThumbKey("a1"), attachmentOriginalKey("a1"), posterKey("a1"), embedImageKey("e1"), embedAuthorKey("e1"))
        val gone = setOf(attachmentThumbKey("a2"), attachmentOriginalKey("a2"), posterKey("a2"), embedImageKey("e2"), embedAuthorKey("e2"))
        (keep + gone).forEach { cache.put(it, bytes) }

        cache.sweepMessageMedia(keep)

        keep.forEach { assertContentEquals(bytes, cache.peek(it), it) }
        gone.forEach { assertNull(cache.peek(it), it) }
        assertEquals(keep.size, messageFiles().size)
    }

    @Test
    fun varrerComListaVaziaEsvaziaAAreaDasMensagens() = runTest(timeout = 5.seconds) {
        val cache = cache()
        cache.put(attachmentThumbKey("a1"), bytes)

        cache.sweepMessageMedia(emptySet())

        assertTrue(messageFiles().isEmpty())
        assertFalse(cache.has(attachmentThumbKey("a1")))
    }

    @Test
    fun varrerNaoTocaEmAvataresEmojisBannersNemAvataresDeWebhook() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val others = listOf(avatarMediaName("u1", 2), "emoji/e1", "media/abc123", WebhookAvatars.mediaName("w1"))
        others.forEach { cache.put(it, bytes) }
        cache.put(attachmentThumbKey("a1"), bytes)

        cache.sweepMessageMedia(emptySet())

        others.forEach { assertContentEquals(bytes, cache.peek(it), it) }
        assertEquals(others.size, looseFiles().size)
    }

    @Test
    fun varrerApagaOsTemporariosEsquecidos() = runTest(timeout = 5.seconds) {
        val cache = cache()
        cache.put(attachmentThumbKey("a1"), bytes)
        val stale = File(directory, MediaCache.MESSAGE_AREA + "/" + "0".repeat(64) + ".tmp").apply { writeBytes(bytes) }

        cache.sweepMessageMedia(setOf(attachmentThumbKey("a1")))

        assertFalse(stale.exists())
        assertContentEquals(bytes, cache.peek(attachmentThumbKey("a1")))
    }

    @Test
    fun varrerSemAAreaCriadaNaoFalha() = runTest(timeout = 5.seconds) {
        cache().sweepMessageMedia(emptySet())

        assertTrue(messageFiles().isEmpty())
    }

    @Test
    fun oQueSaiuDaListaTambemSaiDaMemoria() = runTest(timeout = 5.seconds) {
        val cache = cache()
        cache.put(attachmentThumbKey("a1"), bytes, keepInMemory = true)
        cache.put(attachmentThumbKey("a2"), bytes, keepInMemory = true)

        cache.sweepMessageMedia(setOf(attachmentThumbKey("a2")))
        // Sem o arquivo, uma cópia que sobrasse na memória ainda serviria a mídia que já não é de nenhuma mensagem.
        assertNull(cache.peek(attachmentThumbKey("a1")))
        assertContentEquals(bytes, cache.peek(attachmentThumbKey("a2")))
    }

    @Test
    fun buscaEmAndamentoNaoPerdeOArquivoPelaVarredura() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val name = attachmentThumbKey("a1")
        val gate = CompletableDeferred<Unit>()
        val fetching = async { cache.get(name) { gate.await(); bytes } }
        runCurrent() // a busca já é a dona do nome e espera o servidor

        cache.sweepMessageMedia(emptySet())
        gate.complete(Unit)

        assertContentEquals(bytes, fetching.await())
        assertContentEquals(bytes, cache.peek(name)) // a gravação sobreviveu à varredura
        assertEquals(1, messageFiles().size)
    }

    @Test
    fun outraInstanciaAindaAchaOQueSobrouDaVarredura() = runTest(timeout = 5.seconds) {
        cache().put(attachmentThumbKey("a1"), bytes)

        cache().sweepMessageMedia(setOf(attachmentThumbKey("a1")))

        assertContentEquals(bytes, cache().peek(attachmentThumbKey("a1"))) // a mesma chave e o mesmo nome acham o arquivo
    }

    @Test
    fun varreduraCanceladaNaoApagaNada() = runTest(timeout = 5.seconds) {
        val cache = cache()
        cache.put(attachmentThumbKey("a1"), bytes)

        val job = launch(start = CoroutineStart.UNDISPATCHED) { cache.sweepMessageMedia(emptySet()) }
        job.cancel() // a varredura está suspensa antes de tocar no disco
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, messageFiles().size)
    }

    // Migração da pasta única

    @Test
    fun varrerForaDaAreaMantemSoAListaEAAreaDasMensagens() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val avatar = avatarMediaName("u1", 3)
        val emoji = "emoji/e1"
        listOf(avatar, emoji, "media/banner", "att-thumb/antigo", WebhookAvatars.mediaName("w1")).forEach { cache.put(it, bytes) }
        cache.put(attachmentThumbKey("a1"), bytes)

        cache.sweepOutsideMessageArea(setOf(avatar, emoji))

        assertContentEquals(bytes, cache.peek(avatar))
        assertContentEquals(bytes, cache.peek(emoji))
        assertNull(cache.peek("media/banner"))
        assertNull(cache.peek("att-thumb/antigo"))
        assertNull(cache.peek(WebhookAvatars.mediaName("w1")))
        assertEquals(2, looseFiles().size)
        assertContentEquals(bytes, cache.peek(attachmentThumbKey("a1"))) // a área nova não é tocada
        assertEquals(1, messageFiles().size)
    }

    @Test
    fun varrerForaDaAreaApagaTemporariosEPoupaAsSubpastas() = runTest(timeout = 5.seconds) {
        val cache = cache()
        File(directory).mkdirs()
        val stale = File(directory, "f".repeat(64) + ".tmp").apply { writeBytes(bytes) }
        val folder = File(directory, "outra").apply { mkdirs() }
        File(folder, "dentro").writeBytes(bytes)

        cache.sweepOutsideMessageArea(emptySet())

        assertFalse(stale.exists())
        assertTrue(File(folder, "dentro").exists())
    }

    @Test
    fun varrerForaDaAreaSemAPastaCriadaNaoFalha() = runTest(timeout = 5.seconds) {
        File(directory).deleteRecursively()

        cache().sweepOutsideMessageArea(emptySet())
    }
}
