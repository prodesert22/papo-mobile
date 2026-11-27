package chat.papo.mobile.ui.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.theme_preview_author_1
import chat.papo.mobile.resources.theme_preview_author_2
import chat.papo.mobile.resources.theme_preview_channel_general
import chat.papo.mobile.resources.theme_preview_channel_news
import chat.papo.mobile.resources.theme_preview_description
import chat.papo.mobile.resources.theme_preview_input
import chat.papo.mobile.resources.theme_preview_message_1
import chat.papo.mobile.resources.theme_preview_message_2
import chat.papo.mobile.resources.theme_preview_tab_channels
import chat.papo.mobile.resources.theme_preview_tab_dms
import chat.papo.mobile.ui.theme.LocalPapoColors
import chat.papo.mobile.ui.theme.PapoTheme
import chat.papo.mobile.ui.theme.ThemeSpec
import org.jetbrains.compose.resources.stringResource

private val PreviewHeight = 224.dp

/**
 * Uma miniatura da interface com as cores de [spec]: a faixa de servidores, a lista com as abas, duas mensagens (uma com
 * menção) e o campo de texto. É desenhada sob um [PapoTheme] aninhado, então reflete cada mudança sem tocar na tela de
 * cima. [systemDark] decide o modo quando o de [spec] é Sistema.
 */
@Composable
fun ThemePreview(spec: ThemeSpec, systemDark: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.theme_preview_description)
    PapoTheme(spec, systemDark) {
        val scheme = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(12.dp)
        Row(
            modifier
                .fillMaxWidth()
                .height(PreviewHeight)
                .clip(shape)
                .background(scheme.background)
                .border(1.dp, scheme.outlineVariant, shape)
                // É um desenho: o leitor de tela lê só a descrição, não os textos de exemplo.
                .clearAndSetSemantics { contentDescription = description },
        ) {
            PreviewRail()
            PreviewList()
            PreviewChat(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreviewRail() {
    val colors = LocalPapoColors.current
    Column(
        Modifier.width(40.dp).fillMaxHeight().background(colors.rail).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) { index ->
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (index == 0) colors.accent else colors.onRailMuted.copy(alpha = 0.4f)),
                )
                if (index == 1) Box(Modifier.size(9.dp).clip(CircleShape).background(colors.mention))
            }
        }
    }
}

@Composable
private fun PreviewList() {
    val colors = LocalPapoColors.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.width(132.dp).fillMaxHeight().background(scheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            PreviewTab(stringResource(Res.string.theme_preview_tab_channels), selected = true)
            Box(Modifier.width(8.dp))
            PreviewTab(stringResource(Res.string.theme_preview_tab_dms), selected = false, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(scheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewText("# " + stringResource(Res.string.theme_preview_channel_general), colors.unreadName, FontWeight.Bold, Modifier.weight(1f))
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.accent))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PreviewText("# " + stringResource(Res.string.theme_preview_channel_news), scheme.onSurfaceVariant, FontWeight.Normal, Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).background(colors.mention).padding(horizontal = 5.dp)) {
                    Text("2", fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = colors.onMention)
                }
            }
        }
    }
}

@Composable
private fun PreviewTab(text: String, selected: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.padding(top = 8.dp)) {
        PreviewText(text, if (selected) scheme.primary else scheme.onSurfaceVariant, if (selected) FontWeight.Bold else FontWeight.Normal)
        Box(Modifier.padding(top = 5.dp).height(2.dp).fillMaxWidth().background(if (selected) scheme.primary else scheme.surface))
    }
}

@Composable
private fun PreviewChat(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxHeight().background(scheme.background).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewMessage(stringResource(Res.string.theme_preview_author_1), stringResource(Res.string.theme_preview_message_1), addressed = false)
            PreviewMessage(stringResource(Res.string.theme_preview_author_2), stringResource(Res.string.theme_preview_message_2), addressed = true)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            PreviewText(stringResource(Res.string.theme_preview_input), scheme.onSurfaceVariant, FontWeight.Normal)
        }
    }
}

@Composable
private fun PreviewMessage(author: String, text: String, addressed: Boolean) {
    val colors = LocalPapoColors.current
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(if (addressed) colors.highlight else Color.Transparent)) {
        if (addressed) Box(Modifier.width(2.dp).height(34.dp).background(colors.highlightBar))
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            PreviewText(author, scheme.primary, FontWeight.Bold)
            PreviewText(text, scheme.onSurface, FontWeight.Normal)
        }
    }
}

@Composable
private fun PreviewText(text: String, color: Color, weight: FontWeight, modifier: Modifier = Modifier) {
    Text(text, modifier, color = color, fontWeight = weight, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
