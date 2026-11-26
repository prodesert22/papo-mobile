package chat.papo.mobile.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

/** O depósito do processo de que o [SharedMediaProvider] lê, e como se monta o endereço de um arquivo nele. */
object SharedMedia {
    val store = SharedMediaStore()

    fun authority(context: Context): String = "${context.packageName}.shared"

    /**
     * O endereço de [token]: o nome vem no fim só porque há aplicativos que o usam como nome do arquivo (o provedor ignora
     * essa parte e responde `DISPLAY_NAME` no `query`).
     */
    fun uri(context: Context, token: String, name: String): Uri =
        Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).appendPath(name.ifBlank { "arquivo" }).build()
}

/**
 * Entrega a outros aplicativos o conteúdo que o app pôs em [SharedMedia.store], para **Compartilhar**. Não é exportado:
 * só lê quem o sistema autorizou naquele compartilhamento (`grantUriPermissions`). O conteúdo vai da memória por um pipe,
 * sem arquivo em disco, e cada aplicativo o lê uma vez. É só de leitura.
 */
class SharedMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = info(uri)?.mimeType

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val info = info(uri) ?: return null
        val columns = (projection ?: COLUMNS).filter { it in COLUMNS }
        val cursor = MatrixCursor(columns.toTypedArray(), 1)
        cursor.addRow(columns.map { if (it == OpenableColumns.SIZE) info.size else info.name })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("somente leitura")
        val token = tokenOf(uri) ?: throw FileNotFoundException("endereço desconhecido")
        val bytes = SharedMedia.store.take(token, Binder.getCallingUid()) ?: throw FileNotFoundException("não há mais o que ler")
        return pipeOf(bytes)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException(READ_ONLY)

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException(READ_ONLY)

    private fun info(uri: Uri) = tokenOf(uri)?.let(SharedMedia.store::info)

    private fun tokenOf(uri: Uri): String? = uri.pathSegments.firstOrNull()?.takeIf(SharedMediaStore::isToken)

    /**
     * O lado de leitura de um pipe que recebe [bytes] de uma thread própria (o pipe só tem alguns KB de folga, e quem lê
     * pode ser lento). "Confiável" faz o fim da escrita saber se quem lê fechou antes de o conteúdo acabar, e quem lê
     * saber se a escrita falhou no meio, em vez de achar que o arquivo acabou ali.
     */
    private fun pipeOf(bytes: ByteArray): ParcelFileDescriptor {
        val (reader, writer) = try {
            ParcelFileDescriptor.createReliablePipe().let { it[0] to it[1] }
        } catch (e: IOException) {
            throw FileNotFoundException("não foi possível abrir o conteúdo")
        }
        thread(name = "papo-share", isDaemon = true) {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writer)
            try {
                out.write(bytes)
                out.close()
            } catch (e: Exception) {
                // Quem lê fechou antes do fim (cancelou, ou só queria o começo): fechar com erro libera o pipe.
                runCatching { writer.closeWithError(e.message) }
            }
        }
        return reader
    }

    private companion object {
        val COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        const val READ_ONLY = "somente leitura"
    }
}
