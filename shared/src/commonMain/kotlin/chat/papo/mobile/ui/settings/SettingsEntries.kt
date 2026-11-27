package chat.papo.mobile.ui.settings

/** Os itens da aba Aplicativo, na ordem em que aparecem. */
enum class AppEntry { Appearance, Security, AndroidNotifications, DeviceData, About }

/** Os itens da aba Servidor, na ordem em que aparecem. */
enum class ServerEntry { Profile, AccountLocal, ServerTheme, Notifications, Blocked, Password, Devices, ServerInfo, SignOut }

/** A tela que vale: a do servidor só com uma conta (a pedida pode ter saído); senão a do app. */
fun settingsPage(requested: SettingsPage, accountId: String?): SettingsPage =
    if (requested == SettingsPage.Server && accountId != null) SettingsPage.Server else SettingsPage.App

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
