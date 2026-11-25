package chat.papo.mobile.chat

/*
 * Regra de permissões do servidor, lida no código do backend (`internal/services/messages.go`,
 * `reactions.go`, `voice.go`, `notifications.go` e `internal/middleware/permissions.go`) e **não observada
 * em execução**. A OpenAPI não a descreve: só a busca a insinua (`openapi.yml`, `POST /search`:
 * "dono do servidor, canais abertos sem permissões definidas ou canais em que uma das suas roles tem
 * read_channel"). Por isso o desconhecido sempre se comporta como antes das permissões: o app oferece a
 * ação e o servidor, que é a autoridade, recusa com 403.
 *
 * Duas famílias de flag, que se combinam de formas diferentes:
 *
 * - Flags de **cargo** ([RoleFlag]): OU entre os cargos do usuário. O dono do servidor (`owner_id` de
 *   `GET /server`) tem todas. Não existe cargo padrão: quem não tem cargo não tem flag nenhuma.
 * - Flags de **canal** ([ChannelFlag]): entradas por cargo em `permissions` de `GET /channels`, em canal de
 *   texto ou de voz (DMs têm outra regra, abaixo). A ordem de avaliação do servidor é:
 *   1. dono do servidor: sim, em tudo;
 *   2. canal **aberto** (nenhuma entrada): leitura, envio e voz são livres para todos; **excluir não**
 *      (precisa de entrada explícita, só o dono passa);
 *   3. caso contrário, a união das entradas dos cargos do usuário: sim se alguma tem a flag. Não há
 *      negação explícita, e cargo do usuário sem entrada no canal não concede nada.
 *
 * Por ação (canal; DM entre parênteses quando difere):
 *
 * | Ação                    | Regra do servidor                                                         |
 * |-------------------------|---------------------------------------------------------------------------|
 * | Ler mensagens           | `read_channel` (DM: ser membro e não estar bloqueado)                     |
 * | Enviar                  | `send_messages` (DM: membro e não bloqueado; bloqueio responde `dm-blocked`) |
 * | Enviar anexo            | envio + `send_attachment` de cargo, **só fora de DM** (em DM não se olha) |
 * | Editar                  | só o autor, sem olhar envio nem leitura                                   |
 * | Excluir a própria       | sempre; a de outro: `delete_messages` de canal (DM: **nunca**, nem o dono) |
 * | Fixar                   | `pin_message` de cargo, sem olhar leitura (DM: ser membro)                |
 * | Desafixar               | leitura + `pin_message` (DM: leitura)                                     |
 * | Reagir                  | `send_messages`                                                           |
 * | Tirar a própria reação  | `read_channel`                                                            |
 * | Entrar na chamada       | `connect_voice`, livre em canal aberto; o WebSocket recusa com `voice-forbidden` |
 * | `@everyone`             | `everyone_message` de cargo do **autor** (dono incluso), nunca webhook    |
 *
 * `GET /channels` lista todos os canais que não são DM, **sem filtrar leitura**, com as entradas de cada
 * canal (e até a `last_message` de canal que o usuário não lê): quem esconde é o app ([visibleChannels]).
 */

/**
 * Resposta a "o usuário pode?". [Unknown] quando alguma fonte ainda não chegou (dono, cargos, entradas do
 * canal ou cargos do usuário): a tela oferece a ação e o servidor decide. Só [Denied] esconde ou bloqueia.
 */
enum class Decision { Allowed, Denied, Unknown }

/** As oito flags de cargo do servidor (`RolePermissions`). O app só as lê; administrar cargos é de outro item. */
enum class RoleFlag {
    ManageServer,
    ManageChannels,
    ManageRoles,
    ManageWebhooks,
    BanMembers,
    PinMessage,
    EveryoneMessage,
    SendAttachment,
}

/** As quatro flags que um cargo pode ter em um canal (`ChannelPermissions`). */
enum class ChannelFlag { Read, Send, Delete, Voice }

/** As flags que um cargo tem ligadas; as que não estão em [granted] estão desligadas. */
data class RoleFlags(val granted: Set<RoleFlag> = emptySet())

/** Entrada de um canal para o cargo [roleId]: o que ele pode no canal. */
data class ChannelGrant(
    val roleId: String,
    val read: Boolean,
    val send: Boolean,
    val delete: Boolean,
    val voice: Boolean,
) {
    /** A entrada vale para quem tem o cargo [roleId] entre os cargos [roles] e dá a flag [flag]. */
    fun grantsTo(roles: Set<String>, flag: ChannelFlag): Boolean = roleId in roles && when (flag) {
        ChannelFlag.Read -> read
        ChannelFlag.Send -> send
        ChannelFlag.Delete -> delete
        ChannelFlag.Voice -> voice
    }
}

/**
 * Tudo o que o app sabe, vindo de fontes diferentes, para decidir permissões de uma conta. Cada fonte pode
 * faltar (nulo ou falso) e, sem ela, a resposta é [Decision.Unknown], nunca [Decision.Denied]: esconder
 * por falta de dado é pior que oferecer e levar um 403.
 *
 * - [ownerId] e [ownerKnown]: `owner_id` de `GET /server`. [ownerKnown] diz que a resposta chegou, e
 *   [ownerId] pode ser nulo mesmo assim (servidor sem dono: ninguém é dono).
 * - [roles]: cargos e flags, de `GET /roles`; nulo até a primeira resposta. Um cargo de [userRoles] que não
 *   está aqui não tem definição (foi criado agora, ou apagado): [Decision.Unknown].
 * - [userRoles]: ids dos cargos de cada usuário, de `GET /users` e dos eventos `role_add`/`role_remove`.
 *   Usuário ausente só vale "sem cargos" quando [usersComplete], ou seja, quando todas as páginas de
 *   `GET /users` chegaram.
 * - [grants]: entradas por canal, de `GET /channels`; nulo até a primeira resposta. Canal ausente é
 *   desconhecido; lista vazia é canal aberto.
 *
 * Esta é a regra de decisão: [role] e [channel].
 */
data class PermissionSnapshot(
    val ownerId: String? = null,
    val ownerKnown: Boolean = false,
    val roles: Map<String, RoleFlags>? = null,
    val userRoles: Map<String, Set<String>> = emptyMap(),
    val usersComplete: Boolean = false,
    val grants: Map<String, List<ChannelGrant>>? = null,
) {
    /**
     * Se [userId] tem a flag de cargo [flag]: o dono sempre; cargos do usuário ou catálogo desconhecidos,
     * ou cargo sem definição, [Decision.Unknown]; algum cargo com a flag, [Decision.Allowed]; senão
     * [Decision.Denied] (só com o dono conhecido).
     */
    fun role(userId: String, flag: RoleFlag): Decision {
        if (isOwner(userId)) return Decision.Allowed
        val catalog = roles ?: return Decision.Unknown
        val mine = rolesOf(userId) ?: return Decision.Unknown
        var undefined = false
        for (roleId in mine) {
            val flags = catalog[roleId]
            if (flags == null) undefined = true else if (flag in flags.granted) return Decision.Allowed
        }
        // Sem a flag em nenhum cargo definido: só se nega com certeza se nenhum cargo ficou de fora e o dono é conhecido.
        return if (undefined || !ownerKnown) Decision.Unknown else Decision.Denied
    }

    /**
     * Se [userId] tem a flag de canal [flag] no canal [channelId], que não é uma DM: canal sem entradas
     * conhecidas, [Decision.Unknown]; dono, [Decision.Allowed]; canal aberto e [flag] diferente de
     * [ChannelFlag.Delete], [Decision.Allowed]; união das entradas dos cargos do usuário com a flag,
     * [Decision.Allowed] (com os cargos do usuário desconhecidos, [Decision.Unknown]); senão
     * [Decision.Denied], só com o dono conhecido.
     */
    fun channel(userId: String, channelId: String, flag: ChannelFlag): Decision {
        val entries = grants?.get(channelId) ?: return Decision.Unknown
        if (isOwner(userId)) return Decision.Allowed
        if (entries.isEmpty()) return if (flag != ChannelFlag.Delete) Decision.Allowed else withoutGrant()
        val mine = rolesOf(userId) ?: return Decision.Unknown
        if (entries.any { it.grantsTo(mine, flag) }) return Decision.Allowed
        return withoutGrant()
    }

    /** Nenhuma concessão: negar exige saber que o usuário não é o dono, que passaria por cima de tudo. */
    private fun withoutGrant(): Decision = if (ownerKnown) Decision.Denied else Decision.Unknown

    private fun isOwner(userId: String): Boolean = ownerKnown && ownerId == userId

    /** Ids dos cargos do usuário; nulo se não se sabe (usuário fora das páginas lidas de `GET /users`). */
    private fun rolesOf(userId: String): Set<String>? = userRoles[userId] ?: if (usersComplete) emptySet() else null

    companion object {
        /** Nada se sabe ainda: tudo é [Decision.Unknown]. */
        val EMPTY = PermissionSnapshot()
    }
}

/**
 * O que a pessoa pode fazer em uma conversa, já resolvido para a tela. Cada campo é um "oferecer?":
 * [Decision.Unknown] oferece (o servidor decide), exceto três campos que só valem com certeza:
 * [mentionEveryone] e [deleteOthers] (só [Decision.Allowed]: o app nunca ofereceu nem avisou `@everyone`
 * ou excluir a mensagem de outra pessoa) e [readDenied] (só [Decision.Denied]).
 *
 * Em canal: [attach] = envio e `send_attachment`; [react] = envio; [removeOwnReaction] = leitura;
 * [pin] = `pin_message`; [deleteOthers] = `delete_messages` do canal; [joinVoice] = `connect_voice`.
 * Em DM tudo é sim, menos [deleteOthers], [joinVoice] e [mentionEveryone] (em DM todos já são avisados);
 * o anexo em DM não depende de `send_attachment`, e o bloqueio da DM não é conhecido (fica o 403
 * `dm-blocked`).
 */
data class ConversationAccess(
    val read: Boolean,
    val send: Boolean,
    val attach: Boolean,
    val react: Boolean,
    val removeOwnReaction: Boolean,
    val pin: Boolean,
    /** Excluir mensagem de outra pessoa (a própria sempre pode). */
    val deleteOthers: Boolean,
    val joinVoice: Boolean,
    /** Oferecer `@everyone` no autocompletar: só com [Decision.Allowed], nunca por desconhecimento. */
    val mentionEveryone: Boolean,
    /** Certeza de que a leitura foi negada: a conversa fecha e o histórico guardado se apaga. */
    val readDenied: Boolean,
) {
    companion object {
        /**
         * Para quem ainda não tem [PermissionSnapshot]: tudo desconhecido, ou seja, oferece tudo e não
         * esconde nada, menos `@everyone` e excluir mensagem alheia, que exigem certeza. É o que
         * [conversationAccess] devolve para um canal com [PermissionSnapshot.EMPTY].
         */
        val Unrestricted = ConversationAccess(
            read = true,
            send = true,
            attach = true,
            react = true,
            removeOwnReaction = true,
            pin = true,
            deleteOthers = false,
            joinVoice = true,
            mentionEveryone = false,
            readDenied = false,
        )
    }
}

/**
 * O acesso de [me] à conversa [channelId] (uma DM se [isDm]), segundo [snapshot]. Ver [ConversationAccess]
 * para o que cada campo exige.
 */
fun conversationAccess(snapshot: PermissionSnapshot, me: String, channelId: String, isDm: Boolean): ConversationAccess {
    // Em DM a regra do servidor é ser membro e não estar bloqueado; o bloqueio não é conhecido (fica o 403).
    if (isDm) {
        return ConversationAccess(
            read = true,
            send = true,
            attach = true,
            react = true,
            removeOwnReaction = true,
            pin = true,
            deleteOthers = false,
            joinVoice = false,
            mentionEveryone = false,
            readDenied = false,
        )
    }
    val read = snapshot.channel(me, channelId, ChannelFlag.Read)
    val send = snapshot.channel(me, channelId, ChannelFlag.Send)
    val attachment = snapshot.role(me, RoleFlag.SendAttachment)
    return ConversationAccess(
        read = read.offered,
        send = send.offered,
        attach = send.offered && attachment.offered,
        react = send.offered,
        removeOwnReaction = read.offered,
        pin = snapshot.role(me, RoleFlag.PinMessage).offered,
        deleteOthers = snapshot.channel(me, channelId, ChannelFlag.Delete) == Decision.Allowed,
        joinVoice = snapshot.channel(me, channelId, ChannelFlag.Voice).offered,
        mentionEveryone = snapshot.role(me, RoleFlag.EveryoneMessage) == Decision.Allowed,
        readDenied = read == Decision.Denied,
    )
}

/** O que só o [Decision.Denied] tira da tela: o desconhecido se oferece e o servidor decide. */
private val Decision.offered: Boolean get() = this != Decision.Denied

/**
 * Se a mensagem de [authorId] pode ser tratada como `@everyone`: o autor tem `everyone_message` (dono
 * incluso) e a mensagem não veio de webhook. Webhook ([webhookId] não nulo) ou autor nulo nunca; só
 * [Decision.Allowed] vale, nunca desconhecido.
 */
fun authorCanEveryone(snapshot: PermissionSnapshot, authorId: String?, webhookId: String?): Boolean {
    if (webhookId != null || authorId.isNullOrEmpty()) return false
    return snapshot.role(authorId, RoleFlag.EveryoneMessage) == Decision.Allowed
}

/**
 * Os canais que [me] deve ver. Canal de texto fica se a leitura não foi negada; canal de voz, se a leitura
 * **ou** a voz não foi negada (dá para entrar na chamada sem ler o chat). Categoria some só se ficou vazia
 * por causa do filtro; a que já era vazia fica. O desconhecido aparece.
 *
 * Quem pertence à categoria é o que [groupChannels] decide, o mesmo agrupamento da lista: os canais com
 * `parent_id` dela e os antigos, sem ele, que a seguem na ordem. O filtro age dentro desses grupos, como no web.
 * A ordem da lista de entrada é mantida.
 */
fun visibleChannels(channels: List<Channel>, snapshot: PermissionSnapshot, me: String): List<Channel> {
    val emptied = groupChannels(channels).mapNotNullTo(HashSet()) { group ->
        group.category?.id?.takeIf { group.channels.isNotEmpty() && group.channels.none { snapshot.canSee(it, me) } }
    }
    return channels.filter { if (it.type == ChannelType.Category) it.id !in emptied else snapshot.canSee(it, me) }
}

/**
 * Os canais, de texto ou de voz, que [me] pode abrir para ler: a leitura não foi negada. Ao contrário de
 * [visibleChannels], deixa de fora o canal de voz que só dá para entrar na chamada, porque quem o abre vê o
 * chat dele. É o que a troca rápida, o filtro da busca e o ponto de não lida consideram. Categorias não entram.
 */
fun readableChannels(channels: List<Channel>, snapshot: PermissionSnapshot, me: String): List<Channel> =
    channels.filter { it.type != ChannelType.Category && snapshot.channel(me, it.id, ChannelFlag.Read).offered }

private fun PermissionSnapshot.canSee(item: Channel, me: String): Boolean {
    if (item.type == ChannelType.Voice && channel(me, item.id, ChannelFlag.Voice).offered) return true
    return channel(me, item.id, ChannelFlag.Read).offered
}
