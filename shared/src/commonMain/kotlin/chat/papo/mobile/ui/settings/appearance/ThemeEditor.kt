package chat.papo.mobile.ui.settings.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.theme_mode_dark
import chat.papo.mobile.resources.theme_mode_light
import chat.papo.mobile.resources.theme_mode_oled
import chat.papo.mobile.resources.theme_mode_oled_hint
import chat.papo.mobile.resources.theme_mode_system
import chat.papo.mobile.resources.theme_mode_title
import chat.papo.mobile.resources.theme_presets_title
import chat.papo.mobile.resources.theme_preview_title
import chat.papo.mobile.ui.settings.SettingsPadding
import chat.papo.mobile.ui.theme.ThemeMode
import chat.papo.mobile.ui.theme.ThemePreset
import chat.papo.mobile.ui.theme.ThemePresets
import chat.papo.mobile.ui.theme.ThemeSpec
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun ThemeMode.label(): StringResource = when (this) {
    ThemeMode.System -> Res.string.theme_mode_system
    ThemeMode.Light -> Res.string.theme_mode_light
    ThemeMode.Dark -> Res.string.theme_mode_dark
    ThemeMode.Oled -> Res.string.theme_mode_oled
}

/**
 * Os controles de um tema: a prévia, o modo, as predefinições e as cores item por item. Quem usa guarda o [spec] e recebe
 * cada mudança em [onChange]; o editor não grava nada.
 */
@Composable
fun ThemeEditor(spec: ThemeSpec, onChange: (ThemeSpec) -> Unit, modifier: Modifier = Modifier) {
    val systemDark = isSystemInDarkTheme()
    // Com o modo Sistema a prévia mostra os dois lados, e as cores trocadas valem para o lado que está na prévia.
    var previewDark by rememberSaveable { mutableStateOf(systemDark) }
    val shownDark = if (spec.mode == ThemeMode.System) previewDark else systemDark
    Column(modifier.padding(horizontal = SettingsPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PreviewSection(spec, shownDark, onPickDark = { previewDark = it })
        ModeSection(spec, onChange)
        PresetSection(spec, onChange)
        ItemsSection(spec, shownDark, onChange)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PreviewSection(spec: ThemeSpec, shownDark: Boolean, onPickDark: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(Res.string.theme_preview_title)) }
            // Nos outros modos só há um lado e a chave some.
            if (spec.mode == ThemeMode.System) {
                FilterChip(selected = !shownDark, onClick = { onPickDark(false) }, label = { Text(stringResource(Res.string.theme_mode_light)) })
                FilterChip(selected = shownDark, onClick = { onPickDark(true) }, label = { Text(stringResource(Res.string.theme_mode_dark)) })
            }
        }
        ThemePreview(spec, systemDark = shownDark)
    }
}

@Composable
private fun ModeSection(spec: ThemeSpec, onChange: (ThemeSpec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.theme_mode_title))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = spec.mode == mode,
                    onClick = { onChange(spec.withMode(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    // Sem o ícone de marcado, que tiraria a largura de nomes como "Sistema" em telas estreitas.
                    icon = {},
                    label = { Text(stringResource(mode.label()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        if (spec.mode == ThemeMode.Oled) {
            Text(
                stringResource(Res.string.theme_mode_oled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSection(spec: ThemeSpec, onChange: (ThemeSpec) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val selected = ThemePresets.find(spec.preset)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.theme_presets_title))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
        ) {
            ThemePresets.all.forEach { preset ->
                PresetCard(
                    preset = preset,
                    spec = spec,
                    systemDark = systemDark,
                    selected = preset === selected,
                    onClick = { onChange(spec.withPreset(preset.id)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: ThemePreset,
    spec: ThemeSpec,
    systemDark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A amostra é a predefinição pura no modo escolhido, sem as cores trocadas à mão.
    val theme = preset.theme(spec.variant(systemDark))
    val shape = RoundedCornerShape(12.dp)
    val ring = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).clip(shape).background(theme.colorScheme.background).border(ring, shape)) {
            Box(Modifier.size(width = 14.dp, height = 56.dp).background(theme.papo.rail))
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(width = 36.dp, height = 8.dp).clip(CircleShape).background(theme.colorScheme.onSurface.copy(alpha = 0.7f)))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(theme.papo.accent))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(theme.colorScheme.secondary))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(theme.colorScheme.surfaceContainerHigh).border(1.dp, theme.colorScheme.outlineVariant, CircleShape))
                }
            }
        }
        Text(
            stringResource(preset.name),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
