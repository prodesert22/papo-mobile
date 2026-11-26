package chat.papo.mobile

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.platform.SharedMedia
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O provedor que entrega o **Compartilhar** do visualizador, no Android de verdade: o manifesto, o pipe e o
 * `ContentResolver`. Só usa a memória do processo de teste (o depósito) e nunca toca nos dados do app instalado.
 * Quem lê aqui tem o mesmo uid do provedor; a regra de uma leitura por aplicativo, com uids diferentes, é do
 * `SharedMediaStoreTest`.
 */
@RunWith(AndroidJUnit4::class)
class SharedMediaProviderTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val store = SharedMedia.store

    @Before
    @After
    fun clean() = store.clear()

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun share(bytes: ByteArray, name: String = "foto.jpg", mime: String = "image/jpeg"): Uri =
        SharedMedia.uri(context, store.put(name, mime, bytes), name)

    private fun read(uri: Uri): ByteArray = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("o provedor não abriu $uri")

    @Test
    fun entregaOsBytesDaMemoria() {
        val bytes = payload(1_000)
        assertContentEquals(bytes, read(share(bytes)))
    }

    @Test
    fun entregaUmConteudoMuitoMaiorQueOPipeSemPerderNada() {
        val bytes = payload(3 * 1024 * 1024 + 17)
        assertContentEquals(bytes, read(share(bytes)))
    }

    @Test
    fun respondeNomeTamanhoETipoSemGastarALeitura() {
        val uri = share(payload(2_048), name = "viagem.png", mime = "image/png")
        assertEquals("image/png", resolver.getType(uri))
        resolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("viagem.png", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(2_048L, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        // Quem pergunta o nome depois de ler ainda o recebe: o aplicativo de destino costuma fazer nessa ordem.
        read(uri)
        // Só os dois campos de arquivo abrível existem: o que mais se pedir (como o caminho `_data`) não é servido.
        resolver.query(uri, arrayOf("_data", OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.columnCount)
            assertEquals("viagem.png", cursor.getString(0))
        }
    }

    @Test
    fun aIntencaoDeEnviarLevaOEnderecoOTipoEALeituraConcedidaAteOSeletor() {
        val uri = share(payload(10))
        val send = SharedMedia.sendIntent(uri, "foto.jpg", "image/jpeg")
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // O seletor é quem a recebe do app, e a concessão precisa chegar nele para chegar ao aplicativo escolhido.
        val chooser = Intent.createChooser(send, null)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, chooser.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun oMesmoAplicativoLeUmaVezSo() {
        val uri = share(payload(100))
        read(uri)
        assertFailsWith<FileNotFoundException> { resolver.openInputStream(uri) }
    }

    @Test
    fun enderecoSemTokenValidoNaoAbreNada() {
        val authority = SharedMedia.authority(context)
        for (path in listOf("", "naoETokenNenhum/foto.jpg", "0123456789abcdef0123456789abcdef/foto.jpg", "../../etc/passwd")) {
            val uri = Uri.parse("content://$authority/$path")
            assertFailsWith<FileNotFoundException>(path) { resolver.openInputStream(uri) }
            assertNull(resolver.getType(uri), path)
        }
    }

    @Test
    fun limparEsvaziaOQueFicouParaOutrosAplicativos() {
        val uri = share(payload(100))
        store.clear() // o que o bloqueio do app faz
        assertFailsWith<FileNotFoundException> { resolver.openInputStream(uri) }
        assertNull(resolver.getType(uri))
    }

    @Test
    fun soLeNaoAceitaEscritaNemAlteracao() {
        val uri = share(payload(100))
        assertFailsWith<FileNotFoundException> { resolver.openOutputStream(uri) }
        assertFailsWith<UnsupportedOperationException> { resolver.insert(uri, ContentValues()) }
        assertFailsWith<UnsupportedOperationException> { resolver.update(uri, ContentValues(), null, null) }
        assertFailsWith<UnsupportedOperationException> { resolver.delete(uri, null, null) }
        // Nada disso gastou a leitura nem alterou o conteúdo.
        assertContentEquals(payload(100), read(uri))
    }

    @Test
    fun oManifestoNaoExportaOProvedorEConcedeLeituraPorEndereco() {
        val info = assertNotNull(context.packageManager.resolveContentProvider(SharedMedia.authority(context), 0))
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(context.packageName, info.packageName)
    }

    @Test
    fun quemFechaAntesDoFimLiberaOPipeEOProvedorSegueServindo() {
        val uri = share(payload(4 * 1024 * 1024))
        resolver.openInputStream(uri)!!.use { it.read(ByteArray(16)) }
        // A thread que escrevia no pipe termina quando quem lia o fecha, em vez de ficar presa esperando.
        runBlocking {
            withTimeout(5_000) {
                while (Thread.getAllStackTraces().keys.any { it.name == "papo-share" && it.isAlive }) delay(20)
            }
        }
        assertContentEquals(payload(500), read(share(payload(500))))
    }

    @Test
    fun nadaVaiParaODiscoNoCompartilhamento() {
        val before = filesOnDisk()
        read(share(payload(1_000_000)))
        assertEquals(before, filesOnDisk())
    }

    /** Cada arquivo das pastas do app em que um provedor comum gravaria, com o tamanho: o que mudar entre duas fotos é arquivo novo. */
    private fun filesOnDisk(): Map<String, Long> {
        val roots = listOfNotNull(context.filesDir, context.cacheDir, context.codeCacheDir, context.noBackupFilesDir, context.externalCacheDir)
        return roots.flatMap { root -> root.walkTopDown().filter(File::isFile).toList() }.associate { it.path to it.length() }
    }
}
