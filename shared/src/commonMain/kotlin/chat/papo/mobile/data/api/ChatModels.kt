package chat.papo.mobile.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserSummaryDto(
    val id: String,
    val username: String = "",
    val nickname: String? = null,
    val banned: Boolean = false,
    val status: String? = null,
    @SerialName("status_message") val statusMessage: String? = null,
    val typing: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val roles: List<RoleSummaryDto> = emptyList(),
)

@Serializable
data class UsersPage(val users: List<UserSummaryDto> = emptyList(), @SerialName("has_more") val hasMore: Boolean = false)

@Serializable
data class UserProfileDto(
    val id: String,
    val username: String = "",
    val nickname: String? = null,
    @SerialName("avatar_blob") val avatarBlob: String? = null,
    @SerialName("banner_media") val bannerMedia: String? = null,
    val description: String? = null,
    val status: String? = null,
    @SerialName("status_message") val statusMessage: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val roles: List<RoleSummaryDto> = emptyList(),
)

@Serializable
data class ChannelLastMessageDto(
    val id: String? = null,
    val content: String? = null,
    @SerialName("author_id") val authorId: String? = null,
    @SerialName("author_username") val authorUsername: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ChannelDto(
    val id: String,
    val name: String = "",
    val type: String = "text",
    val position: Int = 0,
    val topic: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("last_message") val lastMessage: ChannelLastMessageDto? = null,
    @SerialName("last_read_message") val lastReadMessage: String? = null,
    @SerialName("notification_settings") val notificationSettings: String? = null,
    /** Entradas por cargo (`GET /channels`); lista vazia é canal aberto, sem restrição. */
    val permissions: List<ChannelPermissionEntryDto> = emptyList(),
)

@Serializable
data class ChannelsResponse(val channels: List<ChannelDto> = emptyList())

/**
 * O que um cargo pode fazer em um canal (`ChannelPermissions`). Como no servidor, flag ausente vale
 * `false`: uma entrada existente que não cita `connect_voice` não dá voz.
 */
@Serializable
data class ChannelPermissionsDto(
    @SerialName("read_channel") val readChannel: Boolean = false,
    @SerialName("send_messages") val sendMessages: Boolean = false,
    @SerialName("delete_messages") val deleteMessages: Boolean = false,
    @SerialName("connect_voice") val connectVoice: Boolean = false,
)

/** Uma entrada de `ChannelDto.permissions`: o cargo e o que ele pode fazer no canal (`ChannelPermissionEntry`). */
@Serializable
data class ChannelPermissionEntryDto(
    @SerialName("role_id") val roleId: String,
    @SerialName("role_name") val roleName: String = "",
    val permissions: ChannelPermissionsDto = ChannelPermissionsDto(),
)

/** Flags de um cargo no servidor (`RolePermissions`); flag ausente vale `false`, como no servidor. */
@Serializable
data class RolePermissionsDto(
    @SerialName("manage_server") val manageServer: Boolean = false,
    @SerialName("manage_channels") val manageChannels: Boolean = false,
    @SerialName("manage_roles") val manageRoles: Boolean = false,
    @SerialName("manage_webhooks") val manageWebhooks: Boolean = false,
    @SerialName("ban_members") val banMembers: Boolean = false,
    @SerialName("pin_message") val pinMessage: Boolean = false,
    @SerialName("everyone_message") val everyoneMessage: Boolean = false,
    @SerialName("send_attachment") val sendAttachment: Boolean = false,
)

/** Cargo com as flags (`Role`, de `GET /roles`); `RoleSummaryDto` é a visão reduzida dentro dos usuários. */
@Serializable
data class RoleDto(
    val id: String,
    val name: String = "",
    val color: String? = null,
    val permissions: RolePermissionsDto = RolePermissionsDto(),
)

@Serializable
data class RolesResponse(val roles: List<RoleDto> = emptyList())

@Serializable
data class DirectConversationDto(
    val id: String,
    val user: UserSummaryDto,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_message") val lastMessage: ChannelLastMessageDto? = null,
    @SerialName("last_read_message") val lastReadMessage: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
)

@Serializable
data class DmsResponse(val dms: List<DirectConversationDto> = emptyList())

@Serializable
data class OpenDmRequest(@SerialName("user_id") val userId: String)

@Serializable
data class AttachmentDto(
    val id: String,
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("original_file_name") val fileName: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("thumbnail_id") val thumbnailId: String? = null,
    @SerialName("thumbnail_width") val thumbnailWidth: Int? = null,
    @SerialName("thumbnail_height") val thumbnailHeight: Int? = null,
    @SerialName("moderation_status") val moderationStatus: String? = null,
) {
    val isImage get() = mimeType.startsWith("image/")
    val isVideo get() = mimeType.startsWith("video/")
    val isSensitive get() = moderationStatus == "sensitive"
}

@Serializable
data class ReactionSummaryDto(
    @SerialName("emoji_id") val emojiId: String? = null,
    val unicode: String? = null,
    val count: Int = 0,
)

@Serializable
data class UserReactionDto(
    val id: String? = null,
    @SerialName("emoji_id") val emojiId: String? = null,
    val unicode: String? = null,
)

@Serializable
data class ReactionRequest(@SerialName("emoji_id") val emojiId: String? = null, val unicode: String? = null)

@Serializable
data class ExternalAuthorDto(
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class EmbedMediaDto(
    val url: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

/** Autor declarado pelo site (oEmbed) ou pelo embed customizado; o ícone vem em `author_image_data` de `GET /embeds/{id}`. */
@Serializable
data class EmbedAuthorDto(
    val name: String? = null,
    val url: String? = null,
    val media: EmbedMediaDto? = null,
)

/** Rodapé do embed. O servidor não serve o ícone (`GET /embeds/{id}` só devolve a imagem e o ícone do autor). */
@Serializable
data class EmbedFooterDto(
    val text: String? = null,
    val icon: EmbedMediaDto? = null,
)

@Serializable
data class EmbedFieldDto(
    val position: Int = 0,
    val name: String = "",
    val value: String = "",
    val inline: Boolean = false,
)

/**
 * Embed de mensagem (`Embed` na OpenAPI). Todos os campos novos têm padrão: as linhas antigas do banco, gravadas antes de
 * eles existirem, continuam decodificando, e só os ganham quando a mensagem é relida ou chega `message_embeds_update`.
 */
@Serializable
data class EmbedDto(
    val id: String,
    @SerialName("source_type") val sourceType: String? = null,
    /** `opengraph`, `oembed`, `gif` ou `manual`; outro valor é tratado como um embed comum. */
    @SerialName("fetch_method") val fetchMethod: String? = null,
    val provider: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    val url: String? = null,
    val title: String? = null,
    val description: String? = null,
    /** `#RRGGBB` do embed customizado; qualquer outra forma é ignorada. */
    val color: String? = null,
    val author: EmbedAuthorDto? = null,
    val thumbnail: EmbedMediaDto? = null,
    val image: EmbedMediaDto? = null,
    val video: EmbedMediaDto? = null,
    val footer: EmbedFooterDto? = null,
    val fields: List<EmbedFieldDto> = emptyList(),
    /** Obsoleto: as respostas atuais não o emitem (o iframe do YouTube vem em [video]). */
    @SerialName("embed_url") val embedUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("fetched_at") val fetchedAt: String? = null,
)

/** Resposta de `GET /embeds/{id}`: a imagem do embed (thumbnail, ou a image sem thumbnail) e o ícone do autor, em base64. */
@Serializable
data class EmbedWithImageDto(
    @SerialName("image_data") val imageData: String? = null,
    @SerialName("author_image_data") val authorImageData: String? = null,
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("author_id") val authorId: String? = null,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("webhook_id") val webhookId: String? = null,
    @SerialName("external_author") val externalAuthor: ExternalAuthorDto? = null,
    val attachments: List<AttachmentDto> = emptyList(),
    val embeds: List<EmbedDto> = emptyList(),
    val reactions: List<ReactionSummaryDto> = emptyList(),
    @SerialName("user_reactions") val userReactions: List<UserReactionDto> = emptyList(),
)

@Serializable
data class MessageListResponse(
    val messages: List<MessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class PinnedMessagesResponse(val pinned: List<MessageDto> = emptyList())

@Serializable
data class UpdateMessageRequest(val content: String)

@Serializable
data class MarkReadRequest(@SerialName("message_id") val messageId: String)

@Serializable
data class EmojiDto(
    val id: String,
    val name: String = "",
    val format: String? = null,
    @SerialName("image_blob") val imageBlob: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class EmojisPage(val emojis: List<EmojiDto> = emptyList(), @SerialName("has_more") val hasMore: Boolean = false)

/**
 * Corpo de `POST /search`. O servidor exige ao menos um filtro além de [order];
 * campo nulo não vai no JSON. [containsAttachment] `false` pede mensagens sem anexo.
 */
@Serializable
data class SearchRequest(
    val text: String? = null,
    val author: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    val mention: String? = null,
    val has: String? = null,
    val order: String? = null,
    @SerialName("date_start") val dateStart: String? = null,
    @SerialName("date_end") val dateEnd: String? = null,
    @SerialName("contains_attachment") val containsAttachment: Boolean? = null,
)

/** Resultado de busca: só o corpo da mensagem, sem anexos, embeds nem reações. */
@Serializable
data class SearchResultDto(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("channel_name") val channelName: String? = null,
    @SerialName("author_id") val authorId: String? = null,
    @SerialName("author_username") val authorUsername: String? = null,
    val content: String? = null,
    @SerialName("webhook_id") val webhookId: String? = null,
    @SerialName("external_author") val externalAuthor: ExternalAuthorDto? = null,
    @SerialName("created_at") val createdAt: String,
    val score: Double? = null,
)

@Serializable
data class SearchResponse(
    val results: List<SearchResultDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class IceServerDto(val urls: List<String> = emptyList(), val username: String? = null, val credential: String? = null)

@Serializable
data class IceServersResponse(@SerialName("ice_servers") val iceServers: List<IceServerDto> = emptyList())

/** Arquivo escolhido pelo usuário para enviar. [read] abre o conteúdo do zero a cada chamada. */
class OutgoingFile(val name: String, val mimeType: String, val size: Long, val read: () -> ByteArray)

/**
 * Uma reação de alguém (`MessageReactionUser`). [createdAt] fica como o servidor mandou: junto de [id] é o cursor da
 * página seguinte, e o servidor compara o instante, não o texto.
 */
@Serializable
data class ReactionUserDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("created_at") val createdAt: String,
)

/** Quem reagiu com um emoji (`MessageReactionGroup`). [count] é o número de [users] na página, não o total do emoji. */
@Serializable
data class ReactionGroupDto(
    @SerialName("emoji_id") val emojiId: String? = null,
    val unicode: String? = null,
    val count: Int = 0,
    val users: List<ReactionUserDto> = emptyList(),
)

/** Uma página de `GET /channels/{id}/messages/{id}/reactions` (`MessageReactionList`). */
@Serializable
data class ReactionListDto(
    @SerialName("message_id") val messageId: String,
    val reactions: List<ReactionGroupDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)
