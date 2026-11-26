package chat.papo.mobile.ui.home

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.topTextChannel

/**
 * A abertura automática: ao abrir o app (ou destravá-lo, que o abre de novo), o painel mostra o canal de texto mais ao
 * topo da conta selecionada, uma vez só. Fica no estado do painel: girar o aparelho o guarda, e travar o app o apaga.
 */
internal sealed interface StartupOpening {
    /** Ainda não se olhou a conta selecionada. */
    data object Fresh : StartupOpening

    /** Esperando a lista de [accountId] chegar do servidor. */
    data class Waiting(val accountId: String) : StartupOpening

    /** Resolvida, tenha aberto ou não: não abre mais até o app ser aberto de novo. */
    data object Done : StartupOpening

    /** O estado como vai para o estado salvo. */
    fun encode(): String = when (this) {
        Fresh -> FRESH
        is Waiting -> WAITING + accountId
        Done -> DONE
    }

    companion object {
        private const val FRESH = "fresh"
        private const val WAITING = "waiting:"
        private const val DONE = "done"

        /** O inverso de [encode]. O que vier estragado vale [Done]: na dúvida não se abre nada por cima do que estava. */
        fun decode(text: String?): StartupOpening = when {
            text == FRESH -> Fresh
            text != null && text.startsWith(WAITING) && text.length > WAITING.length -> Waiting(text.removePrefix(WAITING))
            else -> Done
        }
    }
}

/** O que a tela principal sabe, a cada mudança, para decidir se abre o canal do topo. */
internal data class StartupInput(
    /** A conta que a tela principal mostra; nula enquanto as contas não carregaram. */
    val accountId: String?,
    /** A sessão da conta foi encerrada: ela precisa entrar de novo, e a tela mostra isso no lugar da lista. */
    val sessionExpired: Boolean,
    /** A conta já tem uma conversa estacionada: aberta por quem usa, ou vinda do estado salvo. */
    val hasParkedChat: Boolean,
    /** A tela principal é a que está à vista, sem busca, configurações ou chamada por cima. */
    val onHome: Boolean,
    /** Os canais da conta já chegaram do servidor nesta sessão; antes disso a lista é a do banco, de uma sessão anterior. */
    val synced: Boolean,
    /** Os canais que a lista de canais mostra. */
    val channels: List<Channel>,
)

/** O próximo [next] da abertura e, se for o caso, o canal [open] que o painel deve abrir agora. */
internal data class StartupStep(val next: StartupOpening, val open: String? = null)

/**
 * Um passo da abertura. O que quem usa já fez (abrir ou fechar uma conversa, sair da tela principal) ou o que deixa sem o
 * que abrir (sessão encerrada, nenhum canal de texto) encerra a espera. O que ainda está chegando faz esperar, nunca
 * desistir: as contas carregando (conta nula), a conta salva trocada pela válida ao carregar, os canais do servidor ainda
 * não chegados, a lista ainda relida do banco (vazia). Não se pede nada ao servidor: usa-se o que a sincronização traz.
 */
internal fun StartupOpening.step(input: StartupInput): StartupStep = when (this) {
    StartupOpening.Done -> StartupStep(StartupOpening.Done)
    // Esperando, a conta pode mudar sem ninguém tocar em nada (a salva era de uma conta que saiu, e o app escolhe outra ao
    // carregar): a abertura segue a conta que a tela principal mostra agora.
    StartupOpening.Fresh, is StartupOpening.Waiting -> decide(input)
}

private fun decide(input: StartupInput): StartupStep {
    if (input.sessionExpired || input.hasParkedChat || !input.onHome) return StartupStep(StartupOpening.Done)
    val account = input.accountId ?: return StartupStep(StartupOpening.Fresh)
    if (!input.synced || input.channels.isEmpty()) return StartupStep(StartupOpening.Waiting(account))
    val top = topTextChannel(input.channels) ?: return StartupStep(StartupOpening.Done)
    return StartupStep(StartupOpening.Done, open = top.id)
}
