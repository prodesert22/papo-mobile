package chat.papo.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.mentions_count
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.stringResource

/** O número vermelho de menções não lidas ([text] vem de `badgeText`, com o `+` quando a contagem é parcial). */
@Composable
fun MentionBadge(text: String, modifier: Modifier = Modifier, minHeight: androidx.compose.ui.unit.Dp = 22.dp) {
    val colors = LocalPapoColors.current
    val description = stringResource(Res.string.mentions_count, text)
    Box(
        modifier
            .heightIn(min = minHeight)
            .background(colors.mention, RoundedCornerShape(minHeight / 2))
            .padding(horizontal = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onMention, maxLines = 1)
    }
}
