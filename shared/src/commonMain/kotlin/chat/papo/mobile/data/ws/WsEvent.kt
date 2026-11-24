package chat.papo.mobile.data.ws

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.DirectConversationDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.ExternalAuthorDto
import chat.papo.mobile.data.api.PapoJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Eventos que o servidor envia pelo WebSocket (tabela de `/ws` na OpenAPI). */
@Serializable
sealed interface WsEvent {
    @Serializable @SerialName("message")
    data class MessageCreated(
        val id: String,
        @SerialName("channel_id") val channelId: String,
        @SerialName("author_id") val authorId: String? = null,
        @SerialName("webhook_id") val webhookId: String? = null,
        @SerialName("external_author") val externalAuthor: ExternalAuthorDto? = null,
        val content: String? = null,
        @SerialName("reply_to") val replyTo: String? = null,
        val attachments: List<AttachmentDto> = emptyList(),
        @SerialName("created_at") val createdAt: String,
    ) : WsEvent

    @Serializable @SerialName("message_edit")
    data class MessageEdited(
        val id: String,
        @SerialName("channel_id") val channelId: String,
        val content: String? = null,
        @SerialName("edited_at") val editedAt: String? = null,
    ) : WsEvent

    @Serializable @SerialName("message_delete")
    data class MessageDeleted(val id: String, @SerialName("channel_id") val channelId: String) : WsEvent

    @Serializable @SerialName("attachment_moderation_update")
    data class AttachmentModeration(
        @SerialName("channel_id") val channelId: String,
        @SerialName("message_id") val messageId: String,
        @SerialName("attachment_id") val attachmentId: String,
        val status: String,
    ) : WsEvent

    @Serializable @SerialName("message_pin")
    data class MessagePinned(@SerialName("message_id") val messageId: String, @SerialName("is_pinned") val isPinned: Boolean) : WsEvent

    @Serializable @SerialName("react_update")
    data class ReactionUpdated(
        @SerialName("message_id") val messageId: String,
        @SerialName("emoji_id") val emojiId: String? = null,
        val unicode: String? = null,
        val count: Int = 0,
    ) : WsEvent

    @Serializable @SerialName("message_embeds_update")
    data class EmbedsUpdated(
        @SerialName("channel_id") val channelId: String,
        @SerialName("message_id") val messageId: String,
        val embeds: List<EmbedDto> = emptyList(),
    ) : WsEvent

    @Serializable @SerialName("new_notification")
    data class NewNotification(
        val id: String,
        @SerialName("user_id") val authorId: String? = null,
        @SerialName("message_id") val messageId: String? = null,
        @SerialName("message_content") val content: String? = null,
    ) : WsEvent

    @Serializable @SerialName("channel_create")
    data class ChannelCreated(
        @SerialName("channel_id") val channelId: String,
        val name: String = "",
        @SerialName("channel_type") val channelType: String = "text",
        val position: Int = 0,
        val topic: String? = null,
        @SerialName("parent_id") val parentId: String? = null,
    ) : WsEvent

    @Serializable @SerialName("channel_update")
    data class ChannelUpdated(
        @SerialName("channel_id") val channelId: String,
        val name: String = "",
        val position: Int = 0,
        val topic: String? = null,
        @SerialName("parent_id") val parentId: String? = null,
    ) : WsEvent

    @Serializable @SerialName("channel_delete")
    data class ChannelDeleted(@SerialName("channel_id") val channelId: String) : WsEvent

    @Serializable @SerialName("typing")
    data class Typing(
        @SerialName("channel_id") val channelId: String,
        @SerialName("user_id") val userId: String = "",
        @SerialName("is_typing") val isTyping: Boolean = true,
    ) : WsEvent

    @Serializable @SerialName("avatar_update")
    data class AvatarUpdated(@SerialName("user_id") val userId: String) : WsEvent

    @Serializable @SerialName("user_join")
    data class UserJoined(@SerialName("user_id") val userId: String) : WsEvent

    @Serializable @SerialName("role_add")
    data class RoleAdded(@SerialName("user_id") val userId: String, @SerialName("role_id") val roleId: String) : WsEvent

    @Serializable @SerialName("role_remove")
    data class RoleRemoved(@SerialName("user_id") val userId: String, @SerialName("role_id") val roleId: String) : WsEvent

    @Serializable @SerialName("dm_update")
    data class DmUpdated(val dm: DirectConversationDto) : WsEvent

    @Serializable @SerialName("presence_update")
    data class PresenceUpdated(
        @SerialName("user_id") val userId: String,
        val status: String = "offline",
        @SerialName("status_message") val statusMessage: String? = null,
        val typing: String? = null,
        val nickname: String? = null,
    ) : WsEvent

    @Serializable
    data class PresenceMember(
        @SerialName("user_id") val userId: String,
        val status: String = "offline",
        @SerialName("status_message") val statusMessage: String? = null,
        @SerialName("user_voice") val voiceChannels: List<String> = emptyList(),
    )

    @Serializable @SerialName("presence_sync")
    data class PresenceSync(val members: List<PresenceMember> = emptyList()) : WsEvent

    @Serializable @SerialName("heartbeat_ack")
    data object HeartbeatAck : WsEvent

    @Serializable @SerialName("error")
    data class ServerError(val message: String = "", val code: String? = null) : WsEvent

    // Voz

    @Serializable
    data class VoiceMember(
        @SerialName("user_id") val userId: String,
        val muted: Boolean = true,
        @SerialName("camera_on") val cameraOn: Boolean = false,
        @SerialName("screen_sharing") val screenSharing: Boolean = false,
        @SerialName("screen_audio") val screenAudio: Boolean = false,
    )

    @Serializable @SerialName("voice_joined")
    data class VoiceJoined(
        @SerialName("channel_id") val channelId: String,
        val members: List<VoiceMember> = emptyList(),
        @SerialName("active_speakers") val activeSpeakers: List<String> = emptyList(),
    ) : WsEvent

    @Serializable @SerialName("voice_answer")
    data class VoiceAnswer(@SerialName("channel_id") val channelId: String, val sdp: String) : WsEvent

    @Serializable @SerialName("voice_offer")
    data class VoiceOffer(@SerialName("channel_id") val channelId: String, val sdp: String) : WsEvent

    @Serializable @SerialName("voice_ice_candidate")
    data class VoiceIceCandidate(
        @SerialName("channel_id") val channelId: String,
        val candidate: String,
        @SerialName("sdp_mid") val sdpMid: String? = null,
        @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
    ) : WsEvent

    @Serializable @SerialName("voice_state_update")
    data class VoiceStateUpdated(
        @SerialName("channel_id") val channelId: String,
        @SerialName("user_id") val userId: String,
        val muted: Boolean = true,
        @SerialName("camera_on") val cameraOn: Boolean = false,
        @SerialName("screen_sharing") val screenSharing: Boolean = false,
        @SerialName("screen_audio") val screenAudio: Boolean = false,
    ) : WsEvent

    @Serializable @SerialName("voice_leave")
    data class VoiceLeft(@SerialName("channel_id") val channelId: String, @SerialName("user_id") val userId: String) : WsEvent

    @Serializable @SerialName("active_speaker_update")
    data class ActiveSpeakers(
        @SerialName("channel_id") val channelId: String,
        @SerialName("user_ids") val userIds: List<String> = emptyList(),
    ) : WsEvent

    @Serializable
    data class AudioRoute(
        @SerialName("track_id") val trackId: String,
        @SerialName("user_id") val userId: String,
        /** `audio` (microfone) ou `screen_audio` (áudio de uma transmissão). */
        val kind: String = "audio",
    )

    @Serializable @SerialName("voice_audio_routes")
    data class VoiceAudioRoutes(
        @SerialName("channel_id") val channelId: String,
        val routes: List<AudioRoute> = emptyList(),
    ) : WsEvent

    companion object {
        private val json = Json(PapoJson) { classDiscriminator = "type" }

        /** Null para tipos que este cliente não conhece ou payloads malformados. */
        fun parse(text: String): WsEvent? = try {
            json.decodeFromString(serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Mensagens que o cliente envia pelo WebSocket. */
@Serializable
sealed interface WsCommand {
    @Serializable @SerialName("heartbeat")
    data object Heartbeat : WsCommand

    @Serializable @SerialName("presence_activity")
    data object PresenceActivity : WsCommand

    @Serializable @SerialName("typing")
    data class Typing(@SerialName("channel_id") val channelId: String) : WsCommand

    @Serializable @SerialName("voice_join")
    data class VoiceJoin(@SerialName("channel_id") val channelId: String) : WsCommand

    @Serializable @SerialName("voice_leave")
    data class VoiceLeave(@SerialName("channel_id") val channelId: String) : WsCommand

    @Serializable @SerialName("voice_offer")
    data class VoiceOffer(@SerialName("channel_id") val channelId: String, val sdp: String) : WsCommand

    @Serializable @SerialName("voice_answer")
    data class VoiceAnswer(@SerialName("channel_id") val channelId: String, val sdp: String) : WsCommand

    @Serializable @SerialName("voice_ice_candidate")
    data class VoiceIceCandidate(
        @SerialName("channel_id") val channelId: String,
        val candidate: String,
        @SerialName("sdp_mid") val sdpMid: String? = null,
        @SerialName("sdp_mline_index") val sdpMLineIndex: Int? = null,
    ) : WsCommand

    @Serializable @SerialName("track_subscribe")
    data class TrackSubscribe(
        @SerialName("channel_id") val channelId: String,
        @SerialName("publisher_id") val publisherId: String,
        val kind: String,
    ) : WsCommand

    @Serializable @SerialName("track_unsubscribe")
    data class TrackUnsubscribe(
        @SerialName("channel_id") val channelId: String,
        @SerialName("publisher_id") val publisherId: String,
        val kind: String,
    ) : WsCommand

    @Serializable @SerialName("voice_mute")
    data class VoiceMute(@SerialName("channel_id") val channelId: String, val muted: Boolean) : WsCommand

    @Serializable @SerialName("voice_camera")
    data class VoiceCamera(@SerialName("channel_id") val channelId: String, val on: Boolean) : WsCommand

    @Serializable @SerialName("screen_share_start")
    data class ScreenShareStart(@SerialName("channel_id") val channelId: String) : WsCommand

    @Serializable @SerialName("screen_share_stop")
    data class ScreenShareStop(@SerialName("channel_id") val channelId: String) : WsCommand

    companion object {
        private val json = Json(PapoJson) { classDiscriminator = "type" }

        fun encode(command: WsCommand): String = json.encodeToString(serializer(), command)
    }
}
