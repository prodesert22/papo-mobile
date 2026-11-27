package chat.papo.mobile.voice

/**
 * Como a tela oferece a troca de saída: [Toggle] alterna entre alto-falante e fone do aparelho (as duas únicas saídas),
 * [Picker] abre a lista quando há outra saída, e [None] quando não há o que trocar (tablet só com alto-falante, ou antes de abrir).
 */
enum class OutputControl { None, Toggle, Picker }

fun outputControlFor(outputs: List<AudioOutput>): OutputControl {
    val builtIn = outputs.count { it.kind == AudioOutputKind.Speaker || it.kind == AudioOutputKind.Earpiece }
    return when {
        outputs.size > builtIn -> OutputControl.Picker
        builtIn >= 2 -> OutputControl.Toggle
        else -> OutputControl.None
    }
}

/** Para onde o botão de alternar leva: do alto-falante ao fone do aparelho e de qualquer outra saída ao alto-falante. */
fun toggleTarget(outputs: List<AudioOutput>, current: AudioOutput?): AudioOutput? {
    val wanted = if (current?.kind == AudioOutputKind.Speaker) AudioOutputKind.Earpiece else AudioOutputKind.Speaker
    return outputs.firstOrNull { it.kind == wanted }
}

/**
 * Decide em qual saída a chamada toca, sem tocar no sistema: o motor da plataforma conta as saídas que o aparelho oferece
 * a cada mudança e aplica o que sair daqui. Uma instância vale por uma chamada.
 *
 * - no início: fone (Bluetooth ou aparelho auditivo, depois fio ou USB) se houver; senão o alto-falante, como numa chamada de vídeo;
 * - um fone que aparece agora passa a ser a saída, mesmo depois de uma escolha anterior (quem liga o fone quer ouvir nele);
 * - a escolha da pessoa vale enquanto o aparelho existir;
 * - a saída em uso some: outro fone que ainda esteja ligado; senão o fone do aparelho, como o discador, para não espalhar a
 *   voz da chamada; sem ele (tablet), o alto-falante.
 *
 * Não é seguro entre threads: o motor chama sempre sob a mesma trava.
 */
class AudioOutputPolicy {
    private var started = false
    private var seen: Set<String> = emptySet()
    private var used: String? = null

    /** A saída em que a chamada deve tocar com a lista [available]; null só se a lista está vazia. Idempotente. */
    fun decide(available: List<AudioOutput>): AudioOutput? {
        val ids = available.mapTo(HashSet()) { it.id }
        val appeared = available.filter { it.id !in seen && headsetRank(it) > 0 }
        val first = !started
        // Uma lista vazia (o sistema ainda não listou nada) não conta como o início.
        if (available.isNotEmpty()) started = true
        seen = ids

        val next = when {
            available.isEmpty() -> null
            first -> bestHeadset(available) ?: speakerFirst(available)
            appeared.isNotEmpty() -> bestHeadset(appeared)
            else -> available.firstOrNull { it.id == used } ?: bestHeadset(available) ?: earpieceFirst(available)
        }
        used = next?.id
        return next
    }

    /** A pessoa escolheu [id]; devolve a saída se ela existe em [available], senão nada muda. */
    fun choose(id: String, available: List<AudioOutput>): AudioOutput? {
        val picked = available.firstOrNull { it.id == id } ?: return null
        used = picked.id
        return picked
    }

    private fun speakerFirst(available: List<AudioOutput>): AudioOutput? =
        available.firstOrNull { it.kind == AudioOutputKind.Speaker }
            ?: available.firstOrNull { it.kind == AudioOutputKind.Earpiece }
            ?: available.firstOrNull()

    private fun earpieceFirst(available: List<AudioOutput>): AudioOutput? =
        available.firstOrNull { it.kind == AudioOutputKind.Earpiece }
            ?: available.firstOrNull { it.kind == AudioOutputKind.Speaker }
            ?: available.firstOrNull()

    private fun bestHeadset(list: List<AudioOutput>): AudioOutput? = list.filter { headsetRank(it) > 0 }.maxByOrNull(::headsetRank)

    /** Maior é melhor; 0 não é fone e nunca vira saída sozinho. */
    private fun headsetRank(output: AudioOutput): Int = when (output.kind) {
        AudioOutputKind.Bluetooth, AudioOutputKind.HearingAid -> 2
        AudioOutputKind.Wired, AudioOutputKind.Usb -> 1
        AudioOutputKind.Earpiece, AudioOutputKind.Speaker, AudioOutputKind.Other -> 0
    }
}
