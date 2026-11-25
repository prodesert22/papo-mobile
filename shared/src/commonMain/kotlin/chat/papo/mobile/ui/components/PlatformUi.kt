package chat.papo.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.voice.VideoTrackHandle

/** O que a interface precisa da plataforma e não existe no Compose comum. */
interface PlatformUi {
    /** Devolve a ação que abre o seletor de arquivos do sistema. */
    @Composable
    fun rememberFilePicker(onPicked: (List<OutgoingFile>) -> Unit): () -> Unit

    /** Desenha uma faixa de vídeo de chamada. [fill] recorta para preencher; senão mostra inteira. */
    @Composable
    fun VideoView(track: VideoTrackHandle, modifier: Modifier, fill: Boolean)

    /**
     * A fonte de emojis do aparelho sabe desenhar [emoji]? Versões antigas do Android
     * não têm os emojis mais novos e mostrariam um quadrado vazio. Pode ser lento:
     * chame fora da thread principal.
     */
    fun supportsEmoji(emoji: String): Boolean

    /** Grava um arquivo na pasta de downloads do usuário. */
    suspend fun saveToDownloads(name: String, mimeType: String, bytes: ByteArray): Boolean
}

val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { error("PlatformUi não fornecido") }
