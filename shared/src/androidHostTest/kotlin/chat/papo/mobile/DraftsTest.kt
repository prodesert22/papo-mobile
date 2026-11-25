package chat.papo.mobile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.chat.Draft
import chat.papo.mobile.chat.Drafts
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DraftsTest {
    private val reply = "0b0e7c1e-1111-2222-3333-444455556666"

    private fun TestScope.drafts(meta: MetaQueries) = Drafts(meta, backgroundScope, StandardTestDispatcher(testScheduler))

    private fun meta() = AccountDb(TestResources.driver()).metaQueries

    private fun MetaQueries.stored(channelId: String): Draft? = Drafts.decode(get(Drafts.key(channelId)).executeAsOneOrNull())

    @Test
    fun cadaConversaTemOSeuRascunho() = runTest {
        val meta = meta()
        val drafts = drafts(meta)
        drafts.save("c1", Draft("um", reply))
        drafts.save("c2", Draft("dois"))
        advanceTimeBy(Drafts.DEBOUNCE)
        runCurrent()

        assertEquals(Draft("um", reply), drafts.load("c1"))
        assertEquals(Draft("dois"), drafts.load("c2"))
        assertNull(drafts.load("c3"))
        assertEquals(Draft("um", reply), meta.stored("c1")) // e foi para o banco, na chave da conversa
        assertEquals(Draft("dois"), meta.stored("c2"))
    }

    @Test
    fun soGravaDepoisDoAtrasoSemNovasMudancas() = runTest {
        val meta = meta()
        val drafts = drafts(meta)
        drafts.save("c1", Draft("a"))
        advanceTimeBy(Drafts.DEBOUNCE - 1.milliseconds)
        runCurrent()
        assertNull(meta.stored("c1"))

        // Outra letra recomeça a espera: ainda não gravou o "a" nem o "ab".
        drafts.save("c1", Draft("ab"))
        advanceTimeBy(Drafts.DEBOUNCE - 1.milliseconds)
        runCurrent()
        assertNull(meta.stored("c1"))

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(Draft("ab"), meta.stored("c1"))
    }

    @Test
    fun flushGravaNaHoraSemEsperarOAtraso() = runTest {
        val meta = meta()
        val drafts = drafts(meta)
        drafts.save("c1", Draft("a pressa"))
        drafts.flush()
        runCurrent()

        assertEquals(Draft("a pressa"), meta.stored("c1"))
        // Depois do atraso nada muda: a gravação que esperava já não tem o que fazer.
        advanceTimeBy(Drafts.DEBOUNCE * 2)
        runCurrent()
        assertEquals(Draft("a pressa"), meta.stored("c1"))
    }

    @Test
    fun flushSemNadaEsperandoNaoFazNada() = runTest {
        val meta = meta()
        drafts(meta).flush()
        runCurrent()
        assertNull(meta.stored("c1"))
    }

    @Test
    fun rascunhoVazioApagaAChave() = runTest {
        val meta = meta()
        val drafts = drafts(meta)
        drafts.save("c1", Draft("texto"))
        drafts.flush()
        runCurrent()
        assertEquals(Draft("texto"), meta.stored("c1"))

        drafts.save("c1", Draft("   \n"))
        drafts.flush()
        runCurrent()
        assertNull(meta.get(Drafts.key("c1")).executeAsOneOrNull())

        drafts.save("c1", Draft("de novo"))
        drafts.save("c1", null)
        drafts.flush()
        runCurrent()
        assertNull(meta.get(Drafts.key("c1")).executeAsOneOrNull())
        assertNull(drafts.load("c1"))
    }

    @Test
    fun soComRespostaAindaEUmRascunho() = runTest {
        val meta = meta()
        val drafts = drafts(meta)
        drafts.save("c1", Draft("", reply))
        drafts.flush()
        runCurrent()
        assertEquals(Draft("", reply), meta.stored("c1"))
    }

    @Test
    fun loadVeOQueAindaEsperaParaSerGravado() = runTest {
        val meta = meta()
        val drafts = drafts(meta)
        drafts.save("c1", Draft("velho"))
        drafts.flush()
        runCurrent()

        drafts.save("c1", Draft("novo"))
        assertEquals(Draft("novo"), drafts.load("c1")) // o banco ainda tem "velho"
        assertEquals(Draft("velho"), meta.stored("c1"))

        drafts.save("c1", null)
        assertNull(drafts.load("c1")) // apagar também vale antes de chegar ao banco
    }

    @Test
    fun sobreviveAoReinicioDaConta() = runTest {
        val meta = meta()
        val first = drafts(meta)
        first.save("c1", Draft("guardado", reply))
        first.flush()
        runCurrent()

        assertEquals(Draft("guardado", reply), drafts(meta).load("c1"))
    }

    @Test
    fun valorEstragadoNoBancoEIgnorado() = runTest {
        val meta = meta()
        meta.put(Drafts.key("c1"), "isto não é json")
        assertNull(drafts(meta).load("c1"))
        meta.put(Drafts.key("c1"), """{"text":"${"a".repeat(Drafts.MAX_TEXT + 1)}"}""")
        assertNull(drafts(meta).load("c1"))
    }

    @Test
    fun doisBancosDeContaFicamIsolados() = runTest {
        val accountA = meta()
        val accountB = meta()
        val a = drafts(accountA)
        val b = drafts(accountB)

        // O mesmo id de conversa em duas contas é outra conversa.
        a.save("c1", Draft("da conta A"))
        a.flush()
        runCurrent()

        assertEquals(Draft("da conta A"), a.load("c1"))
        assertNull(b.load("c1"))
        assertNull(accountB.get(Drafts.key("c1")).executeAsOneOrNull())

        b.save("c1", Draft("da conta B"))
        b.flush()
        runCurrent()
        assertEquals(Draft("da conta A"), accountA.stored("c1"))
        assertEquals(Draft("da conta B"), accountB.stored("c1"))
    }

    @Test
    fun falhaAoGravarNaoPerdeORascunhoNemDerrubaOApp() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AccountDb.Schema.create(it) }
        val meta = AccountDb(driver).metaQueries
        val drafts = drafts(meta)
        driver.close() // o banco some: a gravação falha
        assertFails { meta.put(Drafts.key("c1"), "x") }

        drafts.save("c1", Draft("não se perde"))
        drafts.flush()
        runCurrent()
        advanceTimeBy(Drafts.DEBOUNCE)
        runCurrent()

        // Sem exceção solta (o teste acabaria com ela) e o valor segue esperando, visível para quem abre a conversa.
        assertEquals(Draft("não se perde"), drafts.load("c1"))
    }
}
