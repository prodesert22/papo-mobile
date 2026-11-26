package chat.papo.mobile.ui.components

import androidx.compose.runtime.Composable
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.chat.PasswordProblem
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.password_mismatch
import chat.papo.mobile.resources.password_problem_short
import chat.papo.mobile.resources.password_problem_symbol
import chat.papo.mobile.resources.password_problem_upper
import org.jetbrains.compose.resources.stringResource

@Composable
fun PasswordProblem.text(): String = stringResource(
    when (this) {
        PasswordProblem.TooShort -> Res.string.password_problem_short
        PasswordProblem.NoUppercase -> Res.string.password_problem_upper
        PasswordProblem.NoSymbol -> Res.string.password_problem_symbol
    },
)

/** O que falta à senha nova, numa frase; null quando está certa. */
@Composable
fun PasswordCheck.text(): String? = when (this) {
    PasswordCheck.Ok -> null
    is PasswordCheck.Weak -> problems.map { it.text() }.joinToString(" ")
    PasswordCheck.Mismatch -> stringResource(Res.string.password_mismatch)
}
