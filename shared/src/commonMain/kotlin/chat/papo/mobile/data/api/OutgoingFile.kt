package chat.papo.mobile.data.api

import chat.papo.mobile.ui.components.PlatformImage
import io.ktor.utils.io.ByteReadChannel

/**
 * Arquivo escolhido pelo usuário para enviar. O conteúdo nunca é lido de uma vez: [open] devolve um fluxo
 * novo a cada chamada (o envio abre de novo quando repete o pedido depois de renovar a sessão), e quem o
 * recebe o lê até o fim ou o cancela. Nada vai para o disco.
 */
class OutgoingFile(
    val name: String,
    val mimeType: String,
    /** Bytes do arquivo, ou null se o provedor não diz. Sem tamanho o corpo sai em pedaços e o envio não tem porcentagem. */
    val size: Long?,
    /** Abre o conteúdo do zero. Se o arquivo não pode ser aberto, lança: [ChatApi.sendMessage] a vê como [FileUnavailableException]. */
    val open: () -> ByteReadChannel,
    /** Miniatura com o lado maior de até `maxPx`, ou null se não há imagem para mostrar. Quem a recebe a fecha. Não bloqueia a thread principal. */
    val preview: suspend (maxPx: Int) -> PlatformImage? = { null },
    /** O provedor já recusou ou perdeu o arquivo na escolha: o cartão avisa e o envio fica bloqueado até o usuário o tirar. */
    val unavailable: Boolean = false,
)

/** Um arquivo a enviar não pôde ser aberto ou lido. [fileName] é o nome dele, para a mensagem ao usuário. */
class FileUnavailableException(val fileName: String, cause: Throwable? = null) : Exception("arquivo indisponível: $fileName", cause)
