package chat.papo.mobile.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import chat.papo.mobile.AppGraph
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.OpenConversation
import chat.papo.mobile.media.AudioTrack
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.wiped_body
import chat.papo.mobile.resources.wiped_title
import chat.papo.mobile.security.AppLock
import chat.papo.mobile.security.LockState
import chat.papo.mobile.ui.addserver.AddServerScreen
import chat.papo.mobile.ui.addserver.AddServerViewModel
import chat.papo.mobile.ui.call.CallBar
import chat.papo.mobile.ui.call.CallScreen
import chat.papo.mobile.ui.chat.ChannelListPane
import chat.papo.mobile.ui.chat.ChatScreen
import chat.papo.mobile.ui.chat.ChatViewModel
import chat.papo.mobile.ui.chat.MessageTarget
import chat.papo.mobile.ui.components.AudioBar
import chat.papo.mobile.ui.components.LocalAudioPlayback
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.PlatformUi
import chat.papo.mobile.ui.home.ConversationPanelHost
import chat.papo.mobile.ui.home.ConversationPanelState
import chat.papo.mobile.ui.home.HomeScreen
import chat.papo.mobile.ui.home.ParkedChat
import chat.papo.mobile.ui.home.ParkedChatStores
import chat.papo.mobile.ui.home.StartupInput
import chat.papo.mobile.ui.home.StartupOpening
import chat.papo.mobile.ui.home.rememberConversationPanelState
import chat.papo.mobile.ui.home.step
import chat.papo.mobile.ui.lock.LockScreen
import chat.papo.mobile.ui.members.MembersDrawer
import chat.papo.mobile.ui.profile.EditProfileScreen
import chat.papo.mobile.ui.profile.EditProfileViewModel
import chat.papo.mobile.ui.profile.ProfileScreen
import chat.papo.mobile.ui.profile.ProfileViewModel
import chat.papo.mobile.ui.search.SearchScreen
import chat.papo.mobile.ui.search.SearchViewModel
import chat.papo.mobile.ui.settings.AccountSettingsScreen
import chat.papo.mobile.ui.settings.AppSettingsScreen
import chat.papo.mobile.ui.settings.BlockedUsersScreen
import chat.papo.mobile.ui.settings.BlockedUsersViewModel
import chat.papo.mobile.ui.settings.ChangePasswordScreen
import chat.papo.mobile.ui.settings.ChangePasswordViewModel
import chat.papo.mobile.ui.switcher.QuickSwitcherScreen
import chat.papo.mobile.ui.switcher.QuickSwitcherViewModel
import chat.papo.mobile.ui.theme.PapoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

/** O deslize de uma tela que entra ou sai sozinha (um toque, o botão de voltar). */
private val ScreenSlide = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)

/** O deslize conduzido pelo gesto de voltar: linear, para a tela andar na mesma proporção do dedo. */
private val ScreenFollow = tween<IntOffset>(durationMillis = 300, easing = LinearEasing)

@Serializable
private data object HomeRoute

@Serializable
private data class AddServerRoute(val address: String? = null, val username: String? = null)

@Serializable
private data class AccountSettingsRoute(val accountId: String)

@Serializable
private data object AppSettingsRoute

@Serializable
private data object CallRoute

/** Busca na conta; [channelId] já abre com o filtro daquela conversa. */
@Serializable
private data class SearchRoute(val accountId: String, val channelId: String? = null)

/** Perfil de [userId], um usuário da conta [accountId]. */
@Serializable
private data class ProfileRoute(val accountId: String, val userId: String)

/** Os usuários que a conta [accountId] bloqueou. */
@Serializable
private data class BlockedRoute(val accountId: String)

/** A troca da senha da conta [accountId]. */
@Serializable
private data class PasswordRoute(val accountId: String)

/** Edição do próprio perfil da conta [accountId]. */
@Serializable
private data class EditProfileRoute(val accountId: String)

/** Troca rápida entre as conversas de todas as contas; [accountId] e [channelId] são a conversa de onde veio, se houve. */
@Serializable
private data class SwitcherRoute(val accountId: String? = null, val channelId: String? = null)

/** A conversa do áudio [track], aberta na mensagem dele. */
private fun parkedChatOf(track: AudioTrack) = ParkedChat(track.channelId, MessageTarget(track.messageId, track.messageAtIso))

@Composable
fun PapoApp(graph: AppGraph, platformUi: PlatformUi) {
    val state by graph.lock.state.collectAsStateWithLifecycle()
    val wiped by graph.lock.dataWasWiped.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { graph.lock.start() }
    // Fica fora da tela principal para sobreviver a girar o aparelho; é esvaziado ao travar.
    val chatStores = viewModel { ParkedChatStores() }

    CompositionLocalProvider(LocalPlatformUi provides platformUi) {
      PapoTheme {
        when (val current = state) {
            LockState.Starting -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
            is LockState.Locked -> {
                // Nada de uma conversa fica em memória com o app travado; ao destravar a lista aparece.
                LaunchedEffect(Unit) { chatStores.clear() }
                LockScreen(graph.lock, current)
            }
            is LockState.Unlocked -> {
                val session = current.session as AppSession
                CompositionLocalProvider(LocalAudioPlayback provides session.audio) { Unlocked(session, graph.lock, graph.appVersion, chatStores) }
            }
        }
        if (wiped) {
            AlertDialog(
                onDismissRequest = graph.lock::dismissWipedNotice,
                title = { Text(stringResource(Res.string.wiped_title)) },
                text = { Text(stringResource(Res.string.wiped_body)) },
                confirmButton = {
                    TextButton(onClick = graph.lock::dismissWipedNotice) { Text(stringResource(Res.string.action_close)) }
                },
            )
        }
      }
    }
}

@Composable
private fun Unlocked(session: AppSession, lock: AppLock, appVersion: String, chatStores: ParkedChatStores) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val loaded by session.accounts.loaded.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    // A conversa de cada conta e o painel dela ficam acima da navegação: o perfil ou a busca por cima da tela principal
    // não os perdem, e girar o aparelho os guarda.
    val panel = rememberConversationPanelState()

    if (!loaded) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    // Qualquer toque conta como atividade: é o que tira o usuário do "ausente" automático do servidor.
    val activityScope = rememberCoroutineScope()
    val activity = Modifier.pointerInput(session) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            activityScope.launch {
                session.accounts.sessionMap.value.values.forEach { runCatching { it.chat.notifyActivity() } }
            }
        }
    }

    // Mostra a conversa [chat] da conta [accountId] no painel da tela principal, saindo das telas que estavam por cima dela.
    // A conta da conversa passa a ser a ativa; ao fechar o painel se chega na lista dela.
    val openChat = { accountId: String, chat: ParkedChat ->
        session.accounts.select(accountId)
        navController.popBackStack(HomeRoute, inclusive = false)
        panel.open(accountId, chat)
    }
    OpenTopChatOnStart(session, panel, navController, openChat)
    ReopenChatOnReturn(session, panel, navController, openChat)

    // Uma tela nova entra deslizando da borda direita por cima da anterior, que fica parada embaixo, e sai pelo mesmo
    // caminho. No gesto de voltar ela acompanha o dedo; o padrão da biblioteca era encolher a tela para o centro.
    NavHost(
        navController,
        startDestination = HomeRoute,
        modifier = activity,
        enterTransition = { slideInHorizontally(ScreenSlide) { it } },
        exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { slideOutHorizontally(ScreenSlide) { it } },
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = { slideOutHorizontally(ScreenFollow) { it } },
    ) {
        composable<HomeRoute> {
            if (accounts.isEmpty()) {
                // Sem contas, a tela inicial é o próprio fluxo de entrar num servidor.
                val vm = viewModel(key = "first-server") { AddServerViewModel(session.accounts, null, null) }
                AddServerScreen(vm, canLeave = false, onLeave = {}, onDone = {})
            } else {
                val activeId by session.accounts.activeId.collectAsStateWithLifecycle()
                val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
                val chats = panel.chats
                // Conta que saiu leva a conversa; armazém de conversa que já não é a última de uma conta, o ViewModel dela.
                LaunchedEffect(sessions) { panel.retain(sessions.keys) }
                SideEffect { chatStores.retain(ParkedChatStores.liveKeys(chats, sessions)) }
                // A conta que a tela principal mostra é a mesma que ela escolhe; a conversa estacionada é a dela.
                val selected = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
                val selectedSession = selected?.let { sessions[it.id] }
                val parked = selected?.let { chats[it.id] }?.takeIf { selectedSession != null }
                ConversationPanelHost(
                    panel = panel,
                    parked = parked,
                    home = {
                        HomeScreen(
                            session = session,
                            onAddServer = { navController.navigate(AddServerRoute()) },
                            onReauthenticate = { navController.navigate(AddServerRoute(it.server.value, it.username)) },
                            onAccountSettings = { navController.navigate(AccountSettingsRoute(it.id)) },
                            onSearch = { navController.navigate(SearchRoute(it.id)) },
                            onSwitch = { navController.navigate(SwitcherRoute()) },
                            onAppSettings = { navController.navigate(AppSettingsRoute) },
                            content = { account ->
                                sessions[account.id]?.let { accountSession ->
                                    Column {
                                        CallBar(session) { navController.navigate(CallRoute) }
                                        AudioBar(onOpen = { openChat(it.accountId, parkedChatOf(it)) })
                                        ChannelListPane(
                                            session = accountSession,
                                            onOpen = { channelId -> panel.open(account.id, ParkedChat(channelId)) },
                                            onJoinVoice = { channelId ->
                                                session.calls.join(accountSession, channelId)
                                                navController.navigate(CallRoute)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    },
                    chat = { chat, active ->
                        if (selected != null && selectedSession != null) {
                            ParkedChatContent(session, selected.id, selectedSession, chat, active, chatStores, panel, navController, openChat)
                        }
                    },
                )
            }
        }
        composable<AddServerRoute> { entry ->
            val route = entry.toRoute<AddServerRoute>()
            val vm = viewModel { AddServerViewModel(session.accounts, route.address, route.username) }
            AddServerScreen(
                viewModel = vm,
                canLeave = true,
                onLeave = { navController.popBackStack() },
                onDone = { navController.popBackStack(HomeRoute, inclusive = false) },
            )
        }
        composable<ProfileRoute> { entry ->
            val route = entry.toRoute<ProfileRoute>()
            val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
            val accountSession = sessions[route.accountId]
            if (accountSession == null) {
                LaunchedEffect(Unit) { navController.popBackStack(HomeRoute, inclusive = false) }
            } else {
                // A sessão entra na chave: o perfil lido por uma sessão nunca aparece em outra.
                val vm = viewModel(key = "profile/${route.accountId}/${route.userId}/${accountSession.hashCode()}") {
                    ProfileViewModel(accountSession, route.userId)
                }
                ProfileScreen(
                    session = accountSession,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    // Veio desta conversa (o título da conversa direta leva ao perfil) ou da lista: ela abre no painel.
                    onOpenChat = { channelId -> openChat(route.accountId, ParkedChat(channelId)) },
                    onEdit = { navController.navigate(EditProfileRoute(route.accountId)) },
                )
            }
        }
        composable<EditProfileRoute> { entry ->
            val route = entry.toRoute<EditProfileRoute>()
            val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
            val accountSession = sessions[route.accountId]
            if (accountSession == null) {
                LaunchedEffect(Unit) { navController.popBackStack(HomeRoute, inclusive = false) }
            } else {
                val reducer = LocalPlatformUi.current.imageReducer
                // A sessão entra na chave: o que se edita numa sessão nunca vai para outra.
                val vm = viewModel(key = "edit-profile/${route.accountId}/${accountSession.hashCode()}") {
                    EditProfileViewModel(accountSession, reducer)
                }
                EditProfileScreen(accountSession, vm, onBack = { navController.popBackStack() })
            }
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
            val accountSession = sessions[route.accountId]
            if (accountSession == null) {
                LaunchedEffect(Unit) { navController.popBackStack(HomeRoute, inclusive = false) }
            } else {
                // A sessão entra na chave: os resultados de uma sessão nunca aparecem em outra.
                val vm = viewModel(key = "search/${route.accountId}/${route.channelId}/${accountSession.hashCode()}") {
                    SearchViewModel(accountSession, route.channelId)
                }
                SearchScreen(
                    session = accountSession,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpen = { hit -> openChat(route.accountId, ParkedChat(hit.channelId, MessageTarget(hit.id, hit.createdAtIso))) },
                )
            }
        }
        composable<CallRoute> {
            CallScreen(
                session,
                // Volta a quem abriu a chamada: a lista de conversas ou o chat do canal de voz.
                onBack = { navController.popBackStack(CallRoute, inclusive = true) },
                onOpenChat = { accountId, channelId -> openChat(accountId, ParkedChat(channelId)) },
            )
        }
        composable<AccountSettingsRoute> { entry ->
            val accountId = entry.toRoute<AccountSettingsRoute>().accountId
            AccountSettingsScreen(
                session,
                accountId,
                onBack = { navController.popBackStack() },
                onOpenBlocked = { navController.navigate(BlockedRoute(accountId)) },
                onOpenPassword = { navController.navigate(PasswordRoute(accountId)) },
                onEditProfile = { navController.navigate(EditProfileRoute(accountId)) },
            )
        }
        composable<PasswordRoute> { entry ->
            val route = entry.toRoute<PasswordRoute>()
            val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
            val accountSession = sessions[route.accountId]
            if (accountSession == null) {
                LaunchedEffect(Unit) { navController.popBackStack(HomeRoute, inclusive = false) }
            } else {
                // A sessão entra na chave: nada de uma troca de senha passa para outra conta.
                val vm = viewModel(key = "password/${route.accountId}/${accountSession.hashCode()}") {
                    ChangePasswordViewModel(accountSession, endSession = { session.accounts.endSession(route.accountId) })
                }
                ChangePasswordScreen(
                    vm,
                    onBack = { navController.popBackStack() },
                    // A sessão acabou: a tela principal mostra a conta como expirada e leva ao "Entrar de novo".
                    onDone = { navController.popBackStack(HomeRoute, inclusive = false) },
                )
            }
        }
        composable<BlockedRoute> { entry ->
            val route = entry.toRoute<BlockedRoute>()
            val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
            val accountSession = sessions[route.accountId]
            if (accountSession == null) {
                LaunchedEffect(Unit) { navController.popBackStack(HomeRoute, inclusive = false) }
            } else {
                // A sessão entra na chave: a lista de uma conta nunca aparece em outra.
                val vm = viewModel(key = "blocked/${route.accountId}/${accountSession.hashCode()}") { BlockedUsersViewModel(accountSession.blocks) }
                BlockedUsersScreen(accountSession, vm, onBack = { navController.popBackStack() })
            }
        }
        composable<AppSettingsRoute> {
            AppSettingsScreen(lock, appVersion, onBack = { navController.popBackStack() })
        }
        composable<SwitcherRoute> { entry ->
            val route = entry.toRoute<SwitcherRoute>()
            val open = if (route.accountId != null && route.channelId != null) OpenConversation(route.accountId, route.channelId) else null
            val vm = viewModel(key = "switcher/${route.accountId}/${route.channelId}") { QuickSwitcherViewModel(session.accounts, open) }
            QuickSwitcherScreen(
                viewModel = vm,
                accounts = accounts,
                onBack = { navController.popBackStack() },
                onOpen = { target -> openChat(target.accountId, ParkedChat(target.channelId)) },
            )
        }
    }
}

/** Os dois valores de uma conta sem sessão para a abertura automática: nenhum canal, e nada sincronizado. */
private val NoChannels: StateFlow<List<Channel>> = MutableStateFlow(emptyList())
private val NotSynced: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * Ao abrir o app, ou destravá-lo, abre no painel o canal de texto mais ao topo da conta selecionada, pelo mesmo [openChat]
 * dos outros pedidos de abrir conversa. A decisão (qual canal, quando, uma vez só) é de [StartupOpening]; aqui só se juntam
 * as entradas dela. Resolvida a abertura, não observa mais nada.
 *
 * A conta é a `activeId`, que depois de carregar é a de uma conta gravada ou nula: a lista de contas pode chegar um instante
 * depois, e uma conta ainda sem sessão só espera.
 */
@Composable
private fun OpenTopChatOnStart(
    session: AppSession,
    panel: ConversationPanelState,
    navController: NavHostController,
    openChat: (accountId: String, chat: ParkedChat) -> Unit,
) {
    if (panel.startup == StartupOpening.Done) return
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val activeId by session.accounts.activeId.collectAsStateWithLifecycle()
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    // Antes de a navegação montar o grafo não se sabe que tela está por cima: espera o próximo quadro.
    if (entry == null) return
    // A mesma conta que a tela principal mostra: a selecionada ou, sem seleção válida, a primeira.
    val shownId = (accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull())?.id
    val chat = shownId?.let { sessions[it] }?.chat
    val channels by (chat?.visibleChannels ?: NoChannels).collectAsStateWithLifecycle()
    // Os canais do servidor bastam: esperar todos os usuários (várias páginas num servidor grande) atrasaria a abertura, e
    // uma falha no meio deles a impediria.
    val synced by (chat?.channelsSynced ?: NotSynced).collectAsStateWithLifecycle()

    val step = panel.startup.step(
        StartupInput(
            accountId = shownId,
            sessionExpired = accounts.firstOrNull { it.id == shownId }?.sessionExpired == true,
            hasParkedChat = shownId?.let { panel.chats[it] } != null,
            // A tela principal é a raiz da navegação: sem tela anterior a ela, nada está por cima.
            onHome = navController.previousBackStackEntry == null,
            synced = synced,
            channels = channels,
        ),
    )
    LaunchedEffect(step) {
        panel.startup = step.next
        val channelId = step.open ?: return@LaunchedEffect
        shownId?.let { openChat(it, ParkedChat(channelId)) }
    }
}

/**
 * Voltar ao app (do segundo plano, de outro app, da tela apagada) com a lista de canais à vista é abrir o app de novo: a
 * conversa da conta volta ao painel — a que estava estacionada nele, ou, sem ela, o canal de texto do topo (pela mesma
 * abertura de [OpenTopChatOnStart]). Quem volta numa conversa, ou noutra tela (busca, configurações, chamada), fica onde
 * estava. Girar o aparelho recria a composição sem passar por aqui: só conta a volta depois de uma parada vista por ela.
 */
@Composable
private fun ReopenChatOnReturn(
    session: AppSession,
    panel: ConversationPanelState,
    navController: NavHostController,
    openChat: (accountId: String, chat: ParkedChat) -> Unit,
) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val activeId by session.accounts.activeId.collectAsStateWithLifecycle()
    val shownId = (accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull())?.id
    // O efeito vive a composição inteira: lê a conta e o `openChat` de agora, não os do primeiro quadro (quando as contas
    // ainda carregavam e a conta mostrada podia ser outra, ou nenhuma).
    val currentShownId by rememberUpdatedState(shownId)
    val currentOpenChat by rememberUpdatedState(openChat)
    var stopped by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        if (stopped) {
            stopped = false
            val onHome = navController.previousBackStackEntry == null
            val account = currentShownId
            if (onHome && panel.isClosed && account != null) {
                val parked = panel.chats[account]
                if (parked != null) currentOpenChat(account, parked) else panel.startup = StartupOpening.Fresh
            }
        }
        onStopOrDispose { stopped = true }
    }
}

/**
 * A conversa do painel: o ViewModel dela, a tela e a gaveta de membros por cima. Uma conversa por vez, e cada uma na
 * própria composição ([key]): o que uma tem (a rolagem, o campo, a mídia aberta) não passa à seguinte.
 */
@Composable
private fun ParkedChatContent(
    session: AppSession,
    accountId: String,
    accountSession: AccountSession,
    chat: ParkedChat,
    active: Boolean,
    stores: ParkedChatStores,
    panel: ConversationPanelState,
    navController: NavHostController,
    openChat: (accountId: String, chat: ParkedChat) -> Unit,
) {
    val storeKey = ParkedChatStores.keyOf(accountId, chat, accountSession)
    key(storeKey) {
        // O ViewModel mora no armazém da conversa, que sobrevive a girar o aparelho e sai quando ela deixa de ser a última da conta.
        val owner = remember(stores, storeKey) { stores.owner(storeKey) }
        val vm = viewModel(viewModelStoreOwner = owner, key = storeKey) { ChatViewModel(accountSession, chat.channelId, chat.target) }
        val openProfile = { userId: String ->
            navController.navigate(ProfileRoute(accountId, userId)) { launchSingleTop = true }
        }
        // A gaveta fica dentro da conversa: o voltar do sistema a fecha antes de fechar o painel.
        val members = rememberDrawerState(DrawerValue.Closed)
        val membersScope = rememberCoroutineScope()
        MembersDrawer(session = accountSession, state = members, onOpenProfile = openProfile) {
            ChatScreen(
                session = accountSession,
                viewModel = vm,
                onBack = panel::close,
                onSearch = { navController.navigate(SearchRoute(accountId, chat.channelId)) },
                onSwitch = { navController.navigate(SwitcherRoute(accountId, chat.channelId)) },
                onOpenAudio = { track ->
                    // O áudio é desta mesma conversa: a lista rola até a mensagem, sem abrir outra cópia da conversa.
                    if (track.accountId == accountId && track.channelId == chat.channelId) {
                        vm.showMessage(MessageTarget(track.messageId, track.messageAtIso))
                    } else {
                        openChat(track.accountId, parkedChatOf(track))
                    }
                },
                onJoinCall = if (session.calls.supported) {
                    {
                        session.calls.join(accountSession, chat.channelId)
                        navController.navigate(CallRoute) { launchSingleTop = true }
                    }
                } else {
                    null
                },
                onOpenProfile = openProfile,
                onOpenMembers = { membersScope.launch { members.open() } },
                active = active,
            )
        }
    }
}
