package chat.papo.mobile.ui.chat

import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.ConversationAccess
import chat.papo.mobile.chat.Message

/*
 * O que a conversa oferece a quem a usa, dado o [ConversationAccess]. A tela só consulta estas funções: assim a
 * regra "o que aparece com cada permissão" fica fora do Compose e pode ser testada. Oferecer não é garantir: o
 * servidor continua sendo a autoridade e recusa com 403 o que o app ofereceu por não saber.
 */

/**
 * As ações da folha de uma mensagem.
 *
 * - [react]: reações rápidas e o "+" que abre o seletor (reagir pede `send_messages`);
 * - [reply]: "Responder" (pede enviar);
 * - [pin]: fixar e desafixar (`pin_message`, ou membro da DM);
 * - [edit]: só a própria mensagem (o servidor só deixa o autor, sem olhar envio nem leitura);
 * - [delete]: a própria, ou a de outra pessoa com `delete_messages` (a de webhook entra: não tem autor);
 * - [text]: copiar e selecionar o texto, que só pedem ler a mensagem.
 */
data class MessageActionOffer(
    val react: Boolean,
    val reply: Boolean,
    val pin: Boolean,
    val edit: Boolean,
    val delete: Boolean,
    val text: Boolean,
) {
    /** Há ao menos uma ação: a folha sem nenhuma não abre. */
    val any: Boolean get() = react || reply || pin || edit || delete || text
}

/**
 * As ações que [access] deixa oferecer para [message] a quem usa a conta [me]. A mensagem é minha se o autor sou
 * eu; a de webhook não tem autor e nunca é.
 */
fun messageActionOffer(access: ConversationAccess, message: Message, me: String): MessageActionOffer {
    val mine = message.authorId == me
    return MessageActionOffer(
        react = access.react,
        reply = access.send,
        pin = access.pin,
        edit = mine,
        delete = mine || access.deleteOthers,
        text = message.content.isNotBlank(),
    )
}

/**
 * Se a bolinha de uma reação responde ao toque. Com [canReact] qualquer uma (soma ou tira a minha); sem ele, só a
 * reação [mine] e só se [canRemoveOwn] (ler basta para tirar a própria, então um canal somente leitura ainda deixa).
 */
fun reactionToggleable(canReact: Boolean, canRemoveOwn: Boolean, mine: Boolean): Boolean = canReact || (mine && canRemoveOwn)

/**
 * Se o compositor aparece. Sem envio entra a barra de somente leitura, menos ao editar a própria mensagem: editar
 * não depende de enviar, e o compositor é onde se edita.
 */
fun composerOffered(access: ConversationAccess, editing: Boolean): Boolean = access.send || editing

/**
 * Se o botão das mensagens fixadas aparece: nunca sem leitura, em DM sempre, e em canal só quando o canal é
 * conhecido e não é uma categoria ([channelType] nulo é canal que ainda não chegou).
 */
fun pinnedListOffered(access: ConversationAccess, isDm: Boolean, channelType: ChannelType?): Boolean =
    !access.readDenied && (isDm || (channelType != null && channelType != ChannelType.Category))
