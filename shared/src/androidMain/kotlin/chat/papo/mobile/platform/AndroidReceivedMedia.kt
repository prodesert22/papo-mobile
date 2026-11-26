package chat.papo.mobile.platform

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.ui.Modifier
import chat.papo.mobile.chat.nameFor
import chat.papo.mobile.chat.receivedMediaType
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.ui.components.RECEIVED_MEDIA_MAX_BYTES
import io.ktor.utils.io.ByteReadChannel
import java.io.FileNotFoundException

/**
 * O que o campo do compositor recebe do teclado (GIF, figurinha, imagem) e ao colar: as imagens e os vídeos viram
 * [OutgoingFile]; o resto (texto colado) volta para o campo. O Compose já pede ao sistema o acesso temporário ao
 * `content://` que o teclado dá, e esse acesso acaba logo depois da entrega, então o conteúdo é lido por inteiro aqui,
 * na hora, até [RECEIVED_MEDIA_MAX_BYTES], e fica em memória até o envio ou até o usuário tirar o anexo. Nada vai
 * para o disco.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class ReceivedMedia(context: Context, private val images: AndroidImages) {
    private val resolver = context.applicationContext.contentResolver

    /** O modificador do campo; [onMedia] recebe, na thread principal, o que for imagem ou vídeo de uma entrega. */
    fun modifier(onMedia: (List<OutgoingFile>) -> Unit): Modifier = Modifier.contentReceiver(
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                val description = transferableContent.clipMetadata.clipDescription
                val declared = List(description.mimeTypeCount) { description.getMimeType(it) }
                val received = ArrayList<OutgoingFile>()
                val rest = transferableContent.consume { item ->
                    val file = read(item, declared)
                    if (file != null) received += file
                    file != null
                }
                if (received.isNotEmpty()) onMedia(received)
                return rest
            }
        },
    )

    /**
     * O item como anexo, ou null se não é imagem nem vídeo (então ele não é consumido). Só se lê `content://`: o que o teclado
     * e quem copia entregam é de fora (regra 6), e um `file://` apontaria para dentro da pasta do próprio app.
     */
    private fun read(item: ClipData.Item, declared: List<String>): OutgoingFile? {
        val uri = item.uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: return null
        val type = receivedMediaType(typeOf(uri), declared) ?: return null
        val name = nameFor(displayNameOf(uri), type)
        val bytes = try {
            resolver.openInputStream(uri)?.use { readAtMost(it, RECEIVED_MEDIA_MAX_BYTES) }
        } catch (e: Exception) {
            // Sem permissão (SecurityException), conteúdo que sumiu (FileNotFoundException) ou leitura que falhou (IOException):
            // o cartão avisa e o usuário decide, como com o arquivo que o seletor perdeu.
            null
        }
        if (bytes == null) return unavailable(name, type)
        return OutgoingFile(
            name = name,
            mimeType = type,
            size = bytes.size.toLong(),
            open = { ByteReadChannel(bytes) },
            // Um quadro só: o GIF aparece parado na prévia, e o selo diz o que ele é. Vídeo fica sem prévia (o ícone do tipo).
            preview = { maxPx -> if (type.startsWith("image/")) images.decode(bytes, maxPx, animate = false) else null },
        )
    }

    private fun unavailable(name: String, type: String) = OutgoingFile(
        name = name,
        mimeType = type,
        size = null,
        open = { throw FileNotFoundException("o conteúdo recebido não pôde ser lido") },
        unavailable = true,
    )

    private fun typeOf(uri: Uri): String? = try {
        resolver.getType(uri)
    } catch (e: Exception) {
        null // o provedor se recusa a dizer: vale o tipo que o teclado declarou
    }

    private fun displayNameOf(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null // sem nome: `nameFor` dá um genérico do tipo (`imagem.gif`)
    }
}
