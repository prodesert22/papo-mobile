package chat.papo.mobile

import chat.papo.mobile.chat.ReactorCursor
import chat.papo.mobile.chat.mergeReactors
import chat.papo.mobile.chat.nextReactorCursor
import chat.papo.mobile.chat.orderLike
import chat.papo.mobile.data.api.ReactionGroupDto
import chat.papo.mobile.data.api.ReactionSummaryDto
import chat.papo.mobile.data.api.ReactionUserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A leitura de quem reagiu: juntar páginas, achar o cursor da seguinte e pôr os grupos na ordem das bolinhas. */
class ReactorsTest {
    private fun reaction(id: String, createdAt: String) = ReactionUserDto(id, "u-$id", createdAt)

    private fun group(unicode: String?, vararg users: ReactionUserDto, emojiId: String? = null) =
        ReactionGroupDto(emojiId, unicode, users.size, users.toList())

    private fun at(second: Int) = "2026-11-24T12:00:${second.toString().padStart(2, '0')}Z"

    // Cursor da página seguinte

    @Test
    fun paginaVaziaNaoTemCursor() {
        assertNull(nextReactorCursor(emptyList()))
        assertNull(nextReactorCursor(listOf(group("🔥"))))
    }

    @Test
    fun ocursorEAReacaoMaisAntigaDaPagina() {
        val page = listOf(group("🔥", reaction("r1", at(10)), reaction("r2", at(8))))
        assertEquals(ReactorCursor(at(8), "r2"), nextReactorCursor(page))
    }

    @Test
    fun ocursorNaoEOUltimoUsuarioDoUltimoGrupo() {
        // A última linha da página (r3) está no 🔥, que apareceu antes do ❤: o último usuário do último grupo é r2.
        val page = listOf(
            group("🔥", reaction("r1", at(10)), reaction("r3", at(8))),
            group("❤", reaction("r2", at(9))),
        )
        assertEquals(ReactorCursor(at(8), "r3"), nextReactorCursor(page))
    }

    @Test
    fun ocursorComparaOInstanteENaoOTextoEVoltaComoVeio() {
        // O RFC 3339 corta os zeros finais: como texto, ".5Z" vem antes de "Z" e ".123456Z" antes de ".12Z".
        val seconds = "2026-11-24T12:00:01Z"
        val half = "2026-11-24T12:00:01.5Z"
        assertEquals(ReactorCursor(seconds, "b"), nextReactorCursor(listOf(group("🔥", reaction("a", half), reaction("b", seconds)))))

        val short = "2026-11-24T12:00:01.12Z"
        val long = "2026-11-24T12:00:01.123456Z"
        assertEquals(ReactorCursor(short, "b"), nextReactorCursor(listOf(group("🔥", reaction("a", long), reaction("b", short)))))
    }

    @Test
    fun noMesmoInstanteDesempataPeloMenorId() {
        val page = listOf(group("🔥", reaction("b", at(5))), group("❤", reaction("a", at(5)), reaction("c", at(5))))
        assertEquals(ReactorCursor(at(5), "a"), nextReactorCursor(page))
    }

    @Test
    fun reacaoComDataIlegivelNaoEntraNaContaEPaginaSoDeIlegiveisNaoTemCursor() {
        assertEquals(
            ReactorCursor(at(7), "r2"),
            nextReactorCursor(listOf(group("🔥", reaction("r1", "ontem"), reaction("r2", at(7))))),
        )
        assertNull(nextReactorCursor(listOf(group("🔥", reaction("r1", "ontem"), reaction("r2", "")))))
    }

    // Juntar páginas

    @Test
    fun grupoPartidoEntreDuasPaginasViraUmSo() {
        val first = listOf(group("🔥", reaction("r1", at(10)), reaction("r2", at(9))), group("❤", reaction("r3", at(8))))
        val second = listOf(group("🔥", reaction("r4", at(7))), group("❤", reaction("r5", at(6))))

        val merged = mergeReactors(first, second)

        assertEquals(listOf("🔥", "❤"), merged.map { it.unicode })
        assertEquals(listOf("r1", "r2", "r4"), merged[0].users.map { it.id })
        assertEquals(listOf("r3", "r5"), merged[1].users.map { it.id })
        assertEquals(listOf(3, 2), merged.map { it.count }) // o que já foi lido, não o total do servidor
    }

    @Test
    fun emojiNovoEntraDepoisDosQueJaEstavam() {
        val merged = mergeReactors(listOf(group("🔥", reaction("r1", at(10)))), listOf(group("👍", reaction("r2", at(9))), group("🔥", reaction("r3", at(8)))))
        assertEquals(listOf("🔥", "👍"), merged.map { it.unicode })
    }

    @Test
    fun amesmaReacaoEntreDuasPaginasEntraUmaVezSo() {
        val first = listOf(group("🔥", reaction("r1", at(10)), reaction("r2", at(9))))
        val second = listOf(group("🔥", reaction("r2", at(9)), reaction("r3", at(8))))

        val merged = mergeReactors(first, second)

        assertEquals(listOf("r1", "r2", "r3"), merged.single().users.map { it.id })
        assertEquals(3, merged.single().count)
    }

    @Test
    fun emojiDoServidorEUnicodeNaoSeMisturam() {
        val custom = group(null, reaction("r1", at(10)), emojiId = "e1")
        val unicode = group("🔥", reaction("r2", at(9)))
        val merged = mergeReactors(listOf(custom), listOf(unicode, group(null, reaction("r3", at(8)), emojiId = "e1")))

        assertEquals(listOf("e1" to null, null to "🔥"), merged.map { it.emojiId to it.unicode })
        assertEquals(listOf("r1", "r3"), merged[0].users.map { it.id })
    }

    @Test
    fun juntarSemNadaDeUmLadoOuDoOutro() {
        val page = listOf(group("🔥", reaction("r1", at(10))))
        assertEquals(page, mergeReactors(emptyList(), page))
        assertEquals(page, mergeReactors(page, emptyList()))
        assertEquals(emptyList(), mergeReactors(emptyList(), emptyList()))
    }

    // Ordem das bolinhas

    @Test
    fun osGruposSeguemAOrdemDasBolinhasEAContagemEViva() {
        val summary = listOf(
            ReactionSummaryDto(unicode = "❤", count = 4),
            ReactionSummaryDto(emojiId = "e1", count = 9),
            ReactionSummaryDto(unicode = "🔥", count = 2),
        )
        val read = listOf(group("🔥", reaction("r1", at(10))), group(null, reaction("r2", at(9)), emojiId = "e1"), group("❤", reaction("r3", at(8))))

        val ordered = orderLike(summary, read)

        assertEquals(listOf(null to "❤", "e1" to null, null to "🔥"), ordered.map { it.emojiId to it.unicode })
        assertEquals(listOf(4, 9, 2), ordered.map { it.count })
        assertEquals(listOf("r3"), ordered[0].users.map { it.id })
    }

    @Test
    fun bolinhaSemNinguemLidoAindaApareceSemUsuarios() {
        val ordered = orderLike(listOf(ReactionSummaryDto(unicode = "🔥", count = 3)), emptyList())
        assertEquals(1, ordered.size)
        assertEquals(3, ordered.single().count)
        assertEquals(emptyList(), ordered.single().users)
    }

    @Test
    fun grupoLidoQueAMensagemJaNaoTemSome() {
        val ordered = orderLike(listOf(ReactionSummaryDto(unicode = "🔥", count = 1)), listOf(group("❤", reaction("r1", at(10)))))
        assertEquals(listOf("🔥"), ordered.map { it.unicode })
        assertEquals(emptyList(), ordered.single().users)
        assertEquals(emptyList(), orderLike(emptyList(), listOf(group("❤", reaction("r1", at(10))))))
    }
}
