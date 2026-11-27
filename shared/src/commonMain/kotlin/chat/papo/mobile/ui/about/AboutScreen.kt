package chat.papo.mobile.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.papo.mobile.core.BuildInfo
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.about_beta
import chat.papo.mobile.resources.about_build
import chat.papo.mobile.resources.about_copy_version
import chat.papo.mobile.resources.about_debug
import chat.papo.mobile.resources.about_developer
import chat.papo.mobile.resources.about_license_failed
import chat.papo.mobile.resources.about_license_notice
import chat.papo.mobile.resources.about_license_read
import chat.papo.mobile.resources.about_license_title
import chat.papo.mobile.resources.about_link_copied
import chat.papo.mobile.resources.about_link_failed
import chat.papo.mobile.resources.about_link_releases
import chat.papo.mobile.resources.about_link_server
import chat.papo.mobile.resources.about_link_source
import chat.papo.mobile.resources.about_link_terminal
import chat.papo.mobile.resources.about_link_web
import chat.papo.mobile.resources.about_name
import chat.papo.mobile.resources.about_section_developer
import chat.papo.mobile.resources.about_section_license
import chat.papo.mobile.resources.about_section_project
import chat.papo.mobile.resources.about_title
import chat.papo.mobile.resources.about_version
import chat.papo.mobile.resources.about_version_copied
import chat.papo.mobile.resources.licenses_title
import chat.papo.mobile.resources.msg_link_copy
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import chat.papo.mobile.ui.settings.SettingsItem
import chat.papo.mobile.ui.settings.SettingsPadding
import chat.papo.mobile.ui.settings.SettingsSectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Quanto tempo uma confirmação ("Versão copiada") fica na tela. */
private const val NOTICE_MILLIS = 3_000L

/** O aviso embaixo da lista de links. */
private sealed interface LinkNotice {
    /** Nenhum aplicativo abriu [url]. */
    data class Failed(val url: String) : LinkNotice

    data object Copied : LinkNotice
}

/**
 * Sobre o Papo Mobile: versão e build (com a opção de copiá-los), links do projeto, o aviso e o texto da licença
 * (AGPL-3.0, §5(d)), o desenvolvedor e as licenças de terceiros ([onLicenses]). Não faz nenhum pedido de rede: os links
 * abrem fora do app, pelo filtro da regra 6.
 */
@Composable
fun AboutScreen(build: BuildInfo, onLicenses: () -> Unit, onBack: () -> Unit) {
    val platformUi = LocalPlatformUi.current
    val clipboard = LocalClipboardManager.current
    val appName = stringResource(Res.string.about_name)
    val summary = remember(build) { describeVersion(build) }
    var versionCopied by remember { mutableStateOf(false) }
    var linkNotice by remember { mutableStateOf<LinkNotice?>(null) }
    var licenseOpen by remember { mutableStateOf(false) }

    LaunchedEffect(versionCopied) {
        if (!versionCopied) return@LaunchedEffect
        delay(NOTICE_MILLIS)
        versionCopied = false
    }
    LaunchedEffect(linkNotice) {
        if (linkNotice != LinkNotice.Copied) return@LaunchedEffect
        delay(NOTICE_MILLIS)
        linkNotice = null
    }

    val open: (AboutLink) -> Unit = { link ->
        linkNotice = if (platformUi.openExternalUrl(link.url)) null else LinkNotice.Failed(link.url)
    }

    SettingsDetailScaffold(stringResource(Res.string.about_title), onBack) {
        Column(Modifier.fillMaxWidth().padding(horizontal = SettingsPadding, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppMark()
            Spacer(Modifier.height(12.dp))
            Text(appName, style = MaterialTheme.typography.headlineSmall)
            val lines = buildList {
                summary.release?.let { add(stringResource(Res.string.about_version, it)) }
                summary.beta?.let { add(stringResource(Res.string.about_beta, it)) }
                summary.build?.let { add(stringResource(Res.string.about_build, it)) }
                if (summary.development) add(stringResource(Res.string.about_debug))
            }
            for (line in lines) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(versionClipboardText(appName, build)))
                    versionCopied = true
                },
            ) { Text(stringResource(Res.string.about_copy_version)) }
            if (versionCopied) Notice(stringResource(Res.string.about_version_copied))
        }

        SettingsSectionTitle(stringResource(Res.string.about_section_project))
        LinkRow(Icons.Filled.Code, Res.string.about_link_source, AboutLink.Source, open)
        LinkRow(Icons.Filled.NewReleases, Res.string.about_link_releases, AboutLink.Releases, open)
        LinkRow(Icons.Filled.Dns, Res.string.about_link_server, AboutLink.Server, open)
        LinkRow(Icons.Filled.Language, Res.string.about_link_web, AboutLink.Web, open)
        LinkRow(Icons.Filled.Terminal, Res.string.about_link_terminal, AboutLink.Terminal, open)
        when (val notice = linkNotice) {
            null -> {}
            LinkNotice.Copied -> Notice(stringResource(Res.string.about_link_copied))
            is LinkNotice.Failed -> Column(Modifier.padding(horizontal = SettingsPadding)) {
                Notice(stringResource(Res.string.about_link_failed), centered = false)
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(notice.url))
                        linkNotice = LinkNotice.Copied
                    },
                ) { Text(stringResource(Res.string.msg_link_copy)) }
            }
        }

        SettingsSectionTitle(stringResource(Res.string.about_section_developer))
        SettingsItem(
            Icons.Filled.Person,
            stringResource(Res.string.about_developer, DEVELOPER_NAME),
            displayUrl(AboutLink.Developer.url),
            onClick = { open(AboutLink.Developer) },
        )

        SettingsSectionTitle(stringResource(Res.string.about_section_license))
        Text(
            stringResource(Res.string.about_license_notice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = SettingsPadding),
        )
        TextButton(onClick = { licenseOpen = true }, modifier = Modifier.padding(horizontal = SettingsPadding - 12.dp)) {
            Text(stringResource(Res.string.about_license_read))
        }
        SettingsItem(Icons.Filled.Description, stringResource(Res.string.licenses_title), summary = null, onClick = onLicenses)
        Spacer(Modifier.height(16.dp))
    }

    if (licenseOpen) LicenseSheet(onDismiss = { licenseOpen = false })
}

/** O nome do perfil no GitHub; é o único dado pessoal que a tela mostra, e é público. */
private const val DEVELOPER_NAME = "prodesert22"

@Composable
private fun LinkRow(icon: ImageVector, title: StringResource, link: AboutLink, open: (AboutLink) -> Unit) {
    SettingsItem(icon, stringResource(title), displayUrl(link.url), onClick = { open(link) })
}

/** Uma confirmação curta, anunciada pelo leitor de tela quando aparece. */
@Composable
private fun Notice(text: String, centered: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private sealed interface LicenseText {
    data object Loading : LicenseText

    data object Failed : LicenseText

    data class Ready(val text: String) : LicenseText
}

/**
 * A folha com o texto da licença, rolável e selecionável. O texto é lido do recurso ao abrir e só existe enquanto a folha
 * está na tela.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(onDismiss: () -> Unit) {
    var license by remember { mutableStateOf<LicenseText>(LicenseText.Loading) }
    LaunchedEffect(Unit) {
        license = try {
            LicenseText.Ready(readableLicense(Res.readBytes(LICENSE_PATH).decodeToString()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LicenseText.Failed
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = SettingsPadding).padding(bottom = 24.dp)) {
            Text(stringResource(Res.string.about_license_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val current = license) {
                    LicenseText.Loading -> {}
                    LicenseText.Failed -> Text(stringResource(Res.string.about_license_failed), color = MaterialTheme.colorScheme.error)
                    is LicenseText.Ready -> SelectionContainer { Text(current.text, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private const val LICENSE_PATH = "files/legal/LICENSE.txt"

/** O ícone do app (o balão do ícone de inicialização) sobre o fundo dele, que o Compose comum não lê do recurso do Android. */
@Composable
private fun AppMark() {
    Image(
        AppMarkVector,
        contentDescription = null,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0F1E24)),
    )
}

private val AppMarkVector: ImageVector = ImageVector.Builder(
    defaultWidth = 72.dp,
    defaultHeight = 72.dp,
    viewportWidth = 108f,
    viewportHeight = 108f,
).apply {
    addPath(
        addPathNodes("M38,36h32a10,10 0,0 1,10 10v16a10,10 0,0 1,-10 10H52l-11,9v-9h-3a10,10 0,0 1,-10 -10V46a10,10 0,0 1,10 -10z"),
        fill = SolidColor(Color(0xFFFFB238)),
    )
    addPath(
        addPathNodes("M46,54m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0M62,54m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0"),
        fill = SolidColor(Color(0xFF0F1E24)),
    )
}.build()
