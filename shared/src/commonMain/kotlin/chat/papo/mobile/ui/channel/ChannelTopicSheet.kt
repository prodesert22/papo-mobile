package chat.papo.mobile.ui.channel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.channel_topic_edit
import org.jetbrains.compose.resources.stringResource

/**
 * O nome do canal e a descrição inteira, que no cabeçalho aparece cortada em uma linha. A descrição vem do servidor e é
 * mostrada como texto selecionável, nunca como marcação. [onEdit] é nulo para quem não pode editar o canal: sem o
 * botão, a folha só mostra o texto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelTopicSheet(name: String, topic: String, onEdit: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            SelectionContainer { Text(topic, style = MaterialTheme.typography.bodyLarge) }
            if (onEdit != null) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.channel_topic_edit))
                }
            }
        }
    }
}
