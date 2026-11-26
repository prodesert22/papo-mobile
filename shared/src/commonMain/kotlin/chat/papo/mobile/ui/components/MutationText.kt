package chat.papo.mobile.ui.components

import androidx.compose.runtime.Composable
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.mutation_not_sent
import chat.papo.mobile.resources.mutation_rate_limited
import chat.papo.mobile.resources.mutation_rejected
import chat.papo.mobile.resources.mutation_unauthorized
import org.jetbrains.compose.resources.stringResource

/**
 * O texto de uma mudança que não deu certo, ou null se deu. [uncertain] diz o que fazer quando o servidor pode ter
 * aplicado a mudança: cada tela sabe o que conferir antes de repetir. O detalhe de uma recusa é o texto do servidor.
 */
@Composable
fun MutationResult.failureText(uncertain: String): String? = when (this) {
    MutationResult.Done -> null
    MutationResult.RateLimited -> stringResource(Res.string.mutation_rate_limited)
    MutationResult.NotSent -> stringResource(Res.string.mutation_not_sent)
    MutationResult.Unauthorized -> stringResource(Res.string.mutation_unauthorized)
    MutationResult.Uncertain -> uncertain
    is MutationResult.Rejected -> detail?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.mutation_rejected)
}
