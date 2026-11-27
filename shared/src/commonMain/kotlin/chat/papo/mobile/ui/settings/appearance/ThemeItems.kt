package chat.papo.mobile.ui.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.theme_item_accent
import chat.papo.mobile.resources.theme_item_accent_hint
import chat.papo.mobile.resources.theme_item_background
import chat.papo.mobile.resources.theme_item_background_hint
import chat.papo.mobile.resources.theme_item_changed
import chat.papo.mobile.resources.theme_item_mention
import chat.papo.mobile.resources.theme_item_mention_background
import chat.papo.mobile.resources.theme_item_mention_background_hint
import chat.papo.mobile.resources.theme_item_mention_hint
import chat.papo.mobile.resources.theme_item_rail
import chat.papo.mobile.resources.theme_item_rail_hint
import chat.papo.mobile.resources.theme_item_surface
import chat.papo.mobile.resources.theme_item_surface_hint
import chat.papo.mobile.resources.theme_item_text
import chat.papo.mobile.resources.theme_item_text_hint
import chat.papo.mobile.resources.theme_item_text_muted
import chat.papo.mobile.resources.theme_item_text_muted_hint
import chat.papo.mobile.resources.theme_items_hint
import chat.papo.mobile.resources.theme_items_title
import chat.papo.mobile.ui.theme.ThemeSpec
import chat.papo.mobile.ui.theme.ThemeToken
import chat.papo.mobile.ui.theme.effectiveColor
import chat.papo.mobile.ui.theme.hasOverride
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun ThemeToken.label(): StringResource = when (this) {
    ThemeToken.Accent -> Res.string.theme_item_accent
    ThemeToken.Background -> Res.string.theme_item_background
    ThemeToken.Surface -> Res.string.theme_item_surface
    ThemeToken.Rail -> Res.string.theme_item_rail
    ThemeToken.Text -> Res.string.theme_item_text
    ThemeToken.TextMuted -> Res.string.theme_item_text_muted
    ThemeToken.Mention -> Res.string.theme_item_mention
    ThemeToken.MentionBackground -> Res.string.theme_item_mention_background
}

private fun ThemeToken.hint(): StringResource = when (this) {
    ThemeToken.Accent -> Res.string.theme_item_accent_hint
    ThemeToken.Background -> Res.string.theme_item_background_hint
    ThemeToken.Surface -> Res.string.theme_item_surface_hint
    ThemeToken.Rail -> Res.string.theme_item_rail_hint
    ThemeToken.Text -> Res.string.theme_item_text_hint
    ThemeToken.TextMuted -> Res.string.theme_item_text_muted_hint
    ThemeToken.Mention -> Res.string.theme_item_mention_hint
    ThemeToken.MentionBackground -> Res.string.theme_item_mention_background_hint
}

private const val NoItem = -1

/**
 * A lista das cores que se podem trocar, cada uma com a amostra da cor que vale agora. Tocar abre o [ColorEditorDialog],
 * que grava no modo que está valendo ([shownDark] decide qual é, quando o modo é Sistema).
 */
@Composable
internal fun ItemsSection(spec: ThemeSpec, shownDark: Boolean, onChange: (ThemeSpec) -> Unit) {
    // Só o índice vai para o estado salvo: sobrevive a girar a tela sem guardar o enum.
    var editing by rememberSaveable { mutableIntStateOf(NoItem) }
    val variant = spec.variant(shownDark)
    Column {
        SectionTitle(stringResource(Res.string.theme_items_title))
        Text(
            stringResource(Res.string.theme_items_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        ThemeToken.entries.forEachIndexed { index, token ->
            ItemRow(
                token = token,
                color = Color(effectiveColor(spec, shownDark, token)),
                changed = spec.hasOverride(variant, token),
                onClick = { editing = index },
            )
        }
    }
    val token = ThemeToken.entries.getOrNull(editing)
    if (token != null) {
        ColorEditorDialog(spec, token, shownDark, onChange, onDismiss = { editing = NoItem })
    }
}

@Composable
private fun ItemRow(token: ThemeToken, color: Color, changed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(token.label()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (changed) {
                    Text(stringResource(Res.string.theme_item_changed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(token.hint()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Swatch(color, Modifier.size(32.dp))
    }
}

/** Uma amostra redonda. O contorno é para a cor que se confunde com o fundo (o preto no tema OLED, por exemplo). */
@Composable
internal fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}
