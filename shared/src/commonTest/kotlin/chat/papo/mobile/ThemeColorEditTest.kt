package chat.papo.mobile

import androidx.compose.ui.graphics.Color
import chat.papo.mobile.ui.theme.QuickSwatches
import chat.papo.mobile.ui.theme.ThemeMode
import chat.papo.mobile.ui.theme.ThemePresets
import chat.papo.mobile.ui.theme.ThemeSpec
import chat.papo.mobile.ui.theme.ThemeToken
import chat.papo.mobile.ui.theme.ThemeVariant
import chat.papo.mobile.ui.theme.affectsTextContrast
import chat.papo.mobile.ui.theme.contrastRatio
import chat.papo.mobile.ui.theme.effectiveColor
import chat.papo.mobile.ui.theme.formatHexColor
import chat.papo.mobile.ui.theme.hasOverride
import chat.papo.mobile.ui.theme.lowContrastTexts
import chat.papo.mobile.ui.theme.parseHexColor
import chat.papo.mobile.ui.theme.resolveTheme
import chat.papo.mobile.ui.theme.withPickedColor
import chat.papo.mobile.ui.theme.withRestoredColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeColorEditTest {
    private val navy = 0xFF101040
    private val white = 0xFFFFFFFF

    private fun gray(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    // ---- #RRGGBB ----

    @Test
    fun leSeisDigitosComESemOJogoDaVelha() {
        assertEquals(0xFF1E78C8.toInt(), parseHexColor("#1E78C8"))
        assertEquals(0xFF1E78C8.toInt(), parseHexColor("1E78C8"))
        assertEquals(0xFF000000.toInt(), parseHexColor("#000000"))
        assertEquals(white.toInt(), parseHexColor("#FFFFFF"))
    }

    @Test
    fun leMinusculasMaiusculasEComEspacosAoRedor() {
        assertEquals(0xFFAABBCC.toInt(), parseHexColor("#aabbcc"))
        assertEquals(0xFFAABBCC.toInt(), parseHexColor("#AaBbCc"))
        assertEquals(0xFF012345.toInt(), parseHexColor("#012345"))
        assertEquals(0xFF789ABC.toInt(), parseHexColor("  #789abc \n"))
    }

    @Test
    fun formaCurtaDeTresDigitosSeExpande() {
        assertEquals(0xFFAABBCC.toInt(), parseHexColor("#abc"))
        assertEquals(0xFF112233.toInt(), parseHexColor("123"))
        assertEquals(white.toInt(), parseHexColor("#fff"))
    }

    @Test
    fun recusaOQueNaoEUmaCorOpaca() {
        listOf(
            "", "   ", "#", "##123456", "#1", "#12", "#1234", "#12345", "#1234567",
            "#GGGGGG", "#12345G", "#12 456", "xyz", "red",
            "-1a2b3", "#-1a2b3", "+1a2b3c", "#+1a2b3", // sinais não são dígitos
            "#٣٣٣٣٣٣", // dígitos de outro alfabeto
            "#1E78C8FF", "#1E78C880", "#1E78C8F", // com transparência (8 dígitos) ou meio caminho
        ).forEach { assertNull(parseHexColor(it), "entrada: '$it'") }
    }

    @Test
    fun formataEmMaiusculasComZerosAEsquerda() {
        assertEquals("#1E78C8", formatHexColor(0xFF1E78C8.toInt()))
        assertEquals("#00000F", formatHexColor(0xFF00000F.toInt()))
        assertEquals("#000000", formatHexColor(0xFF000000.toInt()))
        assertEquals("#FFFFFF", formatHexColor(white.toInt()))
        assertEquals("#ABCDEF", formatHexColor(0xFFABCDEF.toInt()))
    }

    @Test
    fun formatarIgnoraATransparencia() {
        assertEquals("#FF0000", formatHexColor(0x40FF0000))
        assertEquals("#FF0000", formatHexColor(0x00FF0000))
    }

    @Test
    fun formatarELerVoltamIguais() {
        listOf(0xFF000000, 0xFF1E78C8, 0xFF00000F, 0xFFFFFFFF, 0xFF7F7F7F).map { it.toInt() }.forEach {
            assertEquals(it, parseHexColor(formatHexColor(it)))
        }
    }

    // ---- gravar e restaurar pelo editor ----

    @Test
    fun escolherUmaCorGravaNaVarianteEMarcaOverride() {
        val spec = ThemeSpec(ThemeMode.Dark)
        assertFalse(spec.hasOverride(ThemeVariant.Dark, ThemeToken.Accent))

        val picked = spec.withPickedColor(ThemeVariant.Dark, ThemeToken.Accent, navy.toInt())

        assertTrue(picked.hasOverride(ThemeVariant.Dark, ThemeToken.Accent))
        assertFalse(picked.hasOverride(ThemeVariant.Light, ThemeToken.Accent))
        assertFalse(picked.hasOverride(ThemeVariant.Dark, ThemeToken.Rail))
        assertEquals(navy.toInt(), effectiveColor(picked, systemDark = true, token = ThemeToken.Accent))
    }

    @Test
    fun corEscolhidaFicaOpacaNoSpec() {
        val picked = ThemeSpec().withPickedColor(ThemeVariant.Dark, ThemeToken.Background, 0x40FF0000)

        assertEquals(0xFFFF0000.toInt(), picked.overrides[ThemeVariant.Dark]?.get(ThemeToken.Background))
    }

    @Test
    fun restaurarVoltaACorDaPredefinicaoSoNaqueleItem() {
        val spec = ThemeSpec(ThemeMode.Dark)
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Accent, navy.toInt())
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Rail, white.toInt())

        val restored = spec.withRestoredColor(ThemeVariant.Dark, ThemeToken.Accent)

        assertFalse(restored.hasOverride(ThemeVariant.Dark, ThemeToken.Accent))
        assertTrue(restored.hasOverride(ThemeVariant.Dark, ThemeToken.Rail))
        assertEquals(
            effectiveColor(ThemeSpec(ThemeMode.Dark), systemDark = true, token = ThemeToken.Accent),
            effectiveColor(restored, systemDark = true, token = ThemeToken.Accent),
        )
    }

    @Test
    fun restaurarOUltimoItemLimpaOSpec() {
        val spec = ThemeSpec().withPickedColor(ThemeVariant.Light, ThemeToken.Text, navy.toInt())

        assertEquals(ThemeSpec(), spec.withRestoredColor(ThemeVariant.Light, ThemeToken.Text))
    }

    @Test
    fun restaurarSemOverrideNaoMuda() {
        val spec = ThemeSpec().withPickedColor(ThemeVariant.Dark, ThemeToken.Text, navy.toInt())

        assertEquals(spec, spec.withRestoredColor(ThemeVariant.Dark, ThemeToken.Rail))
        assertEquals(spec, spec.withRestoredColor(ThemeVariant.Light, ThemeToken.Text))
    }

    @Test
    fun cadaVarianteTemAsPropriasCores() {
        val spec = ThemeSpec(ThemeMode.System)
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Background, navy.toInt())
            .withPickedColor(ThemeVariant.Light, ThemeToken.Background, white.toInt())

        assertEquals(navy.toInt(), effectiveColor(spec, systemDark = true, token = ThemeToken.Background))
        assertEquals(white.toInt(), effectiveColor(spec, systemDark = false, token = ThemeToken.Background))
    }

    @Test
    fun corEfetivaEOpacaEUsaAPredefinicao() {
        val papo = effectiveColor(ThemeSpec(ThemeMode.Dark), systemDark = false, token = ThemeToken.Background)
        assertEquals(0xFF0F1E24.toInt(), papo)
        assertNotEquals(papo, effectiveColor(ThemeSpec(ThemeMode.Dark, "ocean"), systemDark = false, token = ThemeToken.Background))
        ThemeToken.entries.forEach {
            assertEquals(0xFF, effectiveColor(ThemeSpec(ThemeMode.Oled), systemDark = false, token = it) ushr 24, "$it")
        }
    }

    // ---- aviso de contraste ----

    @Test
    fun temasSemTrocasNaoTemAviso() {
        ThemePresets.all.forEach { preset ->
            ThemeMode.entries.forEach { mode ->
                val theme = resolveTheme(ThemeSpec(mode, preset.id), systemDark = true)
                assertTrue(lowContrastTexts(theme).isEmpty(), "${preset.id}/$mode")
            }
        }
    }

    @Test
    fun textoIgualAoFundoAvisa() {
        val background = effectiveColor(ThemeSpec(ThemeMode.Dark), true, ThemeToken.Background)
        val spec = ThemeSpec(ThemeMode.Dark).withPickedColor(ThemeVariant.Dark, ThemeToken.Text, background)

        assertTrue(ThemeToken.Text in lowContrastTexts(resolveTheme(spec, systemDark = true)))
    }

    @Test
    fun textoSecundarioIgualAoFundoAvisaSoNele() {
        val background = effectiveColor(ThemeSpec(ThemeMode.Dark), true, ThemeToken.Background)
        val spec = ThemeSpec(ThemeMode.Dark).withPickedColor(ThemeVariant.Dark, ThemeToken.TextMuted, background)

        assertEquals(setOf(ThemeToken.TextMuted), lowContrastTexts(resolveTheme(spec, systemDark = true)))
    }

    @Test
    fun oLimiteDoTextoSecundarioEMenorQueODoTexto() {
        val theme = resolveTheme(ThemeSpec(ThemeMode.Dark), systemDark = true)
        val grounds = listOf(theme.colorScheme.background, theme.colorScheme.surfaceContainer)
        // Um cinza entre 3 e 4,5 de contraste: serve de texto de apoio, mas não de texto principal.
        val mid = gray((0x40..0xFF).first { level ->
            grounds.minOf { contrastRatio(Color(gray(level)), it) }.let { it >= 3f && it < 4.5f }
        })

        val asMuted = ThemeSpec(ThemeMode.Dark).withPickedColor(ThemeVariant.Dark, ThemeToken.TextMuted, mid)
        assertTrue(lowContrastTexts(resolveTheme(asMuted, systemDark = true)).isEmpty())

        val asText = ThemeSpec(ThemeMode.Dark).withPickedColor(ThemeVariant.Dark, ThemeToken.Text, mid)
        assertEquals(setOf(ThemeToken.Text), lowContrastTexts(resolveTheme(asText, systemDark = true)))
    }

    @Test
    fun avisaQuandoSoAsAreasDeItensPerdemOContraste() {
        // Texto claro escolhido; as áreas viram brancas e o fundo continua escuro.
        val spec = ThemeSpec(ThemeMode.Dark)
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Text, gray(0xEA))
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Surface, white.toInt())

        assertTrue(contrastRatio(Color(gray(0xEA)), resolveTheme(spec, true).colorScheme.background) >= 4.5f)
        assertTrue(ThemeToken.Text in lowContrastTexts(resolveTheme(spec, systemDark = true)))
    }

    @Test
    fun avisaQuandoSoOFundoPerdeOContraste() {
        val spec = ThemeSpec(ThemeMode.Dark)
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Text, gray(0xEA))
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Background, white.toInt())

        val theme = resolveTheme(spec, systemDark = true)
        assertTrue(contrastRatio(theme.colorScheme.onSurface, theme.colorScheme.surfaceContainer) >= 4.5f)
        assertTrue(ThemeToken.Text in lowContrastTexts(theme))
    }

    @Test
    fun fundoEAreasClarosSemTextoEscolhidoNaoAvisam() {
        // Sem texto escolhido o motor troca o texto por contraste, então não há o que avisar.
        val spec = ThemeSpec(ThemeMode.Dark)
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Background, white.toInt())
            .withPickedColor(ThemeVariant.Dark, ThemeToken.Surface, white.toInt())

        assertTrue(lowContrastTexts(resolveTheme(spec, systemDark = true)).isEmpty())
    }

    @Test
    fun fundoClaroComAreasEscurasAvisaPorqueNenhumTextoServeAosDois() {
        // O motor não consegue um texto legível sobre um fundo branco e áreas escuras ao mesmo tempo; o editor avisa.
        val spec = ThemeSpec(ThemeMode.Dark).withPickedColor(ThemeVariant.Dark, ThemeToken.Background, white.toInt())

        assertTrue(ThemeToken.Text in lowContrastTexts(resolveTheme(spec, systemDark = true)))
    }

    @Test
    fun soOsItensQueMexemNoTextoEntramNoAviso() {
        val affecting = ThemeToken.entries.filter(::affectsTextContrast)

        assertEquals(listOf(ThemeToken.Background, ThemeToken.Surface, ThemeToken.Text, ThemeToken.TextMuted), affecting)
    }

    // ---- amostras ----

    @Test
    fun amostrasRapidasSaoOpacasEUnicas() {
        assertTrue(QuickSwatches.size >= 10)
        assertEquals(QuickSwatches.size, QuickSwatches.toSet().size)
        assertTrue(QuickSwatches.all { it ushr 24 == 0xFF })
        assertTrue(QuickSwatches.contains(0xFF000000.toInt()) && QuickSwatches.contains(white.toInt()))
    }
}
