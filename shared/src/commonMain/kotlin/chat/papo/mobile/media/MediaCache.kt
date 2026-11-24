package chat.papo.mobile.media

import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.CryptoException
import chat.papo.mobile.core.crypto.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Cache de mídia de uma conta (avatares, miniaturas, imagens). Em disco tudo
 * fica cifrado com AES-256-GCM e com nome de arquivo derivado por HMAC, de modo
 * que nem o conteúdo nem a que recurso ele se refere ficam legíveis.
 */
class MediaCache(
    private val directory: String,
    private val key: ByteArray,
    private val crypto: Crypto,
    private val io: CoroutineDispatcher,
    private val memoryBudget: Int = 24 * 1024 * 1024,
    private val maxDiskEntry: Int = 16 * 1024 * 1024,
) {
    private val mutex = Mutex()
    private val memory = LinkedHashMap<String, ByteArray>()
    private var memoryUsed = 0
    private val inFlight = HashMap<String, CompletableDeferred<ByteArray?>>()

    /** Devolve o recurso [name], buscando com [fetch] na primeira vez. Null se não existe. */
    suspend fun get(name: String, fetch: suspend () -> ByteArray?): ByteArray? {
        val (pending, owner) = mutex.withLock {
            memory[name]?.let { hit ->
                memory.remove(name)
                memory[name] = hit // move para o fim (mais recente)
                return hit
            }
            inFlight[name]?.let { it to false } ?: (CompletableDeferred<ByteArray?>().also { inFlight[name] = it } to true)
        }
        if (!owner) return pending.await()

        try {
            val bytes = readDisk(name) ?: fetch()?.also { writeDisk(name, it) }
            mutex.withLock { if (bytes != null) remember(name, bytes) }
            pending.complete(bytes)
            return bytes
        } catch (e: Throwable) {
            pending.completeExceptionally(e)
            throw e
        } finally {
            mutex.withLock { inFlight.remove(name) }
        }
    }

    suspend fun evict(name: String) {
        mutex.withLock { memory.remove(name)?.let { memoryUsed -= it.size } }
        withContext(io) { runCatching { SystemFileSystem.delete(fileFor(name), mustExist = false) } }
    }

    fun clearMemory() {
        memory.clear()
        memoryUsed = 0
    }

    private fun remember(name: String, bytes: ByteArray) {
        if (bytes.size > memoryBudget / 4) return
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
