package chat.papo.mobile.ui.settings

/** Os itens da aba Aplicativo, na ordem em que aparecem. */
enum class AppEntry { Appearance, Security, AndroidNotifications, DeviceData, About }

/** Os itens da aba Servidor, na ordem em que aparecem. */
enum class ServerEntry { Profile, AccountLocal, ServerTheme, Notifications, Blocked, Password, Devices, ServerInfo, SignOut }

/** As abas que a tela mostra: sem conta só há a do aplicativo. */
fun settingsTabs(accountId: String?): List<SettingsTab> =
    if (accountId == null) listOf(SettingsTab.App) else listOf(SettingsTab.App, SettingsTab.Server)

/** A aba que vale: a escolhida, ou a do aplicativo quando a escolhida deixou de existir (a conta saiu). */
fun visibleTab(selected: SettingsTab, accountId: String?): SettingsTab =
    if (selected in settingsTabs(accountId)) selected else SettingsTab.App

/** [systemSettings]: a plataforma sabe abrir as configurações de notificação do sistema; sem isso o item não faz sentido. */
fun appEntries(systemSettings: Boolean): List<AppEntry> = buildList {
    add(AppEntry.Appearance)
    add(AppEntry.Security)
    if (systemSettings) add(AppEntry.AndroidNotifications)
    add(AppEntry.DeviceData)
    add(AppEntry.About)
}

/**
 * Os itens da conta. Perfil, tema do servidor e notificações vivem no banco da sessão: sem [hasSession] (conta expirada)
 * não há o que mostrar. Bloqueados, senha e aparelhos são chamadas ao servidor: com a sessão [expired] não valem.
 */
fun serverEntries(hasSession: Boolean, expired: Boolean): List<ServerEntry> = buildList {
    if (hasSession) add(ServerEntry.Profile)
    add(ServerEntry.AccountLocal)
    if (hasSession) add(ServerEntry.ServerTheme)
    if (hasSession) add(ServerEntry.Notifications)
    if (!expired) {
        add(ServerEntry.Blocked)
        add(ServerEntry.Password)
        add(ServerEntry.Devices)
    }
    add(ServerEntry.ServerInfo)
    add(ServerEntry.SignOut)
}
