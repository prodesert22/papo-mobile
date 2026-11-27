package chat.papo.mobile.push

import chat.papo.mobile.data.api.PushApi
import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * O registro do aparelho para push no servidor de uma conta (`PUT /users/me/push-device`). É da sessão da conta: roda no
 * escopo dela, pelo cliente dela, e o que lembra fica na tabela `meta` do banco cifrado dela (regras 4 e 8). Duas contas
 * do mesmo servidor registram cada uma o seu, sem se ver.
 *
 * O servidor guarda o token como único em todo o servidor e o `PUT` passa o dono para quem registrou por último (limitação
 * do backend, não contornada aqui): com duas contas do mesmo servidor no mesmo aparelho, só a última a registrar recebe.
 *
 * Um registro só vai ao servidor quando o token que ele guarda para esta conta é outro. Falhou, não se repete (regra 15,
 * sobretudo num 429): o token fica sem registro e a próxima sessão tenta de novo. Nada aqui chega a log ou a mensagem.
 */
class PushRegistration(
    private val api: PushApi,
    private val tokens: PushTokens,
    private val meta: MetaQueries,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** A conta tem uma sessão com cookie; sem ela o servidor só responderia 401. */
    private val hasSession: () -> Boolean,
) {
    /** Uma operação por vez; guarda também [attempted] e [stopped]. */
    private val lock = Mutex()

    /** O último token que esta sessão já levou ao servidor, com ou sem sucesso: o mesmo token não é enviado de novo. */
    private var attempted: String? = null
    private var stopped = false
    private var job: Job? = null

    /** Começa a registrar: o token de agora e cada troca dele. Sem efeito sem push no aparelho ou se já começou. */
    fun start() {
        if (!tokens.available || job != null) return
        job = scope.launch {
            // `current()` pode esperar a rede; `changes` repete o último token trocado, então nenhuma troca se perde.
            merge(flow { tokens.currentOrNull()?.let { emit(it) } }, tokens.changes).collect { register(it) }
        }
    }

    /**
     * Tira o aparelho do servidor desta conta (a conta vai sair, ou o app a removeu) e para de registrar. Tira o token
     * registrado e o de agora, porque um `PUT` pode ter chegado sem a resposta chegar. Erro de qualquer tipo é calado: a
     * saída não espera por isso (um 401 ou 404 só diz que não havia o que tirar).
     */
    suspend fun unregister() {
        if (!tokens.available) return
        lock.withLock {
            stopped = true
            if (!hasSession()) return
            val name = fitDeviceName(tokens.deviceName)
            val known = quietResult { registered() }
            for (token in listOfNotNull(known, tokens.currentOrNull()).distinct()) quietly { api.unregister(token, name) }
            quietly { withContext(io) { meta.remove(KEY) } }
        }
    }

    /**
     * Outra conta do mesmo servidor saiu e pode ter levado o registro, que o servidor guarda por token: esquece o que
     * lembrava e registra de novo (uma vez). Em sessão que ainda não começou, só esquece; ela registra ao começar.
     */
    fun registerAgain() {
        if (!tokens.available) return
        scope.launch {
            lock.withLock {
                attempted = null
                quietly { withContext(io) { meta.remove(KEY) } }
            }
            if (job != null) tokens.currentOrNull()?.let { register(it) }
        }
    }

    private suspend fun register(token: String) {
        lock.withLock {
            if (stopped || token == attempted || !hasSession()) return
            attempted = token
            quietly {
                if (registered() == token) return@quietly
                api.register(token, fitDeviceName(tokens.deviceName))
                withContext(io) { meta.put(KEY, token) }
            }
        }
    }

    private suspend fun registered(): String? = withContext(io) { meta.get(KEY).executeAsOneOrNull() }

    private suspend fun PushTokens.currentOrNull(): String? =
        withTimeoutOrNull(TOKEN_WAIT) { quietResult { current() } }

    private suspend fun quietly(block: suspend () -> Unit) {
        quietResult(block)
    }

    /** Executa [block] e devolve o resultado; qualquer falha, menos o cancelamento, vira nulo sem deixar rastro. */
    private suspend fun <T> quietResult(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEY = "push.token"
        val TOKEN_WAIT = 10.seconds
    }
}
