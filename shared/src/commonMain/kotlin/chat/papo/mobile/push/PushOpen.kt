package chat.papo.mobile.push

/**
 * A conta que abre o toque numa notificação de push, entre as contas deste aparelho que têm a conversa ([holders], na
 * ordem das contas). Nenhuma: null, e o app só abre.
 *
 * Limitação do que o servidor manda: o push traz só o id do canal, sem o servidor nem o usuário, e o mesmo id em outro
 * servidor é outra conversa (regra 4). O que se sabe é que uma conta daqui tem uma conversa com aquele id; com mais de uma
 * conta, o app não tem como saber de qual veio o push. Escolhe a conta ativa, se ela é uma delas, e senão a primeira, e
 * isso pode abrir a conversa da conta errada. Só vale entre as contas locais: nada vai ao servidor para decidir.
 */
fun accountForPush(holders: List<String>, activeId: String?): String? =
    holders.firstOrNull { it == activeId } ?: holders.firstOrNull()
