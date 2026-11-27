package chat.papo.mobile

import app.cash.sqldelight.db.SqlDriver
import chat.papo.mobile.account.ServerTheme
import chat.papo.mobile.account.ServerThemeStore
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.account.MetaQueries
import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.ui.theme.AppThemeStore
import chat.papo.mobile.ui.theme.ThemeMode
import chat.papo.mobile.ui.theme.ThemeSpec
import chat.papo.mobile.ui.theme.ThemeToken
import chat.papo.mobile.ui.theme.ThemeVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeStoresTest {
    private class Prefs(val map: MutableMap<String, String> = mutableMapOf()) : AppPrefs {
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val ocean = ThemeSpec(ThemeMode.Oled, "ocean").withOverride(ThemeVariant.Oled, ThemeToken.Accent, 0xFFFF0000.toInt())
    private val amber = ThemeSpec(ThemeMode.Light, "amber")

    private class Db(val driver: SqlDriver = TestResources.driver()) {
        val meta: MetaQueries = AccountDb(driver).metaQueries
        fun stored(): String? = meta.get(ServerThemeStore.KEY).executeAsOneOrNull()
    }

    private fun TestScope.store(db: Db) = ServerThemeStore(db.meta, backgroundScope, StandardTestDispatcher(testScheduler))

    // ---- tema global (preferências do app) ----

    @Test
    fun globalSemPreferenciaComecaNoPadrao() {
        assertEquals(ThemeSpec(), AppThemeStore(Prefs()).spec.value)
    }

    @Test
    fun globalGravaNasPreferenciasELogoAdiante() {
        val prefs = Prefs()
        val store = AppThemeStore(prefs)
        store.set(ocean)

        assertEquals(ocean, store.spec.value)
        assertEquals(ocean.encode(), prefs.map["ui.theme"])
        // Um processo novo (antes de qualquer desbloqueio) já lê o mesmo tema.
        assertEquals(ocean, AppThemeStore(prefs).spec.value)
    }

    @Test
    fun globalEstragadoNasPreferenciasCaiNoPadrao() {
        assertEquals(ThemeSpec(), AppThemeStore(Prefs(mutableMapOf("ui.theme" to "{ não é json"))).spec.value)
    }

    // ---- tema do servidor (meta do banco da conta) ----

    @Test
    fun semLinhaOServidorUsaOTemaDoApp() = runTest {
        val store = store(Db())
        assertNull(store.state.value) // ainda lendo
        runCurrent()

        assertEquals(ServerTheme.UseApp, store.state.value)
    }

    @Test
    fun temaProprioVaiParaOMetaECarregaNaProximaSessao() = runTest {
        val db = Db()
        val store = store(db)
        runCurrent()
        store.set(ServerTheme.Own(ocean))

        assertEquals(ServerTheme.Own(ocean), store.state.value) // vale na tela antes de gravar
        runCurrent()
        assertEquals(ocean.encode(), db.stored())

        val reopened = store(db)
        runCurrent()
        assertEquals(ServerTheme.Own(ocean), reopened.state.value)
    }

    @Test
    fun voltarParaOTemaDoAppApagaALinha() = runTest {
        val db = Db()
        val store = store(db)
        runCurrent()
        store.set(ServerTheme.Own(ocean))
        runCurrent()
        store.set(ServerTheme.UseApp)
        runCurrent()

        assertNull(db.stored())
        assertEquals(ServerTheme.UseApp, store.state.value)
    }

    @Test
    fun mudancasEmSequenciaGravamASuaUltima() = runTest {
        val db = Db()
        val store = store(db)
        runCurrent()
        store.set(ServerTheme.Own(ocean))
        store.set(ServerTheme.Own(amber))
        store.set(ServerTheme.Own(ocean))
        runCurrent()

        assertEquals(ocean.encode(), db.stored())
    }

    @Test
    fun linhaEstragadaVoltaParaOTemaDoApp() = runTest {
        val db = Db()
        db.meta.put(ServerThemeStore.KEY, "{ não é json")
        val store = store(db)
        runCurrent()

        assertEquals(ServerTheme.UseApp, store.state.value)
    }

    @Test
    fun trocarAntesDaLeituraNaoFazNada() = runTest {
        val db = Db()
        db.meta.put(ServerThemeStore.KEY, amber.encode())
        val store = store(db)
        store.set(ServerTheme.Own(ocean)) // a leitura ainda não terminou
        runCurrent()

        assertEquals(ServerTheme.Own(amber), store.state.value)
        assertEquals(amber.encode(), db.stored())
    }

    @Test
    fun bancoQueFalhaNaLeituraNaoDeixaAContaSemTela() = runTest {
        val db = Db()
        db.driver.close()
        assertFails { db.stored() } // confirma que a leitura de fato falha
        val store = store(db)
        runCurrent()

        assertEquals(ServerTheme.UseApp, store.state.value)
    }

    @Test
    fun bancoQueFalhaNaGravacaoMantemAEscolhaNaSessao() = runTest {
        val db = Db()
        val store = store(db)
        runCurrent()
        db.driver.close()
        assertFails { db.meta.put(ServerThemeStore.KEY, "x") } // confirma que a gravação de fato falha
        store.set(ServerTheme.Own(ocean))
        runCurrent() // a falha não derruba o escopo da sessão

        assertEquals(ServerTheme.Own(ocean), store.state.value)
    }

    @Test
    fun cancelarOEscopoNaLeituraNaoDefineNada() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val store = ServerThemeStore(Db().meta, scope, StandardTestDispatcher(testScheduler))
        scope.cancel()
        runCurrent()

        assertNull(store.state.value) // o cancelamento é relançado, não vira "usar o tema do app"
    }

    @Test
    fun contasDiferentesTemTemasDiferentes() = runTest {
        val first = Db()
        val second = Db()
        val third = Db()
        val a = store(first)
        val b = store(second)
        val c = store(third)
        runCurrent()
        a.set(ServerTheme.Own(ocean))
        b.set(ServerTheme.Own(amber))
        runCurrent()

        assertEquals(ServerTheme.Own(ocean), a.state.value)
        assertEquals(ServerTheme.Own(amber), b.state.value)
        assertEquals(ServerTheme.UseApp, c.state.value)
        assertEquals(ocean.encode(), first.stored())
        assertEquals(amber.encode(), second.stored())
        assertNull(third.stored())
    }

    @Test
    fun aSessaoDaContaTemOProprioTema() = runTest {
        val session = TestResources.session(FakeBackend().engine, this)
        runCurrent()
        assertEquals(ServerTheme.UseApp, session.theme.state.value)

        session.theme.set(ServerTheme.Own(amber))
        runCurrent()
        assertIs<ServerTheme.Own>(session.theme.state.value)
    }

    @Test
    fun decodeDaLinha() {
        assertEquals(ServerTheme.UseApp, ServerThemeStore.decode(null))
        assertEquals(ServerTheme.UseApp, ServerThemeStore.decode(""))
        assertEquals(ServerTheme.UseApp, ServerThemeStore.decode("[]"))
        assertEquals(ServerTheme.Own(ocean), ServerThemeStore.decode(ocean.encode()))
    }
}
