package chat.papo.mobile.chat

import chat.papo.mobile.data.db.account.MetaQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * As configurações de notificação de uma conta: o geral do servidor, as de cada canal e as de cada DM. Ficam na
 * tabela `meta` do banco cifrado da conta, uma chave `notif:...` por entrada, então não saem do aparelho nem se
 * misturam entre contas. Quem as lê e as muda é o escopo da sessão, que é também o dono do relógio dos silêncios.
 *
 * Os valores mudam na hora, na memória; a gravação vem logo depois, uma por vez e sempre do valor mais novo.
 * Um silêncio com fim some sozinho quando o fim chega, e os fluxos emitem de novo, sem consultar a rede.
 * Use os setters de uma thread só, como o resto do [ChatStore].
 *
 * Só o nível tem lado no servidor (`notification_settings` de cada canal, em [levelsOnServer]); silêncio e supressão
 * existem só aqui. Um canal sincronizado (a opção do canal, senão a do servidor; ligada por padrão) usa o nível do
 * servidor e o grava lá ([setChannelLevel]); sem sincronização o nível é deste aparelho. Ligar ou desligar a
 * sincronização não muda o nível de nenhum canal na hora: o que mudaria é copiado ([setChannelSync], [setServerSync]).
 */
class NotificationPrefs(
    private val meta: MetaQueries,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val levelsOnServer: ServerChannelLevels = NoServerChannelLevels,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val _server = MutableStateFlow(ServerNotificationSettings())
    private val _channels = MutableStateFlow<Map<String, ChannelNotificationSettings>>(emptyMap())
    private val _dms = MutableStateFlow<Map<String, DmNotificationSettings>>(emptyMap())
    private val _loaded = MutableStateFlow(false)

    /** O geral da conta. */
    val server: StateFlow<ServerNotificationSettings> = _server.asStateFlow()

    /** Só os canais que escolheram algo; quem segue o servidor em tudo não aparece. */
    val channels: StateFlow<Map<String, ChannelNotificationSettings>> = _channels.asStateFlow()

    /** Só as DMs que escolheram algo. */
    val dms: StateFlow<Map<String, DmNotificationSettings>> = _dms.asStateFlow()

    /** O nível que o servidor guarda para cada canal; muda quando os canais são relidos ou uma gravação vale. */
    val serverLevels: StateFlow<Map<String, NotificationLevel>> get() = levelsOnServer.levels

    /** A gravação do nível no servidor: qual canal está sendo gravado e a última falha. */
    val levelSave: StateFlow<LevelSave> get() = levelsOnServer.save

    /** Verdadeiro quando o que estava no banco já foi lido (ou a leitura falhou e valem os padrões). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Uma gravação por vez: duas em paralelo poderiam terminar fora de ordem. */
    private val writeLock = Mutex()

    /** Chaves que o usuário mexeu; o que vem do banco não as sobrescreve se a leitura terminar depois. */
    private val touched = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch { load() }
        scope.launch { watchExpiry() }
    }

    fun updateServer(transform: (ServerNotificationSettings) -> ServerNotificationSettings) {
        touch(SERVER_KEY)
        _server.update(transform)
        save(SERVER_KEY) { _server.value.takeUnless { it == ServerNotificationSettings() }?.let(::encodeServer) }
    }

    /** Muda o canal; se o resultado segue o servidor em tudo, a entrada some. */
    fun updateChannel(channelId: String, transform: (ChannelNotificationSettings) -> ChannelNotificationSettings) {
        val key = channelKey(channelId)
        touch(key)
        _channels.update { it.with(channelId, transform(it[channelId] ?: ChannelNotificationSettings()), ChannelNotificationSettings::isDefault) }
        save(key) { _channels.value[channelId]?.let(::encodeChannel) }
    }

    /** Muda a DM; se o resultado segue o servidor em tudo, a entrada some. */
    fun updateDm(dmId: String, transform: (DmNotificationSettings) -> DmNotificationSettings) {
        val key = dmKey(dmId)
        touch(key)
        _dms.update { it.with(dmId, transform(it[dmId] ?: DmNotificationSettings()), DmNotificationSettings::isDefault) }
        save(key) { _dms.value[dmId]?.let(::encodeDm) }
    }

    /** O que vale para o canal agora. Quem desenha deve ler também os fluxos, para refazer a conta quando algo mudar. */
    fun effectiveFor(channelId: String): EffectiveNotifications =
        effective(_server.value, _channels.value[channelId] ?: ChannelNotificationSettings(), now(), serverLevelFor(channelId))

    /** O que vale para a DM agora. */
    fun effectiveForDm(dmId: String): EffectiveDm =
        effectiveDm(_server.value, _dms.value[dmId] ?: DmNotificationSettings(), now())

    /** O nível do canal no servidor; sem valor guardado, "só menções" (o padrão do servidor). */
    private fun serverLevelFor(channelId: String): NotificationLevel =
        levelsOnServer.levels.value[channelId] ?: NotificationLevel.OnlyMentions

    /** O nível do canal vai e vem do servidor? */
    fun isSyncing(channelId: String): Boolean = effectiveFor(channelId).sync

    /**
     * Escolhe o nível do canal. Sincronizado, grava no servidor (o resultado aparece em [levelSave] e o nível só muda
     * quando o servidor confirma) e [level] nulo não faz nada; sem sincronização, vale na hora neste aparelho e
     * nulo é "Usar padrão do servidor".
     */
    fun setChannelLevel(channelId: String, level: NotificationLevel?) {
        if (effectiveFor(channelId).sync) {
            if (level != null) levelsOnServer.set(channelId, level)
        } else {
            updateChannel(channelId) { it.copy(level = level) }
        }
    }

    /**
     * Liga ou desliga a sincronização do canal ([value] nulo segue o servidor) sem mudar o nível de agora. Desligar
     * guarda neste aparelho o nível que o servidor tinha; ligar larga o nível do aparelho e volta ao do servidor, sem
     * mandar o do aparelho para lá.
     */
    fun setChannelSync(channelId: String, value: Boolean?) {
        val before = effectiveFor(channelId)
        val willSync = value ?: _server.value.sync
        updateChannel(channelId) {
            it.copy(sync = value, level = if (willSync) null else if (before.sync) before.level else it.level)
        }
    }

    /**
     * Liga ou desliga a sincronização do servidor inteiro. Nenhum canal muda de nível por isso: os que seguem o servidor
     * e mudariam guardam neste aparelho o nível que tinham (e, se passariam a sincronizar com outro nível, ficam sem
     * sincronização, porque ligar nunca manda o nível do aparelho ao servidor).
     */
    fun setServerSync(value: Boolean) {
        val ids = levelsOnServer.levels.value.keys + _channels.value.keys
        val before = ids.associateWith(::effectiveFor)
        updateServer { it.copy(sync = value) }
        for ((id, was) in before) {
            val after = effectiveFor(id)
            if (after.level == was.level) {
                // Sincronizado, o nível do aparelho não vale mais: não deixa um valor velho guardado.
                if (after.sync && _channels.value[id]?.level != null) updateChannel(id) { it.copy(level = null) }
            } else {
                updateChannel(id) { it.copy(sync = if (after.sync) false else it.sync, level = was.level) }
            }
        }
    }

    /** Esquece a última falha da gravação do nível (a folha que a mostrava foi fechada). */
    fun clearLevelFailure() = levelsOnServer.clearFailure()

    private fun touch(key: String) = touched.update { it + key }

    private fun <V> Map<String, V>.with(id: String, value: V, isDefault: (V) -> Boolean): Map<String, V> =
        if (isDefault(value)) this - id else this + (id to value)

    private fun save(key: String, encoded: () -> String?) {
        scope.launch(io) {
            writeLock.withLock {
                try {
                    val value = encoded()
                    if (value == null) meta.remove(key) else meta.put(key, value)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A configuração é um conforto: se o banco falhou, o valor segue valendo na memória e vai
                    // para o banco na próxima mudança da mesma chave.
                }
            }
        }
    }

    private suspend fun load() {
        val stored = try {
            withContext(io) { readAll() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // sem ler o banco valem os padrões; o que o usuário mudar daqui para frente é gravado
        }
        if (stored != null) {
            // O que o usuário mudou antes de a leitura terminar vale mais que o banco, inclusive voltar ao padrão.
            // `touched` é lido dentro do `update`, que repete a conta se algo mudar no meio.
            stored.server?.let { fromDb -> _server.update { if (SERVER_KEY in touched.value) it else fromDb } }
            _channels.update { cur -> stored.channels.filterKeys { channelKey(it) !in touched.value } + cur }
            _dms.update { cur -> stored.dms.filterKeys { dmKey(it) !in touched.value } + cur }
        }
        _loaded.value = true
        pruneExpired(now())
    }

    private fun readAll(): Stored {
        var server: ServerNotificationSettings? = null
        val channels = HashMap<String, ChannelNotificationSettings>()
        val dms = HashMap<String, DmNotificationSettings>()
        for (key in meta.keysWithPrefix(PREFIX).executeAsList()) {
            val value = meta.get(key).executeAsOneOrNull()
            when {
                key == SERVER_KEY -> server = decodeServer(value)
                key.startsWith(CHANNEL_PREFIX) -> decodeChannel(value)?.let { channels[key.removePrefix(CHANNEL_PREFIX)] = it }
                key.startsWith(DM_PREFIX) -> decodeDm(value)?.let { dms[key.removePrefix(DM_PREFIX)] = it }
                // Outra chave com o prefixo (de outra versão): não é desta leitura.
            }
        }
        return Stored(server, channels, dms)
    }

    /** Espera o fim do silêncio mais próximo e o tira. Recomeça sempre que as configurações mudam. */
    private suspend fun watchExpiry() {
        combine(_server, _channels, _dms, ::nearestEnd).distinctUntilChanged().collectLatest { end ->
            if (end == null) return@collectLatest
            // Só sai do laço quando o relógio de parede chegou ao fim: o `delay` não sabe se ele foi acertado.
            while (true) {
                val wait = end - now()
                if (!wait.isPositive()) break
                delay(wait)
            }
            pruneExpired(now())
        }
    }

    /** Tira os silêncios que acabaram até [at] e grava o que mudou. */
    private fun pruneExpired(at: Instant) {
        val oldServer = _server.getAndUpdate { if (it.mute.isExpired(at)) it.copy(mute = null) else it }
        val oldChannels = _channels.getAndUpdate { map ->
            map.mapNotNull { (id, value) ->
                if (value.mute.isExpired(at)) value.copy(mute = null).takeUnless { it.isDefault }?.let { id to it } else id to value
            }.toMap()
        }
        val oldDms = _dms.getAndUpdate { map ->
            map.mapNotNull { (id, value) ->
                if (value.mute.isExpired(at)) value.copy(mute = null).takeUnless { it.isDefault }?.let { id to it } else id to value
            }.toMap()
        }
        if (oldServer.mute.isExpired(at)) save(SERVER_KEY) { _server.value.takeUnless { it == ServerNotificationSettings() }?.let(::encodeServer) }
        val expiredChannels = oldChannels.filterValues { it.mute.isExpired(at) }.keys
        for (id in expiredChannels) save(channelKey(id)) { _channels.value[id]?.let(::encodeChannel) }
        val expiredDms = oldDms.filterValues { it.mute.isExpired(at) }.keys
        for (id in expiredDms) save(dmKey(id)) { _dms.value[id]?.let(::encodeDm) }
    }

    private class Stored(
        val server: ServerNotificationSettings?,
        val channels: Map<String, ChannelNotificationSettings>,
        val dms: Map<String, DmNotificationSettings>,
    )

    internal companion object {
        private const val PREFIX = "notif:"
        const val SERVER_KEY = PREFIX + "server"
        private const val CHANNEL_PREFIX = PREFIX + "channel:"
        private const val DM_PREFIX = PREFIX + "dm:"

        fun channelKey(channelId: String) = CHANNEL_PREFIX + channelId
        fun dmKey(dmId: String) = DM_PREFIX + dmId

        /** Estrito no que escreve, mas tolerante com campo novo: um valor gravado por outra versão ainda abre. */
        private val json = Json { ignoreUnknownKeys = true }

        /** O fim mais próximo entre todos os silêncios com fim, ou null se não há nenhum. */
        fun nearestEnd(
            server: ServerNotificationSettings,
            channels: Map<String, ChannelNotificationSettings>,
            dms: Map<String, DmNotificationSettings>,
        ): Instant? = (listOf(server.mute) + channels.values.map { it.mute } + dms.values.map { it.mute })
            .mapNotNull { it?.until }
            .minOrNull()

        private fun Mute?.isExpired(at: Instant): Boolean = this != null && !isActive(at)

        fun encodeServer(value: ServerNotificationSettings): String = json.encodeToString(
            StoredServer.serializer(),
            StoredServer(
                level = value.level.wire,
                suppressEveryone = value.suppressEveryone,
                suppressMentions = value.suppressMentions,
                muted = value.mute != null,
                mutedUntil = value.mute?.until?.toEpochMilliseconds(),
                sync = value.sync,
            ),
        )

        /** O valor guardado em [value], ou null se não há valor ou se ele está estragado (valem os padrões). */
        fun decodeServer(value: String?): ServerNotificationSettings? {
            val stored = parse(StoredServer.serializer(), value) ?: return null
            return ServerNotificationSettings(
                level = NotificationLevel.fromWire(stored.level) ?: ServerNotificationSettings().level,
                suppressEveryone = stored.suppressEveryone,
                suppressMentions = stored.suppressMentions,
                mute = muteOf(stored.muted, stored.mutedUntil),
                sync = stored.sync,
            )
        }

        fun encodeChannel(value: ChannelNotificationSettings): String = json.encodeToString(
            StoredChannel.serializer(),
            StoredChannel(
                level = value.level?.wire,
                suppressEveryone = value.suppressEveryone,
                suppressMentions = value.suppressMentions,
                muted = value.mute != null,
                mutedUntil = value.mute?.until?.toEpochMilliseconds(),
                sync = value.sync,
            ),
        )

        /** O canal guardado em [value], ou null se não há valor, se está estragado ou se segue o servidor em tudo. */
        fun decodeChannel(value: String?): ChannelNotificationSettings? {
            val stored = parse(StoredChannel.serializer(), value) ?: return null
            return ChannelNotificationSettings(
                level = NotificationLevel.fromWire(stored.level),
                suppressEveryone = stored.suppressEveryone,
                suppressMentions = stored.suppressMentions,
                mute = muteOf(stored.muted, stored.mutedUntil),
                sync = stored.sync,
            ).takeUnless { it.isDefault }
        }

        fun encodeDm(value: DmNotificationSettings): String = json.encodeToString(
            StoredDm.serializer(),
            StoredDm(muted = value.mute != null, mutedUntil = value.mute?.until?.toEpochMilliseconds()),
        )

        fun decodeDm(value: String?): DmNotificationSettings? {
            val stored = parse(StoredDm.serializer(), value) ?: return null
            return DmNotificationSettings(muteOf(stored.muted, stored.mutedUntil)).takeUnless { it.isDefault }
        }

        private fun <T> parse(serializer: kotlinx.serialization.KSerializer<T>, value: String?): T? {
            if (value == null) return null
            return try {
                json.decodeFromString(serializer, value)
            } catch (_: IllegalArgumentException) {
                null // valor estragado (ou de outra versão): ignora e começa pelo padrão
            }
        }

        /** Sem `muted` não há silêncio, qualquer que seja o fim guardado; `muted` sem fim é "até reativar". */
        private fun muteOf(muted: Boolean, untilMillis: Long?): Mute? =
            if (muted) Mute(untilMillis?.let(Instant::fromEpochMilliseconds)) else null
    }

    @Serializable
    private class StoredServer(
        val level: String? = null,
        val suppressEveryone: Boolean = false,
        val suppressMentions: Boolean = false,
        val muted: Boolean = false,
        val mutedUntil: Long? = null,
        val sync: Boolean = true,
    )

    @Serializable
    private class StoredChannel(
        val level: String? = null,
        val suppressEveryone: Boolean? = null,
        val suppressMentions: Boolean? = null,
        val muted: Boolean = false,
        val mutedUntil: Long? = null,
        val sync: Boolean? = null,
    )

    @Serializable
    private class StoredDm(
        val muted: Boolean = false,
        val mutedUntil: Long? = null,
    )
}
