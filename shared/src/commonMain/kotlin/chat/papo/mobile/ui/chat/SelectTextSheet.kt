package chat.papo.mobile.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.msg_select
import org.jetbrains.compose.resources.stringResource

/**
 * O texto que "Copiar texto" leva para a área de transferência: como o autor o escreveu, com
 * `@nome` no lugar do `@mention(<@id>)` que o servidor guarda (o mesmo que a edição mostra).
 */
fun copyableText(content: String, users: Map<String, User>): String =
    renderMentions(content) { users[it]?.username }

/**
 * Folha com o texto da mensagem para selecionar um trecho. A seleção fica numa folha à parte
 * porque, na lista, o toque longo já abre as ações da mensagem. [highlightEveryone] é o mesmo da mensagem
 * na lista, para o `@everyone` aparecer igual (copiar leva o texto cru, ver [copyableText]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectTextSheet(
    session: AccountSession,
    content: String,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
    highlightEveryone: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(Res.string.msg_select), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer { MessageBodyText(session, content, users, emojiIds, unicodeEmojis, highlightEveryone) }
            }
        }
    }
}
