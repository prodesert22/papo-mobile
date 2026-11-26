package chat.papo.mobile.ui.profile

import androidx.compose.runtime.Composable
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.media.ImageRefusal
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.profile_edit_failed_not_sent
import chat.papo.mobile.resources.profile_edit_failed_rate_limited
import chat.papo.mobile.resources.profile_edit_failed_rejected
import chat.papo.mobile.resources.profile_edit_failed_uncertain
import chat.papo.mobile.resources.profile_edit_failed_unauthorized
import chat.papo.mobile.resources.profile_edit_refused_animation
import chat.papo.mobile.resources.profile_edit_refused_not_an_image
import chat.papo.mobile.resources.profile_edit_refused_source_too_large
import chat.papo.mobile.resources.profile_edit_refused_too_heavy
import chat.papo.mobile.resources.profile_edit_refused_unreadable
import org.jetbrains.compose.resources.stringResource

/** O texto de uma mudança que não deu certo; null se deu ([MutationResult.Done]). O servidor, quando recusa, explica com as palavras dele. */
@Composable
fun MutationResult.failureText(): String? = when (this) {
    MutationResult.Done -> null
    is MutationResult.Rejected -> detail?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.profile_edit_failed_rejected)
    MutationResult.RateLimited -> stringResource(Res.string.profile_edit_failed_rate_limited)
    MutationResult.NotSent -> stringResource(Res.string.profile_edit_failed_not_sent)
    MutationResult.Unauthorized -> stringResource(Res.string.profile_edit_failed_unauthorized)
    MutationResult.Uncertain -> stringResource(Res.string.profile_edit_failed_uncertain)
}

/** Por que a imagem escolhida não serve. */
@Composable
fun ImageRefusal.text(): String = stringResource(
    when (this) {
        ImageRefusal.NotAnImage -> Res.string.profile_edit_refused_not_an_image
        ImageRefusal.SourceTooLarge -> Res.string.profile_edit_refused_source_too_large
        ImageRefusal.AnimationTooLarge -> Res.string.profile_edit_refused_animation
        ImageRefusal.StillTooHeavy -> Res.string.profile_edit_refused_too_heavy
        ImageRefusal.Unreadable -> Res.string.profile_edit_refused_unreadable
    },
)
