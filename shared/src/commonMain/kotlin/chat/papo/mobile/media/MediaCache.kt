package chat.papo.mobile.media

import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.CryptoException
import chat.papo.mobile.core.crypto.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A busca de um recurso falhou há pouco e o cache ainda não tenta de novo; [cause] é a falha original. */
class MediaUnavailableException(cause: Throwable) : Exception("A busca falhou há pouco; nova tentativa só depois da janela", cause)

/**
 * Cache de mídia de uma conta (avatares, miniaturas, imagens). Em disco tudo
 * fica cifrado com AES-256-GCM e com nome de arquivo derivado por HMAC, de modo
 * que nem o conteúdo nem a que recurso ele se refere ficam legíveis.
 *
 * Também lembra, só em memória e só nesta instância, o que o servidor disse que não existe e o que
 * falhou há pouco, para a interface (que pergunta a cada composição) não repetir o mesmo pedido.
 */
class MediaCache(
    private val directory: String,
    private val key: ByteArray,
    private val crypto: Crypto,
    private val io: CoroutineDispatcher,
    private val memoryBudget: Int = 24 * 1024 * 1024,
    private val maxDiskEntry: Int = 16 * 1024 * 1024,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** O que se sabe de um nome que não foi entregue. */
    private sealed interface Miss {
        /** O servidor respondeu que o recurso não existe. Vale até [put], [evict] ou [clearMemory]. */
        data object Absent : Miss

        /** A busca falhou em [at]; vale por [FAILURE_WINDOW]. */
        class Failed(val at: TimeMark, val cause: Throwable) : Miss
    }

    // internal só para os testes: a contenção do mutex exige threads reais, então eles o seguram à mão.
    internal val mutex = Mutex()
    private val memory = LinkedHashMap<String, ByteArray>()
    private var memoryUsed = 0
    private val inFlight = HashMap<String, CompletableDeferred<ByteArray?>>()
    private val misses = LinkedHashMap<String, Miss>()

    // Nomes que receberam [put] durante a busca: o que a busca disser sobre eles já está desatualizado.
    private val putWhileFetching = HashSet<String>()

    /**
     * Devolve o recurso [name], buscando com [fetch] na primeira vez. Null se não existe, e então
     * [fetch] não roda de novo para este nome. Se a busca falhou há menos de [FAILURE_WINDOW], lança
     * [MediaUnavailableException] sem buscar; passada a janela, uma nova busca é permitida.
     */
    suspend fun get(name: String, fetch: suspend () -> ByteArray?): ByteArray? {
        val (pending, owner) = mutex.withLock {
            memory[name]?.let { hit ->
                memory.remove(name)
                memory[name] = hit // move para o fim (mais recente)
                return hit
            }
            if (knownMissing(name)) return null
            claim(name)
        }
        if (!owner) return pending.await()

        try {
            val bytes = readDisk(name) ?: fetch()?.also { writeDisk(name, it) }
            mutex.withLock { if (bytes != null) remember(name, bytes) else noteMiss(name, Miss.Absent) }
            pending.complete(bytes)
            return bytes
        } catch (e: CancellationException) {
            // Cancelar não diz nada sobre o recurso: não vira ausência nem falha.
            pending.completeExceptionally(e)
            throw e
        } catch (e: Throwable) {
            // Quem já foi cancelado pode receber a falha como outra exceção (o motor HTTP aborta o pedido com
            // IOException, por exemplo); isso é cancelamento, não falha do recurso.
            if (currentCoroutineContext().isActive) {
                withContext(NonCancellable) { mutex.withLock { noteMiss(name, Miss.Failed(timeSource.markNow(), e)) } }
            }
            pending.completeExceptionally(e)
            throw e
        } finally {
            // Roda mesmo com quem busca cancelado: sem NonCancellable, o withLock lança
            // CancellationException sob contenção e a entrada fica em inFlight para sempre.
            withContext(NonCancellable) {
                mutex.withLock {
                    inFlight.remove(name)
                    putWhileFetching.remove(name)
                }
            }
        }
    }

    /** O recurso está guardado (memória ou disco)? Não decifra nada. */
    suspend fun has(name: String): Boolean {
        if (mutex.withLock { name in memory }) return true
        return withContext(io) { SystemFileSystem.exists(fileFor(name)) }
    }

    /**
     * Lê o que já está guardado, sem buscar nada. Diferente de [get], não espera
     * outra busca do mesmo nome: dá para chamar de dentro do `fetch` dele.
     */
    suspend fun peek(name: String): ByteArray? {
        mutex.withLock { memory[name] }?.let { return it }
        return readDisk(name)?.also { bytes -> mutex.withLock { remember(name, bytes) } }
    }

    /**
     * Guarda [bytes] em disco, cifrados, sem passar por [get]. Com [keepInMemory]
     * falso não ocupa a memória do cache (para pré-carregar muitos itens de uma vez).
     */
    suspend fun put(name: String, bytes: ByteArray, keepInMemory: Boolean = true) {
        writeDisk(name, bytes)
        // Depois de gravar e sem poder ser cancelado: o que se lembrava de ausente ou falho deixa de valer.
        withContext(NonCancellable) {
            mutex.withLock {
                misses.remove(name)
                if (name in inFlight) putWhileFetching += name
                if (keepInMemory) remember(name, bytes)
            }
        }
    }

    suspend fun evict(name: String) {
        mutex.withLock {
            memory.remove(name)?.let { memoryUsed -= it.size }
            misses.remove(name)
        }
        withContext(io) { runCatching { SystemFileSystem.delete(fileFor(name), mustExist = false) } }
    }

    /** Esquece o que está na memória, inclusive o que se lembrava de ausente ou falho; o disco fica. */
    suspend fun clearMemory() {
        mutex.withLock {
            memory.clear()
            memoryUsed = 0
            misses.clear()
        }
    }

    /** Sob o mutex: true se [name] é conhecido como ausente; lança se a busca falhou há pouco; false se pode buscar. */
    private fun knownMissing(name: String): Boolean = when (val miss = misses[name]) {
        Miss.Absent -> true
        is Miss.Failed -> if (miss.at.elapsedNow() < FAILURE_WINDOW) {
            throw MediaUnavailableException(miss.cause)
        } else {
            misses.remove(name)
            false
        }
        null -> false
    }

    /** Sob o mutex: junta-se à busca de [name] em andamento (false) ou passa a ser a dona dela (true). */
    private fun claim(name: String): Pair<CompletableDeferred<ByteArray?>, Boolean> =
        inFlight[name]?.let { it to false } ?: (CompletableDeferred<ByteArray?>().also { inFlight[name] = it } to true)

    private fun noteMiss(name: String, miss: Miss) {
        if (name in putWhileFetching) return
        misses.remove(name)
        misses[name] = miss
        if (misses.size > MAX_MISSES) misses.remove(misses.keys.first())
    }

    private fun remember(name: String, bytes: ByteArray) {
        if (bytes.size > memoryBudget / 4) return
        memory.remove(name)?.let { memoryUsed -= it.size }
        memory[name] = bytes
        memoryUsed += bytes.size
        val iterator = memory.entries.iterator()
        while (memoryUsed > memoryBudget && iterator.hasNext()) {
            memoryUsed -= iterator.next().value.size
            iterator.remove()
        }
    }

    private fun fileFor(name: String): Path =
        Path(directory, crypto.hmacSha256(key, name.encodeToByteArray()).toHex())

    private suspend fun readDisk(name: String): ByteArray? = withContext(io) {
        val path = fileFor(name)
        if (!SystemFileSystem.exists(path)) return@withContext null
        try {
            val sealed = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
            crypto.open(key, sealed, aad = name.encodeToByteArray())
        } catch (e: CryptoException) {
            // Arquivo adulterado ou de outra chave: descarta e busca de novo.
            runCatching { SystemFileSystem.delete(path, mustExist = false) }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun writeDisk(name: String, bytes: ByteArray) = withContext(io) {
        if (bytes.size > maxDiskEntry) return@withContext
        runCatching {
            SystemFileSystem.createDirectories(Path(directory))
            val target = fileFor(name)
            val temp = Path(directory, target.name + ".tmp")
            SystemFileSystem.sink(temp).buffered().use { it.write(crypto.seal(key, bytes, aad = name.encodeToByteArray())) }
            SystemFileSystem.atomicMove(temp, target)
        }
    }

    companion object {
        /** Quanto tempo uma busca que falhou não é tentada de novo. */
        internal val FAILURE_WINDOW = 30.seconds

        /** Limite de nomes lembrados como ausentes ou falhos; passando dele, esquece os mais antigos. */
        internal const val MAX_MISSES = 1024

        /** Remove do disco o cache em [directory] (conta removida ou dados apagados). */
        fun deleteDirectory(directory: String) {
            val root = Path(directory)
            if (!SystemFileSystem.exists(root)) return
            runCatching {
                SystemFileSystem.list(root).forEach { child ->
                    if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) deleteDirectory(child.toString())
                    else SystemFileSystem.delete(child, mustExist = false)
                }
                SystemFileSystem.delete(root, mustExist = false)
            }
        }
    }
}
