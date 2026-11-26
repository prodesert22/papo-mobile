package chat.papo.mobile.platform

import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * O que o [SharedMediaProvider] entrega a outros aplicativos, guardado só na memória (regra 8: nenhum arquivo em disco).
 * Cada arquivo vive sob um token aleatório que:
 *  - expira em [lifetime] depois de posto, e some com [clear] (o app travou) ou quando passa de [capacity] arquivos;
 *  - é lido uma vez por aplicativo ([take]): a folha de compartilhamento do sistema abre o conteúdo para a prévia, o aplicativo
 *    escolhido abre o seu, e um aplicativo que repete o pedido não leva o conteúdo de novo. O nome, o tipo e o tamanho
 *    ([info]) respondem enquanto o token vale, porque há quem os pergunte depois de ler.
 * Quem lê é identificado pelo `uid` que o Android informa ao provedor, não por algo que o leitor diga de si.
 */
class SharedMediaStore(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val lifetime: Duration = LIFETIME,
    private val capacity: Int = CAPACITY,
    private val random: SecureRandom = SecureRandom(),
) {
    /** O que se sabe do arquivo sem lê-lo. */
    class Info(val name: String, val mimeType: String, val size: Long)

    private class Entry(val info: Info, val bytes: ByteArray, val expires: TimeMark) {
        val readers = HashSet<Int>()
    }

    // Do mais antigo para o mais novo: passando da capacidade sai o primeiro.
    private val entries = LinkedHashMap<String, Entry>()

    /** Guarda [bytes] (sem copiar: ninguém os altera) e devolve o token. */
    @Synchronized
    fun put(name: String, mimeType: String, bytes: ByteArray): String {
        purge()
        val token = newToken()
        entries[token] = Entry(Info(name, mimeType, bytes.size.toLong()), bytes, timeSource.markNow() + lifetime)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return token
    }

    /** O nome, o tipo e o tamanho do arquivo de [token], ou null se o token não existe, expirou ou foi limpo. */
    @Synchronized
    fun info(token: String): Info? = live(token)?.info

    /** O conteúdo de [token] para o leitor [reader], ou null se não vale mais ou se esse leitor já o levou. */
    @Synchronized
    fun take(token: String, reader: Int): ByteArray? {
        val entry = live(token) ?: return null
        return if (entry.readers.add(reader)) entry.bytes else null
    }

    /** Esquece [token] (o compartilhamento não chegou a abrir). */
    @Synchronized
    fun discard(token: String) {
        entries.remove(token)
    }

    /** Esquece tudo: o app travou, ou os dados locais foram apagados. */
    @Synchronized
    fun clear() = entries.clear()

    /** Solta o que já expirou, para os bytes não ficarem na memória à espera da próxima chamada. */
    @Synchronized
    fun purge() {
        entries.values.removeAll { it.expires.hasPassedNow() }
    }

    private fun live(token: String): Entry? {
        val entry = entries[token] ?: return null
        if (!entry.expires.hasPassedNow()) return entry
        entries.remove(token)
        return null
    }

    private fun newToken(): String = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Quanto um compartilhamento vale: o bastante para escolher o aplicativo, e pouco para o que ficou sem ser lido. */
        val LIFETIME: Duration = 2.minutes

        /** Quantos compartilhamentos valem ao mesmo tempo; cada um segura o arquivo inteiro na memória. */
        const val CAPACITY = 4

        private const val TOKEN_BYTES = 16
        private val TOKEN = Regex("[0-9a-f]{${TOKEN_BYTES * 2}}")

        /** [text] tem a forma de um token desta classe? Um endereço qualquer não passa nem chega ao depósito. */
        fun isToken(text: String): Boolean = TOKEN.matches(text)
    }
}
