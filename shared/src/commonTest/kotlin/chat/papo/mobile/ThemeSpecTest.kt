package chat.papo.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import chat.papo.mobile.ui.theme.DarkColors
import chat.papo.mobile.ui.theme.DarkPapoColors
import chat.papo.mobile.ui.theme.LightColors
import chat.papo.mobile.ui.theme.LightPapoColors
import chat.papo.mobile.ui.theme.ThemeMode
import chat.papo.mobile.ui.theme.ThemePresets
import chat.papo.mobile.ui.theme.ThemeSpec
import chat.papo.mobile.ui.theme.ThemeToken
import chat.papo.mobile.ui.theme.ThemeVariant
import chat.papo.mobile.ui.theme.bestOn
import chat.papo.mobile.ui.theme.contrastRatio
import chat.papo.mobile.ui.theme.luminanceOf
import chat.papo.mobile.ui.theme.resolveTheme
import chat.papo.mobile.ui.theme.windowStyleOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThemeSpecTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val navy = 0xFF101040.toInt()

    private fun spec(mode: ThemeMode, preset: String = "papo", vararg overrides: Triple<ThemeVariant, ThemeToken, Int>) =
        overrides.fold(ThemeSpec(mode, preset)) { acc, (variant, token, color) -> acc.withOverride(variant, token, color) }

    // ---- predefinição Papo: o esquema que o app já tinha ----

    @Test
    fun papoClaroEEscuroSaoOsEsquemasOriginaisSemMudanca() {
        val light = resolveTheme(ThemeSpec(ThemeMode.Light), systemDark = true)
        assertSame(LightColors, light.colorScheme)
        assertEquals(LightPapoColors, light.papo)
        assertEquals(false, light.dark)

        val dark = resolveTheme(ThemeSpec(ThemeMode.Dark), systemDark = false)
        assertSame(DarkColors, dark.colorScheme)
        assertEquals(DarkPapoColors, dark.papo)
        assertEquals(true, dark.dark)
    }

    @Test
    fun padraoSegueOSistema() {
        assertEquals(ThemeMode.System, ThemeSpec().mode)
        assertEquals("papo", ThemeSpec().preset)
        assertSame(DarkColors, resolveTheme(ThemeSpec(), systemDark = true).colorScheme)
        assertSame(LightColors, resolveTheme(ThemeSpec(), systemDark = false).colorScheme)
    }

    @Test
    fun cadaModoEscolheAVariante() {
        assertEquals(ThemeVariant.Light, ThemeSpec(ThemeMode.System).variant(systemDark = false))
        assertEquals(ThemeVariant.Dark, ThemeSpec(ThemeMode.System).variant(systemDark = true))
        assertEquals(ThemeVariant.Light, ThemeSpec(ThemeMode.Light).variant(systemDark = true))
        assertEquals(ThemeVariant.Dark, ThemeSpec(ThemeMode.Dark).variant(systemDark = false))
        assertEquals(ThemeVariant.Oled, ThemeSpec(ThemeMode.Oled).variant(systemDark = false))
    }

    // ---- OLED ----

    @Test
    fun oledTemFundoSuperficiesEFaixaPretos() {
        ThemePresets.all.forEach { preset ->
            val theme = resolveTheme(ThemeSpec(ThemeMode.Oled, preset.id), systemDark = false)
            val scheme = theme.colorScheme
            val blacks = listOf(
                scheme.background, scheme.surface, scheme.surfaceVariant, scheme.surfaceContainerLowest, scheme.surfaceContainerLow,
                scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, theme.papo.rail,
            )
            assertTrue(blacks.all { it == Color.Black }, "${preset.id}: algum fundo não é preto")
            assertTrue(theme.dark)
        }
    }

    @Test
    fun oledTemContornosVisiveisETextoLegivel() {
        ThemePresets.all.forEach { preset ->
            val theme = resolveTheme(ThemeSpec(ThemeMode.Oled, preset.id), systemDark = false)
            val scheme = theme.colorScheme
            assertTrue(contrastRatio(scheme.outline, Color.Black) >= 3f, "${preset.id}: contorno")
            assertTrue(scheme.outlineVariant != Color.Black, "${preset.id}: contorno discreto")
            assertTrue(contrastRatio(scheme.onSurface, Color.Black) >= 7f, "${preset.id}: texto")
            assertTrue(contrastRatio(scheme.onSurfaceVariant, Color.Black) >= 4.5f, "${preset.id}: texto secundário")
            assertTrue(contrastRatio(theme.papo.onRail, Color.Black) >= 7f, "${preset.id}: texto da faixa")
        }
    }

    // ---- predefinições ----

    @Test
    fun todasAsPredefinicoesTemTextoLegivelEmCadaModo() {
        ThemePresets.all.forEach { preset ->
            ThemeVariant.entries.forEach { variant ->
                val theme = preset.theme(variant)
                val scheme = theme.colorScheme
                assertTrue(contrastRatio(scheme.onBackground, scheme.background) >= 4.5f, "${preset.id}/$variant: fundo")
                assertTrue(contrastRatio(scheme.onSurface, scheme.surfaceContainer) >= 4.5f, "${preset.id}/$variant: áreas")
                assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5f, "${preset.id}/$variant: destaque")
                assertTrue(contrastRatio(theme.papo.onRail, theme.papo.rail) >= 4.5f, "${preset.id}/$variant: faixa")
                assertTrue(contrastRatio(theme.papo.onAccent, theme.papo.accent) >= 4.5f, "${preset.id}/$variant: onAccent")
            }
        }
    }

    @Test
    fun predefinicoesTemIdsUnicosECoresDiferentes() {
        val ids = ThemePresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("papo", ids.first())
        val primaries = ThemePresets.all.map { it.theme(ThemeVariant.Dark).colorScheme.primary }
        assertEquals(primaries.size, primaries.toSet().size)
        assertTrue(ThemePresets.all.size in 5..7)
    }

    @Test
    fun predefinicaoDesconhecidaCaiNoPapo() {
        assertSame(ThemePresets.Papo, ThemePresets.find("não existe"))
        assertSame(ThemePresets.all[1], ThemePresets.find(ThemePresets.all[1].id))
        assertSame(LightColors, resolveTheme(ThemeSpec(ThemeMode.Light, "outra-versao"), systemDark = false).colorScheme)
    }

    // ---- cores trocadas à mão ----

    @Test
    fun overrideValeSoNaVarianteDele() {
        val spec = spec(ThemeMode.System, "papo", Triple(ThemeVariant.Dark, ThemeToken.Background, navy))

        assertEquals(Color(navy), resolveTheme(spec, systemDark = true).colorScheme.background)
        assertEquals(Color(navy), resolveTheme(spec, systemDark = true).colorScheme.surface)
        assertSame(LightColors, resolveTheme(spec, systemDark = false).colorScheme)
        assertEquals(Color.Black, resolveTheme(spec.withMode(ThemeMode.Oled), systemDark = true).colorScheme.background)
    }

    @Test
    fun fundoClaroNumTemaEscuroTrocaOTextoPorContraste() {
        val theme = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Background, white)), systemDark = true)

        assertEquals(Color.White, theme.colorScheme.background)
        assertTrue(contrastRatio(theme.colorScheme.onBackground, Color.White) >= 4.5f)
        assertTrue(contrastRatio(theme.colorScheme.onSurface, Color.White) >= 4.5f)
        assertTrue(contrastRatio(theme.colorScheme.onSurfaceVariant, Color.White) >= 4.5f)
    }

    @Test
    fun fundoEscuroNumTemaClaroTambemTrocaOTexto() {
        val theme = resolveTheme(spec(ThemeMode.Light, "papo", Triple(ThemeVariant.Light, ThemeToken.Background, black)), systemDark = false)

        assertTrue(contrastRatio(theme.colorScheme.onBackground, Color.Black) >= 4.5f)
    }

    @Test
    fun textoQueJaEraLegivelNaoMuda() {
        // Fundo parecido com o original: o texto da predefinição continua o mesmo.
        val darkBackground = 0xFF0C1A20.toInt()
        val theme = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Background, darkBackground)), systemDark = true)

        assertEquals(DarkColors.onSurface, theme.colorScheme.onSurface)
        assertEquals(DarkColors.onSurfaceVariant, theme.colorScheme.onSurfaceVariant)
    }

    @Test
    fun textoEscolhidoPeloUsuarioValeComoEsta() {
        val chosenText = 0xFF808080.toInt()
        val chosenMuted = 0xFF707070.toInt()
        val theme = resolveTheme(
            spec(
                ThemeMode.Dark, "papo",
                Triple(ThemeVariant.Dark, ThemeToken.Background, white),
                Triple(ThemeVariant.Dark, ThemeToken.Text, chosenText),
                Triple(ThemeVariant.Dark, ThemeToken.TextMuted, chosenMuted),
            ),
            systemDark = true,
        )

        assertEquals(Color(chosenText), theme.colorScheme.onSurface)
        assertEquals(Color(chosenText), theme.colorScheme.onBackground)
        assertEquals(Color(chosenMuted), theme.colorScheme.onSurfaceVariant)
    }

    @Test
    fun areasDeItensSaemDaCorEscolhida() {
        val dark = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Surface, navy)), systemDark = true).colorScheme
        assertEquals(Color(navy), dark.surfaceContainer)
        assertEquals(Color(navy), dark.surfaceVariant)
        assertTrue(luminanceOf(dark.surfaceContainerLowest) < luminanceOf(dark.surfaceContainer))
        assertTrue(luminanceOf(dark.surfaceContainerHighest) > luminanceOf(dark.surfaceContainer))

        val light = resolveTheme(spec(ThemeMode.Light, "papo", Triple(ThemeVariant.Light, ThemeToken.Surface, 0xFFDDDDDD.toInt())), systemDark = false).colorScheme
        assertTrue(luminanceOf(light.surfaceContainerLowest) > luminanceOf(light.surfaceContainer))
        // O fundo não foi tocado.
        assertEquals(LightColors.background, light.background)
    }

    @Test
    fun destaqueTrocaAAcaoEOTextoSobreEla() {
        val theme = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Accent, navy)), systemDark = true)

        assertEquals(Color(navy), theme.colorScheme.primary)
        assertEquals(Color(navy), theme.papo.accent)
        assertEquals(Color.White, theme.colorScheme.onPrimary)
        assertEquals(Color.White, theme.papo.onAccent)
        assertTrue(contrastRatio(theme.colorScheme.onPrimaryContainer, theme.colorScheme.primaryContainer) >= 4.5f)

        val bright = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Accent, 0xFFFFFF00.toInt())), systemDark = true)
        assertEquals(Color.Black, bright.colorScheme.onPrimary)
    }

    @Test
    fun faixaDeServidoresTrocaOTextoSobreEla() {
        val theme = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Rail, white)), systemDark = true)

        assertEquals(Color.White, theme.papo.rail)
        assertTrue(contrastRatio(theme.papo.onRail, Color.White) >= 4.5f)
        assertTrue(contrastRatio(theme.papo.onRailMuted, Color.White) >= 3f)
    }

    @Test
    fun mencaoEFundoDaMencao() {
        val theme = resolveTheme(
            spec(
                ThemeMode.Dark, "papo",
                Triple(ThemeVariant.Dark, ThemeToken.Mention, 0xFFFFF000.toInt()),
                Triple(ThemeVariant.Dark, ThemeToken.MentionBackground, red),
            ),
            systemDark = true,
        )

        assertEquals(Color.Black, theme.papo.onMention)
        assertEquals(Color(0xFFFFF000), theme.papo.mention)
        assertEquals(Color.Red, theme.papo.highlightBar)
        // Só o vermelho muda: a transparência é a da predefinição, para o texto da mensagem continuar visível.
        assertEquals(Color.Red.copy(alpha = DarkPapoColors.highlight.alpha), theme.papo.highlight)
    }

    @Test
    fun transparenciaDaCorEscolhidaEIgnorada() {
        val translucent = 0x40FF0000
        val theme = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Background, translucent)), systemDark = true)

        assertEquals(Color(0xFFFF0000), theme.colorScheme.background)
    }

    @Test
    fun overrideSobreOledMantemOsOutrosPretos() {
        val theme = resolveTheme(spec(ThemeMode.Oled, "papo", Triple(ThemeVariant.Oled, ThemeToken.Accent, red)), systemDark = false)

        assertEquals(Color.Red, theme.colorScheme.primary)
        assertEquals(Color.Black, theme.colorScheme.background)
        assertEquals(Color.Black, theme.papo.rail)
    }

    @Test
    fun colorOfDevolveACorEfetivaDoItem() {
        val theme = resolveTheme(spec(ThemeMode.Dark, "papo", Triple(ThemeVariant.Dark, ThemeToken.Rail, navy)), systemDark = true)

        assertEquals(Color(navy), theme.colorOf(ThemeToken.Rail))
        assertEquals(DarkColors.background, theme.colorOf(ThemeToken.Background))
        assertEquals(DarkPapoColors.accent, theme.colorOf(ThemeToken.Accent))
        assertEquals(DarkColors.surfaceContainer, theme.colorOf(ThemeToken.Surface))
        assertEquals(DarkColors.onSurface, theme.colorOf(ThemeToken.Text))
        assertEquals(DarkColors.onSurfaceVariant, theme.colorOf(ThemeToken.TextMuted))
        assertEquals(DarkPapoColors.mention, theme.colorOf(ThemeToken.Mention))
        assertEquals(DarkPapoColors.highlightBar, theme.colorOf(ThemeToken.MentionBackground))
    }

    // ---- edição do spec ----

    @Test
    fun trocarDePredefinicaoLimpaAsCoresTrocadas() {
        val edited = ThemeSpec().withOverride(ThemeVariant.Dark, ThemeToken.Accent, red)

        assertEquals(ThemeSpec(preset = "ocean"), edited.withPreset("ocean"))
        assertSame(edited, edited.withPreset("papo")) // a mesma predefinição não perde o que foi ajustado
    }

    @Test
    fun restaurarRemoveACorEAVarianteVazia() {
        val one = ThemeSpec().withOverride(ThemeVariant.Dark, ThemeToken.Accent, red)
        val two = one.withOverride(ThemeVariant.Dark, ThemeToken.Rail, navy)

        assertEquals(mapOf(ThemeToken.Rail to navy), two.withOverride(ThemeVariant.Dark, ThemeToken.Accent, null).overrides[ThemeVariant.Dark])
        assertEquals(emptyMap(), one.withOverride(ThemeVariant.Dark, ThemeToken.Accent, null).overrides)
        assertEquals(one, one.withOverride(ThemeVariant.Light, ThemeToken.Accent, null))
    }

    // ---- gravação (JSON) ----

    @Test
    fun jsonVoltaIgual() {
        val spec = ThemeSpec(ThemeMode.Oled, "rose")
            .withOverride(ThemeVariant.Oled, ThemeToken.Accent, red)
            .withOverride(ThemeVariant.Light, ThemeToken.Background, navy)

        assertEquals(spec, ThemeSpec.decode(spec.encode()))
        assertEquals(ThemeSpec(), ThemeSpec.decode(ThemeSpec().encode()))
    }

    @Test
    fun vazioOuEstragadoCaiNoPadrao() {
        listOf(null, "", "   ", "isto não é json", "{", "[]", "42", "null", """{"mode": 7}""").forEach {
            assertEquals(ThemeSpec(), ThemeSpec.decode(it), "entrada: $it")
            assertNull(ThemeSpec.decodeOrNull(it), "entrada: $it")
        }
    }

    @Test
    fun formatoDesconhecidoCaiNoPadraoMasCamposExtrasSaoIgnorados() {
        // Uma variante que este código não conhece, como a de uma versão futura, invalida o tema inteiro.
        assertNull(ThemeSpec.decodeOrNull("""{"mode":"Dark","overrides":{"Sepia":{"Accent":-1}}}"""))
        assertNull(ThemeSpec.decodeOrNull("""{"overrides":{"Dark":{"Glitter":-1}}}"""))
        // Um campo novo é ignorado e um modo desconhecido volta ao padrão do campo.
        assertEquals(ThemeSpec(ThemeMode.Dark, "rose"), ThemeSpec.decode("""{"mode":"Dark","preset":"rose","novo":1}"""))
        assertEquals(ThemeSpec(ThemeMode.System, "rose"), ThemeSpec.decode("""{"mode":"Sepia","preset":"rose"}"""))
    }

    @Test
    fun predefinicaoDesconhecidaNoJsonFicaNoSpecEResolvePeloPapo() {
        val spec = ThemeSpec.decode("""{"mode":"Light","preset":"futuro"}""")

        assertEquals("futuro", spec.preset)
        assertSame(LightColors, resolveTheme(spec, systemDark = false).colorScheme)
    }

    // ---- janela (fora do Compose) ----

    @Test
    fun estiloDaJanelaSegueOFundoEOModo() {
        val light = windowStyleOf(ThemeSpec(ThemeMode.Light), systemDark = true)
        assertEquals(LightColors.background.toArgb(), light.backgroundArgb)
        assertEquals(false, light.dark)

        val oled = windowStyleOf(ThemeSpec(ThemeMode.Oled), systemDark = false)
        assertEquals(black, oled.backgroundArgb)
        assertEquals(true, oled.dark)

        assertEquals(DarkColors.background.toArgb(), windowStyleOf(ThemeSpec(), systemDark = true).backgroundArgb)
    }

    // ---- luminância e contraste ----

    @Test
    fun contrasteEntrePretoEBrancoE21() {
        assertEquals(0f, luminanceOf(Color.Black))
        assertEquals(1f, luminanceOf(Color.White), 0.001f)
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.001f)
        // Não depende da ordem dos argumentos.
        assertEquals(contrastRatio(Color.Red, Color.Blue), contrastRatio(Color.Blue, Color.Red))
    }

    @Test
    fun luminanciaUsaOBranchoDeCadaCanal() {
        // Verde pesa mais que vermelho e azul; e há o ramo linear do gama para valores muito escuros.
        assertTrue(luminanceOf(Color.Green) > luminanceOf(Color.Red))
        assertTrue(luminanceOf(Color.Red) > luminanceOf(Color.Blue))
        assertEquals(0.01f / 12.92f * 0.2126f, luminanceOf(Color(red = 0.01f, green = 0f, blue = 0f)), 0.0001f)
    }

    @Test
    fun melhorCorSobreUmFundo() {
        assertEquals(Color.White, bestOn(Color.Black))
        assertEquals(Color.Black, bestOn(Color.White))
        assertEquals(Color.White, bestOn(Color(navy)))
        assertNotEquals(bestOn(Color.Yellow), bestOn(Color.Blue))
    }
}
