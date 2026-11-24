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
)

@Serializable
data class ChannelsResponse(val channels: List<ChannelDto> = emptyList())

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
)

@Serializable
data class EmbedDto(
    val id: String,
    @SerialName("source_type") val sourceType: String? = null,
    val provider: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    val url: String? = null,
    val title: String? = null,
    val description: String? = null,
    val thumbnail: EmbedMediaDto? = null,
    val image: EmbedMediaDto? = null,
    val video: EmbedMediaDto? = null,
)

@Serializable
data class EmbedWithImageDto(@SerialName("image_data") val imageData: String? = null)

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

@Serializable
data class SearchRequest(val query: String, @SerialName("channel_id") val channelId: String? = null)

@Serializable
data class IceServerDto(val urls: List<String> = emptyList(), val username: String? = null, val credential: String? = null)

@Serializable
data class IceServersResponse(@SerialName("ice_servers") val iceServers: List<IceServerDto> = emptyList())

/** Arquivo escolhido pelo usuário para enviar. [read] abre o conteúdo do zero a cada chamada. */
class OutgoingFile(val name: String, val mimeType: String, val size: Long, val read: () -> ByteArray)
