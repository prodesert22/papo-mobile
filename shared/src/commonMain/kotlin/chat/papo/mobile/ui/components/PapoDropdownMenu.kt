package chat.papo.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * O menu suspenso do app. O Material pinta o menu com `surfaceContainer`, a cor das áreas de itens: quando ela é a
 * mesma do fundo (o modo OLED, ou um tema com as duas cores iguais), o menu ganha um contorno discreto para não se
 * misturar ao que está por baixo. Nos outros temas ele é o menu de sempre.
 */
@Composable
fun PapoDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val border = if (scheme.surfaceContainer == scheme.background) BorderStroke(1.dp, scheme.outlineVariant) else null
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, border = border, content = content)
}
