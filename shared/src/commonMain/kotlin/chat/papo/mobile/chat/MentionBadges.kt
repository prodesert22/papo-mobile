package chat.papo.mobile.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Menção e resposta valem como menção; `@everyone` tem a supressão própria. Conversa direta não passa por aqui. */
fun TriggerKind.toMessageKind(): MessageKind = when (this) {
    TriggerKind.Mention, TriggerKind.Reply -> MessageKind.Mention
    TriggerKind.Everyone -> MessageKind.Everyone
    TriggerKind.Dm -> MessageKind.Plain
}

/** O que as menções de uma ou mais contas somam; [capped] diz que alguma caixa foi lida só em parte. */
data class MentionTotal(val count: Int = 0, val capped: Boolean = false)

/** A soma de contagens de contas diferentes. Cada conta entra só com o que é dela; nada aqui mistura linhas. */
fun totalOf(counts: Collection<MentionCounts>): MentionTotal =
    MentionTotal(counts.sumOf { it.total }, counts.any { it.capped })

/**
 * As menções não lidas de uma conta, por canal e por conversa direta, e o total. Junta a caixa de notificações, as
 * conversas diretas, os canais que a lista mostra e as configurações: conta menção e resposta se "Suprimir menções"
 * está desligado, e `@everyone` se "Suprimir @everyone" está desligado. O nível e o silêncio não mudam o número, e
 * uma conversa direta conta o `unread_count` do servidor mesmo silenciada.
 *
 * Só entram os canais de [channels] (os que a conta lê e a lista mostra): uma linha de um canal que ela não vê, ou de
 * uma conversa direta fechada, não conta. Quando as configurações mudam, o número é refeito.
 */
class MentionBadges(
    private val userId: String,
    inbox: StateFlow<InboxState>,
    dms: Flow<List<Dm>>,
    channels: Flow<List<Channel>>,
    private val prefs: NotificationPrefs,
    scope: CoroutineScope,
) {
    private val snapshot: StateFlow<Snapshot> = combine(inbox, dms, channels, prefs.server, prefs.channels) { box, dmList, visible, _, _ ->
        val shown = visible.mapTo(HashSet()) { it.id }
        val counts = mentionCounts(box.items, dmList, userId, box.capped) { channelId, kind ->
            channelId in shown && countsAsMention(kind.toMessageKind(), prefs.effectiveFor(channelId))
        }
        Snapshot(counts, box.loaded)
    }.stateIn(scope, SharingStarted.Eagerly, Snapshot(MentionCounts(emptyMap(), emptyMap(), 0, false), loaded = false))

    /** As contagens de agora. */
    val mentions: StateFlow<MentionCounts> = snapshot.map { it.counts }.stateIn(scope, SharingStarted.Eagerly, snapshot.value.counts)

    /**
     * Os canais com menção contada, depois que a caixa foi lida ao menos uma vez; nulo antes disso, quando ainda não se
     * sabe (a troca rápida usa a heurística antiga até lá).
     */
    val mentionedChannels: StateFlow<Set<String>?> = snapshot
        .map { if (it.loaded) it.counts.perChannel.keys.toSet() else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private class Snapshot(val counts: MentionCounts, val loaded: Boolean)
}
