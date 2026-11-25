package chat.papo.mobile.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.ui.components.LocalPlatformUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Baixar um anexo para a pasta Downloads: [downloading] enquanto o arquivo vem e é gravado, [failed] se
 * a última tentativa não gravou. [save] devolve se gravou; qualquer outra falha também conta como não gravou.
 * É o caminho do cartão de arquivo, que o cartão e o visualizador de vídeo repetem.
 */
@Stable
class AttachmentDownload(private val scope: CoroutineScope, private val save: suspend () -> Boolean) {
    var downloading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Começa a baixar; não faz nada se já está baixando. */
    fun start() {
        if (downloading) return
        downloading = true
        failed = false
        scope.launch {
            try {
                failed = !saved()
            } finally {
                downloading = false
            }
        }
    }

    private suspend fun saved(): Boolean = try {
        save()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Também o estouro de memória de um arquivo grande (hoje ele é lido inteiro): aparece como falha, não como queda do app.
        false
    }
}

/** O [AttachmentDownload] de [attachment], da sessão que é dona dele; some quando a tela sai. */
@Composable
fun rememberAttachmentDownload(session: AccountSession, attachment: AttachmentDto): AttachmentDownload {
    val platform = LocalPlatformUi.current
    val scope = rememberCoroutineScope()
    return remember(session, attachment.id) {
        AttachmentDownload(scope) {
            platform.saveToDownloads(attachment.fileName, attachment.mimeType, session.api.attachment(attachment.id))
        }
    }
}
