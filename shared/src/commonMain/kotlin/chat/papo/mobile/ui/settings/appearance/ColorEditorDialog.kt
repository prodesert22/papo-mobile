package chat.papo.mobile.ui.settings.appearance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.theme_color_brightness
import chat.papo.mobile.resources.theme_color_contrast_warning
import chat.papo.mobile.resources.theme_color_editing_variant
import chat.papo.mobile.resources.theme_color_hex_hint
import chat.papo.mobile.resources.theme_color_hex_label
import chat.papo.mobile.resources.theme_color_picker_description
import chat.papo.mobile.resources.theme_color_restore
import chat.papo.mobile.resources.theme_color_swatch_description
import chat.papo.mobile.resources.theme_color_swatches
import chat.papo.mobile.resources.theme_mode_dark
import chat.papo.mobile.resources.theme_mode_light
import chat.papo.mobile.resources.theme_mode_oled
import chat.papo.mobile.ui.settings.SettingsPadding
import chat.papo.mobile.ui.theme.QuickSwatches
import chat.papo.mobile.ui.theme.ThemeSpec
import chat.papo.mobile.ui.theme.ThemeToken
import chat.papo.mobile.ui.theme.ThemeVariant
import chat.papo.mobile.ui.theme.affectsTextContrast
import chat.papo.mobile.ui.theme.effectiveColor
import chat.papo.mobile.ui.theme.formatHexColor
import chat.papo.mobile.ui.theme.hasOverride
import chat.papo.mobile.ui.theme.lowContrastTexts
import chat.papo.mobile.ui.theme.parseHexColor
import chat.papo.mobile.ui.theme.resolveTheme
import chat.papo.mobile.ui.theme.withPickedColor
import chat.papo.mobile.ui.theme.withRestoredColor
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private const val Opaque = 0xFF000000.toInt()
private const val CommitDelayMillis = 400L

private fun ThemeVariant.label() = when (this) {
    ThemeVariant.Light -> Res.string.theme_mode_light
    ThemeVariant.Dark -> Res.string.theme_mode_dark
    ThemeVariant.Oled -> Res.string.theme_mode_oled
}

/**
 * A tela cheia que edita a cor de um item: a prévia fica fixa no alto, ao vivo, e embaixo vêm o seletor, o campo
 * `#RRGGBB`, as amostras e o Restaurar. O seletor só desenha a cor enquanto o dedo se move; a mudança vai para [onChange]
 * quando o dedo sai (ou para por um instante), quando o código ou uma amostra é escolhido e quando a tela fecha, para não
 * gravar a cada quadro.
 */
@Composable
internal fun ColorEditorDialog(
    spec: ThemeSpec,
    token: ThemeToken,
    systemDark: Boolean,
    onChange: (ThemeSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val variant = spec.variant(systemDark)
    val current = effectiveColor(spec, systemDark, token)
    // A cor que o dedo está escolhendo e que ainda não foi para o tema; nula quando não há nada pendente.
    var draft by remember { mutableStateOf<Int?>(null) }
    val shown = draft ?: current
    val previewSpec = draft?.let { spec.withPickedColor(variant, token, it) } ?: spec
    val controller = rememberColorPickerController()

    fun commit(argb: Int) {
        draft = null
        onChange(spec.withPickedColor(variant, token, argb))
    }

    // Um toque no controle de brilho não avisa que terminou: se o dedo para ou sai sem avisar, a cor pendente vai sozinha.
    LaunchedEffect(draft) {
        val pending = draft ?: return@LaunchedEffect
        delay(CommitDelayMillis)
        commit(pending)
    }

    fun close() {
        draft?.let(::commit)
        onDismiss()
    }

    // Escolhas que não vêm do seletor também precisam mover o seletor; as que vêm dele, não (voltaria a ele com arredondamento).
    fun choose(argb: Int) {
        controller.selectByColor(Color(argb), fromUser = false)
        commit(argb)
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // O safeDrawing inclui o teclado: a área rolável encolhe e o campo de texto continua à vista.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::close) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(token.label()), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            stringResource(Res.string.theme_color_editing_variant, stringResource(variant.label())),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (spec.hasOverride(variant, token)) {
                        TextButton(onClick = {
                            draft = null
                            val restored = spec.withRestoredColor(variant, token)
                            controller.selectByColor(Color(effectiveColor(restored, systemDark, token)), fromUser = false)
                            onChange(restored)
                        }) { Text(stringResource(Res.string.theme_color_restore)) }
                    }
                }
                Column(Modifier.padding(horizontal = SettingsPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemePreview(previewSpec, systemDark, height = CompactPreviewHeight)
                    val warn = affectsTextContrast(token) && lowContrastTexts(resolveTheme(previewSpec, systemDark)).isNotEmpty()
                    if (warn) {
                        Text(
                            stringResource(Res.string.theme_color_contrast_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = SettingsPadding, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val pickerDescription = stringResource(Res.string.theme_color_picker_description)
                    HsvColorPicker(
                        modifier = Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = pickerDescription },
                        controller = controller,
                        initialColor = Color(current),
                        onColorChanged = { if (it.fromUser) draft = it.color.toArgb() or Opaque },
                        onColorPickingFinished = { draft?.let(::commit) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.theme_color_brightness), style = MaterialTheme.typography.labelMedium)
                        BrightnessSlider(
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            controller = controller,
                            initialColor = Color(current),
                            onFinish = { draft?.let(::commit) },
                        )
                    }
                    HexField(shown, onValid = ::choose)
                    Swatches(onPick = ::choose)
                }
            }
        }
    }
}

/**
 * O campo `#RRGGBB`. O texto digitado fica como está (é permitido passar por valores inválidos até chegar ao certo); só um
 * valor válido muda a cor, e uma cor que muda por outro caminho reescreve o texto.
 */
@Composable
private fun HexField(color: Int, onValid: (Int) -> Unit) {
    var text by remember { mutableStateOf(formatHexColor(color)) }
    LaunchedEffect(color) {
        if (parseHexColor(text) != (color or Opaque)) text = formatHexColor(color)
    }
    val invalid = parseHexColor(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.take(MaxHexInput)
            parseHexColor(text)?.let(onValid)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.theme_color_hex_label)) },
        supportingText = { Text(stringResource(Res.string.theme_color_hex_hint)) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
}

private const val MaxHexInput = 16

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.theme_color_swatches), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickSwatches.forEach { argb ->
                val description = stringResource(Res.string.theme_color_swatch_description, formatHexColor(argb))
                Swatch(
                    Color(argb),
                    Modifier
                        .size(40.dp)
                        .clickable(role = Role.Button, onClick = { onPick(argb) })
                        .semantics { contentDescription = description },
                )
            }
        }
    }
}
