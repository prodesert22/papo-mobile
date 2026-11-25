package chat.papo.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import chat.papo.mobile.AppGraph
import chat.papo.mobile.AppSession
import chat.papo.mobile.chat.OpenConversation
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
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.PlatformUi
import chat.papo.mobile.ui.home.HomeScreen
import chat.papo.mobile.ui.lock.LockScreen
import chat.papo.mobile.ui.search.SearchScreen
import chat.papo.mobile.ui.search.SearchViewModel
import chat.papo.mobile.ui.settings.AccountSettingsScreen
import chat.papo.mobile.ui.settings.AppSettingsScreen
import chat.papo.mobile.ui.switcher.QuickSwitcherScreen
import chat.papo.mobile.ui.switcher.QuickSwitcherViewModel
import chat.papo.mobile.ui.theme.PapoTheme
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

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

/** [messageId] e [messageAt] pedem para abrir a conversa numa mensagem, como um resultado de busca. */
@Serializable
private data class ChatRoute(
    val accountId: String,
    val channelId: String,
    val messageId: String? = null,
    val messageAt: String? = null,
)

/** Busca na conta; [channelId] já abre com o filtro daquela conversa. */
@Serializable
private data class SearchRoute(val accountId: String, val channelId: String? = null)

/** Troca rápida entre as conversas de todas as contas; [accountId] e [channelId] são a conversa de onde veio, se houve. */
@Serializable
private data class SwitcherRoute(val accountId: String? = null, val channelId: String? = null)

@Composable
fun PapoApp(graph: AppGraph, platformUi: PlatformUi) {
    val state by graph.lock.state.collectAsStateWithLifecycle()
    val wiped by graph.lock.dataWasWiped.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { graph.lock.start() }

    CompositionLocalProvider(LocalPlatformUi provides platformUi) {
      PapoTheme {
        when (val current = state) {
            LockState.Starting -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
            is LockState.Locked -> LockScreen(graph.lock, current)
            is LockState.Unlocked -> Unlocked(current.session as AppSession, graph.lock, graph.appVersion)
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
private fun Unlocked(session: AppSession, lock: AppLock, appVersion: String) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val loaded by session.accounts.loaded.collectAsStateWithLifecycle()
    val navController = rememberNavController()

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

    NavHost(navController, startDestination = HomeRoute, modifier = activity) {
        composable<HomeRoute> {
            if (accounts.isEmpty()) {
                // Sem contas, a tela inicial é o próprio fluxo de entrar num servidor.
                val vm = viewModel(key = "first-server") { AddServerViewModel(session.accounts, null, null) }
                AddServerScreen(vm, canLeave = false, onLeave = {}, onDone = {})
            } else {
                HomeScreen(
                    session = session,
                    onAddServer = { navController.navigate(AddServerRoute()) },
                    onReauthenticate = { navController.navigate(AddServerRoute(it.server.value, it.username)) },
                    onAccountSettings = { navController.navigate(AccountSettingsRoute(it.id)) },
                    onSearch = { navController.navigate(SearchRoute(it.id)) },
                    onSwitch = { navController.navigate(SwitcherRoute()) },
                    onAppSettings = { navController.navigate(AppSettingsRoute) },
                    content = { account ->
                        val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
                        sessions[account.id]?.let { accountSession ->
                            Column {
                                CallBar(session) { navController.navigate(CallRoute) }
                                ChannelListPane(
                                    session = accountSession,
                                    onOpen = { channelId -> navController.navigate(ChatRoute(account.id, channelId)) },
                                    onJoinVoice = { channelId ->
                                        session.calls.join(accountSession, channelId)
                                        navController.navigate(CallRoute)
                                    },
                                )
                            }
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
        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
            val accountSession = sessions[route.accountId]
            if (accountSession == null) {
                // A conta saiu (removida ou sessão trocada) com a conversa aberta.
                LaunchedEffect(Unit) { navController.popBackStack(HomeRoute, inclusive = false) }
            } else {
                val vm = viewModel(key = "${route.accountId}/${route.channelId}/${route.messageId}/${accountSession.hashCode()}") {
                    val target = if (route.messageId != null && route.messageAt != null) MessageTarget(route.messageId, route.messageAt) else null
                    ChatViewModel(accountSession, route.channelId, target)
                }
                ChatScreen(
                    session = accountSession,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onSearch = { navController.navigate(SearchRoute(route.accountId, route.channelId)) },
                    onSwitch = { navController.navigate(SwitcherRoute(route.accountId, route.channelId)) },
                    onJoinCall = if (session.calls.supported) {
                        {
                            session.calls.join(accountSession, route.channelId)
                            // Se a chamada já está aberta por baixo desta conversa, a conversa sai da frente dela.
                            navController.navigate(CallRoute) { popUpTo<CallRoute> { inclusive = true } }
                        }
                    } else {
                        null
                    },
                )
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
                    onOpen = { hit -> navController.navigate(ChatRoute(route.accountId, hit.channelId, hit.id, hit.createdAtIso)) },
                )
            }
        }
        composable<CallRoute> {
            CallScreen(
                session,
                // Volta a quem abriu a chamada: a lista de conversas ou o chat do canal de voz.
                onBack = { navController.popBackStack(CallRoute, inclusive = true) },
                onOpenChat = { accountId, channelId -> navController.navigate(ChatRoute(accountId, channelId)) },
            )
        }
        composable<AccountSettingsRoute> { entry ->
            AccountSettingsScreen(session, entry.toRoute<AccountSettingsRoute>().accountId, onBack = { navController.popBackStack() })
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
                onOpen = { target ->
                    // A conta da conversa passa a ser a ativa; ao voltar do chat se chega na tela principal dela.
                    session.accounts.select(target.accountId)
                    navController.navigate(ChatRoute(target.accountId, target.channelId)) {
                        popUpTo<HomeRoute> { inclusive = false }
                    }
                },
            )
        }
    }
}
