package chat.papo.mobile

import chat.papo.mobile.chat.EmojiMatch
import chat.papo.mobile.chat.EmojiPiece
import chat.papo.mobile.chat.EmojiText
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiTextTest {
    private val names = mapOf("party_parrot" to "id1", "ok" to "id2", "30" to "id3")
    private val unicode = UnicodeEmojiCatalog.parse(
        listOf(
            "😀\t0\trosto risonho\tgrinning face\tfeliz\tgrinning",
            "👍\t1\tpolegar para cima\tthumbs up\tjoia\t+1|thumbsup",
        ).joinToString("\n"),
    )

    @Test
    fun trocaOsNomesQueOServidorTem() {
        assertEquals(
            listOf(
                EmojiPiece.Plain("oi "),
                EmojiPiece.Custom("party_parrot", "id1"),
                EmojiPiece.Plain(" e "),
                EmojiPiece.Custom("ok", "id2"),
            ),
            EmojiText.split("oi :party_parrot: e :ok:", names),
        )
    }

    @Test
    fun matchesDevolveOndeCadaEmojiFicaNoTexto() {
        assertEquals(
            listOf(EmojiMatch(3, 17, "party_parrot", "id1"), EmojiMatch(18, 22, "ok", "id2")),
            EmojiText.matches("oi :party_parrot: :ok: :nao:", names),
        )
        assertTrue(EmojiText.matches(":ok:", emptyMap()).isEmpty())
        assertTrue(EmojiText.matches("sem emoji", names).isEmpty())
    }

    @Test
    fun matchesPulaOQueCaiEmTrechoLiteral() {
        // `:ok:` fica nas posições 0 a 3; um trecho que toque qualquer uma delas o deixa como texto.
        assertTrue(EmojiText.matches(":ok:", names, listOf(0..0)).isEmpty())
        assertTrue(EmojiText.matches(":ok:", names, listOf(3..9)).isEmpty())
        assertEquals(1, EmojiText.matches(":ok: x", names, listOf(4..5)).size)
        assertEquals(1, EmojiText.matches("x :ok:", names, listOf(0..1)).size)
    }

    @Test
    fun nomeQueNaoExisteFicaComoFoiEscrito() {
        assertEquals(listOf(EmojiPiece.Plain("isso :nao_existe: fica")), EmojiText.split("isso :nao_existe: fica", names))
    }

    @Test
    fun maiusculaImportaComoNoClienteWeb() {
        assertEquals(listOf(EmojiPiece.Plain(":OK:")), EmojiText.split(":OK:", names))
    }

    @Test
    fun horaComDoisPontosSoViraEmojiSeExisteEmojiComEsseNome() {
        assertEquals(listOf(EmojiPiece.Plain("10:30:45")), EmojiText.split("10:30:45", mapOf("ok" to "x")))
        // Com um emoji chamado "30", o `:30:` do meio vira imagem, como no cliente web.
        assertEquals(
            listOf(EmojiPiece.Plain("10"), EmojiPiece.Custom("30", "id3"), EmojiPiece.Plain("45")),
            EmojiText.split("10:30:45", names),
        )
    }

    @Test
    fun doisEmojisColadosENomeNaPonta() {
        assertEquals(
            listOf(EmojiPiece.Custom("ok", "id2"), EmojiPiece.Custom("party_parrot", "id1")),
            EmojiText.split(":ok::party_parrot:", names),
        )
    }

    @Test
    fun semEmojiNoServidorOTextoNaoMuda() {
        assertEquals(listOf(EmojiPiece.Plain(":ok:")), EmojiText.split(":ok:", emptyMap()))
        assertEquals(listOf(EmojiPiece.Plain("")), EmojiText.split("", names))
    }

    @Test
    fun mensagemSoDeEmojiFicaGrande() {
        assertTrue(EmojiText.isEmojiOnly(":ok:", names, unicode))
        assertTrue(EmojiText.isEmojiOnly("😀", names, unicode))
        assertTrue(EmojiText.isEmojiOnly("😀 👍  :ok:\n:party_parrot:", names, unicode))
    }

    @Test
    fun qualquerOutraCoisaDesfazOTamanhoGrande() {
        assertFalse(EmojiText.isEmojiOnly("oi 😀", names, unicode))
        assertFalse(EmojiText.isEmojiOnly(":ok: oi", names, unicode))
        assertFalse(EmojiText.isEmojiOnly(":nao_existe:", names, unicode))
        assertFalse(EmojiText.isEmojiOnly("", names, unicode))
        assertFalse(EmojiText.isEmojiOnly("   ", names, unicode))
        assertFalse(EmojiText.isEmojiOnly("`:ok:`", names, unicode))
    }

    @Test
    fun passarDoLimiteVoltaAoTamanhoNormal() {
        assertTrue(EmojiText.isEmojiOnly(":ok:".repeat(27), names, unicode))
        assertFalse(EmojiText.isEmojiOnly(":ok:".repeat(28), names, unicode))
    }

    @Test
    fun semCatalogoUnicodeSoContaOsDoServidor() {
        assertTrue(EmojiText.isEmojiOnly(":ok:", names, null))
        assertFalse(EmojiText.isEmojiOnly("😀", names, null))
    }
}
