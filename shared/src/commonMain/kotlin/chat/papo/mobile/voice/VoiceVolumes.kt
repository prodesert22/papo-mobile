package chat.papo.mobile.voice

import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

const val DEFAULT_VOLUME_PERCENT = 100
const val MAX_VOLUME_PERCENT = 200

/**
 * O volume que o usuário escolheu para cada pessoa da chamada, em porcentagem (0 a 200; 100 é o normal e
 * 0, silenciada para ele): [voice] para o microfone da pessoa, [screen] para o áudio da transmissão de tela.
 * Só guarda o que difere do normal.
 */
data class VolumeLevels(val voice: Map<String, Int> = emptyMap(), val screen: Map<String, Int> = emptyMap()) {
    fun voiceOf(userId: String): Int = voice[userId] ?: DEFAULT_VOLUME_PERCENT
    fun screenOf(userId: String): Int = screen[userId] ?: DEFAULT_VOLUME_PERCENT
}

/**
 * O ganho de cada faixa de áudio recebida: o volume escolhido para quem toca nela (voz ou áudio de tela, pelo
 * tipo da rota), como fração (100 % = 1,0), limitado a 0,0–2,0. Rota de tipo desconhecido fica de fora; rota de
 * quem não tem volume guardado toca no normal.
 */
fun gains(routes: Map<String, AudioRoute>, levels: VolumeLevels): Map<String, Double> {
    val result = LinkedHashMap<String, Double>()
    for ((trackId, route) in routes) {
        val percent = when (route.kind) {
            AudioRoute.MICROPHONE -> levels.voiceOf(route.userId)
            AudioRoute.SCREEN_AUDIO -> levels.screenOf(route.userId)
            else -> continue
        }
        result[trackId] = (percent / 100.0).coerceIn(0.0, MAX_VOLUME_PERCENT / 100.0)
    }
    return result
}

/** Os volumes por pessoa de uma conta. Nada disso vai ao servidor. */
interface VoiceVolumes {
    val levels: StateFlow<VolumeLevels>

    /** Espera os volumes guardados serem lidos, para a chamada não tocar no normal alguém que o usuário silenciou. */
    suspend fun awaitLoaded()

    fun setVoice(userId: String, percent: Int)

    fun setScreen(userId: String, percent: Int)

    /** Grava já o que está esperando. */
    fun flush()
}

/**
 * [VoiceVolumes] na tabela `meta` do banco cifrado da conta, uma chave por pessoa e tipo
 * (`voice_volume:<usuário>`, `voice_screen_volume:<usuário>`), então não saem do aparelho nem se misturam entre
 * contas. O normal (100 %) apaga a chave. Valor guardado fora do formato é ignorado.
 *
 * A gravação espera [debounce] sem novas mudanças (o controle deslizante manda muitas); [flush] grava já. Quem
 * grava é o escopo da sessão.
 */
class StoredVoiceVolumes(
    private val meta: MetaQueries,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val debounce: Duration = DEBOUNCE,
) : VoiceVolumes {
    private val _levels = MutableStateFlow(VolumeLevels())
    override val levels: StateFlow<VolumeLevels> = _levels.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** O valor mais novo de cada chave que ainda não chegou ao banco; null quer dizer "apagar" (voltou ao normal). */
    private val pending = MutableStateFlow<Map<Slot, Int?>>(emptyMap())

    /** Uma gravação por vez, sempre do valor mais novo. */
    private val writeLock = Mutex()
    private var timer: Job? = null

    init {
        scope.launch {
            try {
                val stored = withContext(io) { readAll() }
                // O que o usuário mexeu antes de a leitura terminar é mais novo que o banco.
                _levels.update { withChanges(stored, pending.value) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sem ler o banco, a chamada toca no normal; o que o usuário mexer daqui em diante continua valendo.
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    override suspend fun awaitLoaded() = loaded.await()

    override fun setVoice(userId: String, percent: Int) = set(Slot(Kind.Voice, userId), percent)

    override fun setScreen(userId: String, percent: Int) = set(Slot(Kind.Screen, userId), percent)

    override fun flush() {
        scope.launch { writePending() }
    }

    private fun set(slot: Slot, percent: Int) {
        if (slot.userId.isEmpty() || slot.userId.length > MAX_ID) return
        val value = percent.coerceIn(0, MAX_VOLUME_PERCENT).takeIf { it != DEFAULT_VOLUME_PERCENT }
        pending.update { it + (slot to value) }
        _levels.update { it.with(slot, value) }
        timer?.cancel()
        timer = scope.launch {
            delay(debounce)
            writePending()
        }
    }

    private fun readAll(): VolumeLevels {
        var result = VolumeLevels()
        for (kind in Kind.entries) {
            for (key in meta.keysWithPrefix(kind.prefix).executeAsList()) {
                if (!key.startsWith(kind.prefix)) continue // o LIKE do SQLite trata "_" como curinga
                val userId = key.removePrefix(kind.prefix)
                if (userId.isEmpty() || userId.length > MAX_ID) continue
                val percent = decode(meta.get(key).executeAsOneOrNull()) ?: continue
                result = result.with(Slot(kind, userId), percent.takeIf { it != DEFAULT_VOLUME_PERCENT })
            }
        }
        return result
    }

    private suspend fun writePending() = writeLock.withLock {
        for ((slot, percent) in pending.value) {
            try {
                withContext(io) {
                    if (percent == null) meta.remove(slot.key) else meta.put(slot.key, percent.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // O volume é um conforto: se o banco falhou, ele segue esperando e vai na próxima gravação.
                continue
            }
            // Se mudou enquanto gravava, o valor novo continua esperando a vez dele.
            pending.update { if (slot in it && it[slot] == percent) it - slot else it }
        }
    }

    private enum class Kind(val prefix: String) {
        Voice("voice_volume:"),
        Screen("voice_screen_volume:"),
    }

    private data class Slot(val kind: Kind, val userId: String) {
        val key: String get() = kind.prefix + userId
    }

    private fun VolumeLevels.with(slot: Slot, percent: Int?): VolumeLevels = when (slot.kind) {
        Kind.Voice -> copy(voice = if (percent == null) voice - slot.userId else voice + (slot.userId to percent))
        Kind.Screen -> copy(screen = if (percent == null) screen - slot.userId else screen + (slot.userId to percent))
    }

    private fun withChanges(base: VolumeLevels, changes: Map<Slot, Int?>): VolumeLevels =
        changes.entries.fold(base) { levels, (slot, percent) -> levels.with(slot, percent) }

    internal companion object {
        val DEBOUNCE = 400.milliseconds
        private const val MAX_ID = 64

        fun voiceKey(userId: String) = Kind.Voice.prefix + userId
        fun screenKey(userId: String) = Kind.Screen.prefix + userId

        /** Estrito de propósito: só este código escreve o valor, então o que foge do formato é lixo. */
        fun decode(value: String?): Int? {
            val percent = value?.toIntOrNull() ?: return null
            return percent.takeIf { it in 0..MAX_VOLUME_PERCENT && it.toString() == value }
        }
    }
}
