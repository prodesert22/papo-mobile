package chat.papo.mobile.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.licenses_libraries
import chat.papo.mobile.resources.licenses_open_failed
import chat.papo.mobile.resources.licenses_open_license
import chat.papo.mobile.resources.licenses_open_page
import chat.papo.mobile.resources.licenses_resource_emoji
import chat.papo.mobile.resources.licenses_resource_emoji_license
import chat.papo.mobile.resources.licenses_resource_font
import chat.papo.mobile.resources.licenses_resource_font_license
import chat.papo.mobile.resources.licenses_resource_unicode
import chat.papo.mobile.resources.licenses_resource_unicode_license
import chat.papo.mobile.resources.licenses_resources
import chat.papo.mobile.resources.licenses_text_missing
import chat.papo.mobile.resources.licenses_text_unavailable
import chat.papo.mobile.resources.licenses_title
import chat.papo.mobile.resources.licenses_unavailable
import chat.papo.mobile.resources.licenses_unknown
import chat.papo.mobile.resources.licenses_version_and_licenses
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import chat.papo.mobile.ui.settings.SettingsPadding
import chat.papo.mobile.ui.settings.SettingsSectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Um texto de licença que o próprio app carrega (fonte e dados que vão no pacote), fora da lista de bibliotecas. */
private class IncludedResource(val path: String, val name: StringResource, val license: StringResource)

private val includedResources = listOf(
    IncludedResource("files/legal/BricolageGrotesque-OFL.txt", Res.string.licenses_resource_font, Res.string.licenses_resource_font_license),
    IncludedResource("files/legal/Emojibase-MIT.txt", Res.string.licenses_resource_emoji, Res.string.licenses_resource_emoji_license),
    IncludedResource("files/legal/Unicode-3.0.txt", Res.string.licenses_resource_unicode, Res.string.licenses_resource_unicode_license),
)

private sealed interface LibrariesState {
    data object Loading : LibrariesState
    data object Unavailable : LibrariesState
    class Ready(val rows: List<LibraryRow>) : LibrariesState
}

/** O que a folha de detalhe mostra: uma biblioteca ou um dos textos incluídos. */
private sealed interface Detail {
    class OfLibrary(val row: LibraryRow) : Detail
    class OfResource(val resource: IncludedResource) : Detail
}

/**
 * Licenças de terceiros: os textos que o app carrega e as bibliotecas que vão no APK, com as licenças que o plugin leu dos
 * POMs. [readDefinitions] devolve o JSON gerado na compilação (lido fora da thread principal); a tela não faz pedido de rede
 * e só guarda as linhas enquanto está aberta. Os endereços dos POMs são dado externo: abrem só pelo filtro de
 * `openExternalUrl` (`http` e `https`).
 */
@Composable
fun LicensesScreen(readDefinitions: suspend () -> String?, onBack: () -> Unit) {
    var state by remember { mutableStateOf<LibrariesState>(LibrariesState.Loading) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    LaunchedEffect(Unit) {
        val json = readDefinitions()
        val rows = withContext(Dispatchers.Default) { parseLibraryRows(json) }
        state = if (rows == null) LibrariesState.Unavailable else LibrariesState.Ready(rows)
    }

    SettingsDetailScaffold(stringResource(Res.string.licenses_title), onBack, scroll = false) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { SettingsSectionTitle(stringResource(Res.string.licenses_resources)) }
            items(includedResources) { resource ->
                LicenseListItem(
                    title = stringResource(resource.name),
                    summary = stringResource(resource.license),
                    onClick = { detail = Detail.OfResource(resource) },
                )
            }
            item { SettingsSectionTitle(stringResource(Res.string.licenses_libraries)) }
            when (val current = state) {
                LibrariesState.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                LibrariesState.Unavailable -> item {
                    Text(
                        stringResource(Res.string.licenses_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = SettingsPadding, vertical = 8.dp),
                    )
                }
                is LibrariesState.Ready -> items(current.rows) { row ->
                    LicenseListItem(
                        title = row.name,
                        summary = librarySummary(row),
                        onClick = { detail = Detail.OfLibrary(row) },
                    )
                }
            }
        }
    }

    when (val shown = detail) {
        null -> Unit
        is Detail.OfLibrary -> LibrarySheet(shown.row, onDismiss = { detail = null })
        is Detail.OfResource -> ResourceSheet(shown.resource, onDismiss = { detail = null })
    }
}

@Composable
private fun librarySummary(row: LibraryRow): String {
    val licenses = if (row.licenses.isEmpty()) stringResource(Res.string.licenses_unknown) else row.licenses.joinToString { it.name }
    return if (row.version == null) licenses else stringResource(Res.string.licenses_version_and_licenses, row.version, licenses)
}

@Composable
private fun LicenseListItem(title: String, summary: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SettingsPadding, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A folha que abre ocupando a altura toda: o texto de uma licença é longo e é para ser lido e selecionado. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(title: String, subtitle: String?, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun LibrarySheet(row: LibraryRow, onDismiss: () -> Unit) {
    val platform = LocalPlatformUi.current
    var openFailed by remember { mutableStateOf(false) }
    val open = { url: String -> openFailed = !platform.openExternalUrl(url) }
    DetailSheet(row.name, row.version, onDismiss) {
        val website = row.website
        if (website != null) TextButton(onClick = { open(website) }) { Text(stringResource(Res.string.licenses_open_page)) }
        if (openFailed) Text(stringResource(Res.string.licenses_open_failed), color = MaterialTheme.colorScheme.error)
        if (row.licenses.isEmpty()) Text(stringResource(Res.string.licenses_unknown), style = MaterialTheme.typography.titleSmall)
        row.licenses.forEach { LicenseSection(it, open) }
    }
}

@Composable
private fun LicenseSection(license: LicenseInfo, open: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(license.name, style = MaterialTheme.typography.titleSmall)
    val text = license.text
    if (text != null) {
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
        return
    }
    Text(
        stringResource(Res.string.licenses_text_missing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val url = license.url
    if (url != null) TextButton(onClick = { open(url) }) { Text(stringResource(Res.string.licenses_open_license)) }
}

/** O texto vem do recurso do pacote; o que falhou na leitura vira uma mensagem, não uma tela vazia. */
@Composable
private fun ResourceSheet(resource: IncludedResource, onDismiss: () -> Unit) {
    val text by produceState<Result<String>?>(null, resource.path) {
        value = try {
            Result.success(Res.readBytes(resource.path).decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    DetailSheet(stringResource(resource.name), stringResource(resource.license), onDismiss) {
        val loaded = text
        when {
            loaded == null -> CircularProgressIndicator()
            loaded.isSuccess -> SelectionContainer { Text(loaded.getOrThrow(), style = MaterialTheme.typography.bodySmall) }
            else -> Text(stringResource(Res.string.licenses_text_unavailable), color = MaterialTheme.colorScheme.error)
        }
    }
}
