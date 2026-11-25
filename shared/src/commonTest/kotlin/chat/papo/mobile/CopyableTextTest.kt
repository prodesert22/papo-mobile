package chat.papo.mobile

import chat.papo.mobile.chat.User
import chat.papo.mobile.ui.chat.copyableText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CopyableTextTest {
    private val ana = "0b0e7c1e-1111-2222-3333-444455556666"
    private val bia = "0b0e7c1e-aaaa-bbbb-cccc-ddddeeeeffff"

    private fun user(id: String, username: String, nickname: String? = null) =
        User(id, username, nickname, statusMessage = null, typingPhrase = null, banned = false, roleColor = null, avatarVersion = 0)

    private val users = mapOf(ana to user(ana, "ana"), bia to user(bia, "bia", nickname = "Bia Borges"))

    @Test
    fun mencaoVoltaAserUmArrobaComOUsername() {
        assertEquals("oi @ana!", copyableText("oi @mention(<@$ana>)!", users))
    }

    @Test
    fun usaOUsernameENaoOApelido() {
        // O que se cola numa mensagem nova precisa casar com o username para virar menção de novo.
        assertEquals("@bia", copyableText("@mention(<@$bia>)", users))
    }

    @Test
    fun variasMencoesETextoFicamComoForam() {
        assertEquals(
            "@ana e @bia: **negrito** e `código`\n- item",
            copyableText("@mention(<@$ana>) e @mention(<@$bia>): **negrito** e `código`\n- item", users),
        )
    }

    @Test
    fun mencaoDeQuemNaoSeConheceNaoVazaOFormatoDoServidor() {
        val copied = copyableText("fala @mention(<@$ana>) e @mention(<@ffffffff-ffff-ffff-ffff-ffffffffffff>)", emptyMap())
        assertEquals("fala @usuário e @usuário", copied)
        assertFalse("@mention(" in copied)
    }

    @Test
    fun textoSemMencaoNaoMuda() {
        assertEquals("só texto, sem @ai nem nada", copyableText("só texto, sem @ai nem nada", users))
        assertEquals("", copyableText("", users))
    }

    @Test
    fun nuncaSobraOFormatoDoServidorQuandoOsIdsSaoValidos() {
        val content = "@mention(<@$ana>)@mention(<@$bia>) @mention(<@$ana>)"
        assertFalse("@mention(" in copyableText(content, users))
        assertFalse("@mention(" in copyableText(content, emptyMap()))
    }
}
