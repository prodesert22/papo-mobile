package chat.papo.mobile.media

import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.CryptoException
import chat.papo.mobile.core.crypto.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

        /** A busca falhou em [at]; vale por [FAILURE_WINDOW], para quem pede com `holdFailures`. */
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

    /** [get] sem segurar falhas: toda chamada busca de novo o que falhou. */
    suspend fun get(name: String, fetch: suspend () -> ByteArray?): ByteArray? = get(name, holdFailures = false, fetch)

    /**
     * Devolve o recurso [name], buscando com [fetch] na primeira vez. Null se não existe, e então
     * [fetch] não roda de novo para este nome.
     *
     * Com [holdFailures], uma busca que falhou há menos de [FAILURE_WINDOW] não é repetida: a chamada lança
     * [MediaUnavailableException] sem buscar, e passada a janela uma nova busca é permitida. É para o que a
     * interface pede sozinha e em quantidade, como os avatares de uma lista, onde repetir a cada exibição
     * esbarra no limite de taxa do servidor. Sem ele toda chamada busca de novo, que é o que serve a um
     * pedido feito pelo usuário, como "Tentar de novo".
     */
    suspend fun get(name: String, holdFailures: Boolean, fetch: suspend () -> ByteArray?): ByteArray? {
        val (pending, owner) = mutex.withLock {
            memory[name]?.let { hit ->
                memory.remove(name)
                memory[name] = hit // move para o fim (mais recente)
                return hit
            }
            if (knownMissing(name, holdFailures)) return null
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
            if (holdFailures && currentCoroutineContext().isActive) {
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

    /**
     * Apaga do disco, e da memória, a mídia de mensagem cujo nome não está em [keep]: o que sobrou de mensagens que
     * saíram do aparelho, e arquivos temporários esquecidos por uma gravação interrompida. Só mexe na área das
     * mensagens ([MESSAGE_MEDIA_PREFIX]); avatares, emojis e o resto ficam como estão.
     *
     * Não trava as buscas: uma que grava um arquivo agora, para um nome fora de [keep], pode perdê-lo, e então a mídia
     * é buscada de novo na próxima vez. Nunca fica um arquivo corrompido: a gravação é um temporário movido de uma vez,
     * e quem lê trata o arquivo que sumiu como ausente. Os nomes que têm uma busca em andamento são poupados.
     */
    suspend fun sweepMessageMedia(keep: Set<String>) {
        val writing = forgetFromMemory(keep) { it.startsWith(MESSAGE_MEDIA_PREFIX) }
        withContext(io) { deleteFilesExcept(Path(directory, MESSAGE_AREA), keep, writing) }
    }

    /**
     * Apaga os arquivos que estão fora da área das mensagens e cujo nome não está em [keep]. É a migração do
     * cache que guardava tudo numa pasta só, onde a mídia de mensagem não se distingue das outras (os nomes são HMAC):
     * [keep] diz o que se quer manter e o resto sai. Mesmas garantias de [sweepMessageMedia].
     */
    suspend fun sweepOutsideMessageArea(keep: Set<String>) {
        val writing = forgetFromMemory(keep) { !it.startsWith(MESSAGE_MEDIA_PREFIX) }
        withContext(io) { deleteFilesExcept(Path(directory), keep, writing) }
    }

    /**
     * Tira da memória os nomes de [area] que não estão em [keep] nem têm busca em andamento; devolve os que têm, que a
     * varredura do disco também poupa.
     */
    private suspend fun forgetFromMemory(keep: Set<String>, area: (String) -> Boolean): Set<String> = mutex.withLock {
        val writing = inFlight.keys.filterTo(HashSet(), area)
        val iterator = memory.entries.iterator()
        while (iterator.hasNext()) {
            val (name, bytes) = iterator.next()
            if (area(name) && name !in keep && name !in writing) {
                memoryUsed -= bytes.size
                iterator.remove()
            }
        }
        writing
    }

    /**
     * Apaga os arquivos de [folder] (não as subpastas) que não são os de [keep]. Os de [writing] têm uma gravação em
     * andamento, então o temporário deles também fica; o de qualquer outro nome é resto de gravação interrompida.
     */
    private fun CoroutineScope.deleteFilesExcept(folder: Path, keep: Set<String>, writing: Set<String>) {
        if (!SystemFileSystem.exists(folder)) return
        val keepFiles = HashSet<String>()
        for (name in keep) keepFiles += hashOf(name)
        for (name in writing) hashOf(name).let { keepFiles += it; keepFiles += "$it.tmp" }
        val children = runCatching { SystemFileSystem.list(folder) }.getOrElse { return }
        for (child in children) {
            ensureActive()
            if (child.name in keepFiles) continue
            runCatching {
                if (SystemFileSystem.metadataOrNull(child)?.isDirectory != true) SystemFileSystem.delete(child, mustExist = false)
            }
        }
    }

    /** Esquece o que está na memória, inclusive o que se lembrava de ausente ou falho; o disco fica. */
    suspend fun clearMemory() {
        mutex.withLock {
            memory.clear()
            memoryUsed = 0
            misses.clear()
        }
    }

    /**
     * Sob o mutex: true se [name] é conhecido como ausente; com [holdFailures], lança se a busca falhou há
     * pouco; false se pode buscar. Quem não segura falhas esquece a que estava lembrada e busca.
     */
    private fun knownMissing(name: String, holdFailures: Boolean): Boolean = when (val miss = misses[name]) {
        Miss.Absent -> true
        is Miss.Failed -> if (holdFailures && miss.at.elapsedNow() < FAILURE_WINDOW) {
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

    /**
     * Mídia de mensagem fica na subpasta [MESSAGE_AREA], à parte do resto (avatares, emojis, banners): a retenção
     * varre só ela. O nome do arquivo continua sendo o HMAC do nome, então a área não revela a que mensagem pertence.
     */
    private fun fileFor(name: String): Path =
        if (name.startsWith(MESSAGE_MEDIA_PREFIX)) Path(directory, MESSAGE_AREA, hashOf(name)) else Path(directory, hashOf(name))

    private fun hashOf(name: String): String = crypto.hmacSha256(key, name.encodeToByteArray()).toHex()

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
            val target = fileFor(name)
            val folder = target.parent ?: Path(directory)
            SystemFileSystem.createDirectories(folder)
            val temp = Path(folder.toString(), target.name + ".tmp")
            SystemFileSystem.sink(temp).buffered().use { it.write(crypto.seal(key, bytes, aad = name.encodeToByteArray())) }
            SystemFileSystem.atomicMove(temp, target)
        }
    }

    companion object {
        /** Quanto tempo uma busca que falhou não é tentada de novo. */
        internal val FAILURE_WINDOW = 30.seconds

        /** Subpasta, dentro da pasta da conta, da mídia de mensagem (nomes com [MESSAGE_MEDIA_PREFIX]). */
        internal const val MESSAGE_AREA = "msg"

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
