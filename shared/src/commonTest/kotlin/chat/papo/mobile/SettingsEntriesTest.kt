package chat.papo.mobile

import chat.papo.mobile.ui.settings.AppEntry
import chat.papo.mobile.ui.settings.ServerEntry
import chat.papo.mobile.ui.settings.SettingsTab
import chat.papo.mobile.ui.settings.appEntries
import chat.papo.mobile.ui.settings.serverEntries
import chat.papo.mobile.ui.settings.settingsTabs
import chat.papo.mobile.ui.settings.visibleTab
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsEntriesTest {
    @Test
    fun semContaSoHaAAbaDoAplicativo() {
        assertEquals(listOf(SettingsTab.App), settingsTabs(null))
    }

    @Test
    fun comContaHaAsDuasAbas() {
        assertEquals(listOf(SettingsTab.App, SettingsTab.Server), settingsTabs("conta-1"))
    }

    @Test
    fun aAbaServidorSemContaVoltaParaAAbaDoAplicativo() {
        assertEquals(SettingsTab.App, visibleTab(SettingsTab.Server, null))
    }

    @Test
    fun aAbaEscolhidaValeQuandoExiste() {
        assertEquals(SettingsTab.Server, visibleTab(SettingsTab.Server, "conta-1"))
        assertEquals(SettingsTab.App, visibleTab(SettingsTab.App, "conta-1"))
        assertEquals(SettingsTab.App, visibleTab(SettingsTab.App, null))
    }

    @Test
    fun aAbaDoAplicativoTemTodosOsItensQuandoOSistemaAbreAsConfiguracoes() {
        assertEquals(
            listOf(AppEntry.Appearance, AppEntry.Security, AppEntry.AndroidNotifications, AppEntry.DeviceData, AppEntry.About),
            appEntries(systemSettings = true),
        )
    }

    @Test
    fun aAbaDoAplicativoNaoTemNotificacoesDoAndroidSemSuporteDaPlataforma() {
        assertEquals(
            listOf(AppEntry.Appearance, AppEntry.Security, AppEntry.DeviceData, AppEntry.About),
            appEntries(systemSettings = false),
        )
    }

    @Test
    fun aAbaServidorDeUmaContaComSessaoTemTodosOsItensEMostraOSairPorUltimo() {
        assertEquals(
            listOf(
                ServerEntry.Profile,
                ServerEntry.AccountLocal,
                ServerEntry.ServerTheme,
                ServerEntry.Notifications,
                ServerEntry.Blocked,
                ServerEntry.Password,
                ServerEntry.Devices,
                ServerEntry.ServerInfo,
                ServerEntry.SignOut,
            ),
            serverEntries(hasSession = true, expired = false),
        )
    }

    @Test
    fun contaExpiradaSoTemOQueNaoPedeServidorNemBancoDaSessao() {
        assertEquals(
            listOf(ServerEntry.AccountLocal, ServerEntry.ServerInfo, ServerEntry.SignOut),
            serverEntries(hasSession = false, expired = true),
        )
    }

    @Test
    fun semSessaoAberta_mesmoSemExpirarNaoHaPerfilTemaNemNotificacoes() {
        assertEquals(
            listOf(
                ServerEntry.AccountLocal,
                ServerEntry.Blocked,
                ServerEntry.Password,
                ServerEntry.Devices,
                ServerEntry.ServerInfo,
                ServerEntry.SignOut,
            ),
            serverEntries(hasSession = false, expired = false),
        )
    }

    @Test
    fun comSessaoAbertaEExpiradaFicamPerfilTemaENotificacoesMasNaoAsChamadasAoServidor() {
        assertEquals(
            listOf(
                ServerEntry.Profile,
                ServerEntry.AccountLocal,
                ServerEntry.ServerTheme,
                ServerEntry.Notifications,
                ServerEntry.ServerInfo,
                ServerEntry.SignOut,
            ),
            serverEntries(hasSession = true, expired = true),
        )
    }
}
