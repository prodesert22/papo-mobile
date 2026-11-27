package chat.papo.mobile.chat

import chat.papo.mobile.core.MutationResult

/** Tamanho máximo do nome de um canal, em pontos de código (o servidor conta runas: `utf8.RuneCountInString`). */
const val MAX_CHANNEL_NAME = 32

/** Tamanho máximo da descrição (`topic`) de um canal, contado como o nome. */
const val MAX_CHANNEL_TOPIC = 512

/** Por que um nome de canal não pode ser enviado. */
enum class ChannelNameProblem {
    /** Vazio, ou só espaços: o servidor exige de 1 a 32 caracteres e o app não cria um nome invisível. */
    Empty,

    /** Passa de [MAX_CHANNEL_NAME] caracteres. */
    TooLong,
}

/** O que o campo de nome precisa ouvir antes de salvar, sobre o nome já sem espaços nas pontas. Nulo: serve. */
fun channelNameProblem(name: String): ChannelNameProblem? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> ChannelNameProblem.Empty
        codePointLength(trimmed) > MAX_CHANNEL_NAME -> ChannelNameProblem.TooLong
        else -> null
    }
}

/** Se a descrição, já sem espaços nas pontas, cabe no limite do servidor. Vazia serve: é como se apaga. */
fun channelTopicFits(topic: String): Boolean = codePointLength(topic.trim()) <= MAX_CHANNEL_TOPIC

/** Quantos pontos de código [text] tem: um emoji fora do plano básico vale um, não dois. */
fun codePointLength(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        index += if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/**
 * O que a edição do canal rendeu. Nenhuma falha é repetida sozinha (regra 15): cada uma pede uma ação da pessoa, e
 * [Failed] com [MutationResult.Uncertain] pede conferir o canal antes de tentar de novo.
 */
sealed interface ChannelEditResult {
    data object Saved : ChannelEditResult

    /** 409: já existe um canal com esse nome. */
    data object NameTaken : ChannelEditResult

    /** 403: a conta não pode editar canais (ou deixou de poder); as permissões foram relidas. */
    data object Forbidden : ChannelEditResult

    /** 404: o canal não existe mais; ele saiu do aparelho. */
    data object NotFound : ChannelEditResult

    /** O resto: limite de taxa, sem conexão, sessão encerrada, resultado incerto ou outra recusa. */
    data class Failed(val result: MutationResult) : ChannelEditResult
}

/** Traduz o resultado de [chat.papo.mobile.core.runMutation] e o status HTTP da recusa, se houve, no que a tela mostra. */
fun channelEditResult(result: MutationResult, status: Int?): ChannelEditResult = when (result) {
    MutationResult.Done -> ChannelEditResult.Saved
    is MutationResult.Rejected -> when (status) {
        409 -> ChannelEditResult.NameTaken
        403 -> ChannelEditResult.Forbidden
        404 -> ChannelEditResult.NotFound
        else -> ChannelEditResult.Failed(result)
    }
    MutationResult.RateLimited, MutationResult.NotSent, MutationResult.Unauthorized, MutationResult.Uncertain ->
        ChannelEditResult.Failed(result)
}
