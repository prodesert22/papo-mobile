package chat.papo.mobile.core

import kotlinx.coroutines.CoroutineDispatcher

class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    /**
     * Trabalho pesado que pode esperar (ler e guardar os emojis do servidor): poucas threads
     * de prioridade baixa, para não disputar a CPU com a tela.
     */
    val background: CoroutineDispatcher = default,
)
