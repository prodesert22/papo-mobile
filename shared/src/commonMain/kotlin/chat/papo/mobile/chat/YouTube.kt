package chat.papo.mobile.chat

import chat.papo.mobile.data.api.EmbedDto

/** O id de um vídeo do YouTube: 11 caracteres de `[A-Za-z0-9_-]`, como o backend e o web o validam. */
private const val ID_PATTERN = "[A-Za-z0-9_-]{11}"

private val VIDEO_ID = Regex(ID_PATTERN)

/** A única página de player aceita: o `embed` do YouTube com o id e mais nada (sem parâmetros, barra ou outro host). */
private val EMBED_PAGE = Regex("""https://www\.youtube\.com/embed/($ID_PATTERN)""")

/** [id] tem a forma de um id de vídeo do YouTube? */
fun isYouTubeId(id: String): Boolean = VIDEO_ID.matches(id)

/**
 * O id do vídeo do YouTube do [embed], ou null se ele não é um. O servidor entrega a página do player em `video.url` (e,
 * nas respostas antigas, em `embed_url`) como exatamente `https://www.youtube.com/embed/<id>`, derivada por ele do link
 * original; o cliente só toca se a URL casa inteira com esse padrão (como o web), nunca pelo que mais ela contenha. O id
 * que sai é o que vale para montar qualquer outra URL: nada vem do `url` nem do HTML do embed.
 */
fun youTubeId(embed: EmbedDto): String? =
    listOf(embed.embedUrl, embed.video?.url).firstNotNullOfOrNull { candidate ->
        candidate?.let { EMBED_PAGE.matchEntire(it)?.groupValues?.get(1) }
    }

/** A página do vídeo no YouTube, para abrir fora do app, ou null se [id] não é um id válido (nunca monta a URL com lixo). */
fun youTubeWatchUrl(id: String): String? = if (isYouTubeId(id)) "https://www.youtube.com/watch?v=$id" else null

/** A chave da mídia do YouTube [id] entre os vídeos da conversa (ver `PlayableMedia.key`, que tem `attachment:` e `embed:`). */
fun youTubeKey(id: String): String = "youtube:$id"

/**
 * [key] é de um vídeo do YouTube? Vale para a chave da mídia ([youTubeKey]) e para a do cartão que a leva depois da mensagem
 * (`<mensagem>/youtube:<id>`). O id do vídeo e o da mensagem não têm `/`, então só o que vem depois da última barra conta.
 */
fun isYouTubeKey(key: String): Boolean = key.substringAfterLast('/').startsWith("youtube:")
