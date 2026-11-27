package chat.papo.mobile.account

import chat.papo.mobile.data.db.account.MetaQueries
import chat.papo.mobile.ui.theme.ThemeSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** O tema de uma conta: o do app (padrão) ou um só dela. */
sealed interface ServerTheme {
    data object UseApp : ServerTheme

    data class Own(val spec: ThemeSpec) : ServerTheme
}

/**
 * O tema só deste servidor. Fica na tabela `meta` do banco cifrado da conta (chave [KEY]), então não sai do aparelho nem
 * vale para outra conta, mesmo no mesmo servidor (regra 4). Sem tema próprio não há linha: [ServerTheme.UseApp] é o padrão.
 *
 * [state] é nulo até a primeira leitura terminar; a tela espera por ela para não mostrar o tema do app por um instante
 * numa conta que tem o próprio. O escopo e o banco são da sessão da conta, que os fecha.
 */
class ServerThemeStore(
    private val meta: MetaQueries,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<ServerTheme?>(null)
    val state: StateFlow<ServerTheme?> = _state.asStateFlow()

    /** Uma gravação por vez, sempre do valor mais novo: duas em paralelo poderiam terminar fora de ordem. */
    private val writeLock = Mutex()

    init {
        scope.launch {
            _state.value = try {
                decode(withContext(io) { meta.get(KEY).executeAsOneOrNull() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerTheme.UseApp // um banco ilegível não pode deixar a conta sem tela
            }
        }
    }

    /** Vale já na tela; a gravação vem em seguida. Antes da primeira leitura terminar não há o que trocar. */
    fun set(theme: ServerTheme) {
        if (_state.value == null) return
        _state.value = theme
        scope.launch {
            writeLock.withLock {
                val latest = _state.value ?: return@withLock
                try {
                    withContext(io) {
                        when (latest) {
                            ServerTheme.UseApp -> meta.remove(KEY)
                            is ServerTheme.Own -> meta.put(KEY, latest.spec.encode())
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A escolha vale nesta sessão; a próxima mudança grava de novo.
                }
            }
        }
    }

    companion object {
        const val KEY = "ui.theme"

        /** Uma linha ausente ou ilegível é "usar o tema do app". */
        fun decode(raw: String?): ServerTheme = ThemeSpec.decodeOrNull(raw)?.let(ServerTheme::Own) ?: ServerTheme.UseApp
    }
}
