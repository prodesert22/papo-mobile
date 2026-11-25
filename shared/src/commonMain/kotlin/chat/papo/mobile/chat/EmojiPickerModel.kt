package chat.papo.mobile.chat


/** Seções do seletor, na ordem em que aparecem. */
enum class PickerSection {
    Recent, Server, ServerResults, UnicodeResults,
    Smileys, People, Animals, Food, Travel, Activities, Objects, Symbols, Flags;

    companion object {
        /** A seção de um grupo do catálogo Unicode (0 a 8). */
        fun ofGroup(group: Int): PickerSection = entries[Smileys.ordinal + group]
    }
}

/** Uma célula da grade do seletor. [key] é única na grade inteira, mesmo com o mesmo emoji em duas seções. */
sealed interface PickerCell {
    val key: String

    data class Header(val section: PickerSection) : PickerCell {
        override val key get() = "h:${section.name}"
    }

    data class Unicode(val section: PickerSection, val emoji: UnicodeEmoji) : PickerCell {
        override val key get() = "u:${section.name}:${emoji.char}"
    }

    data class Server(val section: PickerSection, val emoji: ServerEmoji) : PickerCell {
        override val key get() = "s:${section.name}:${emoji.id}"
    }

    /** Lugar reservado enquanto a primeira leitura dos emojis do servidor não termina. */
    data class Placeholder(val section: PickerSection, val index: Int) : PickerCell {
        override val key get() = "p:${section.name}:$index"
    }
}

/** Monta o conteúdo do seletor de emoji. Só dados: a tela apenas desenha o resultado. */
object EmojiPickerModel {
    /** Quantos resultados Unicode uma busca mostra, no máximo. */
    const val MAX_RESULTS = 240

    /** Lugares reservados para os emojis do servidor durante a primeira leitura (duas fileiras). */
    const val SERVER_PLACEHOLDERS = 16

    /** As reações de sempre, que completam a fileira rápida de quem ainda usou poucas. */
    val DEFAULT_REACTIONS = listOf("👍", "\u2764\uFE0F", "😂", "😮", "😢", "🙏")

    /**
     * Fileira de reações rápidas: os emojis usados há pouco (os do servidor só se ainda
     * existem) e, para completar [limit], os padrões.
     */
    fun quickReactions(recents: List<EmojiRef>, server: ServerEmojis, limit: Int = 6): List<EmojiRef> {
        val picked = LinkedHashSet<EmojiRef>()
        for (ref in recents) {
            if (ref is EmojiRef.Custom && ref.id !in server.byId) continue
            picked += ref
            if (picked.size == limit) return picked.toList()
        }
        for (char in DEFAULT_REACTIONS) {
            picked += EmojiRef.Unicode(char)
            if (picked.size == limit) break
        }
        return picked.toList()
    }

    fun cells(
        query: String,
        unicode: UnicodeEmojiCatalog,
        server: ServerEmojis,
        recents: List<EmojiRef>,
    ): List<PickerCell> = if (query.isBlank()) browse(unicode, server, recents) else search(query, unicode, server)

    private fun browse(unicode: UnicodeEmojiCatalog, server: ServerEmojis, recents: List<EmojiRef>): List<PickerCell> {
        val out = ArrayList<PickerCell>()

        // Recentes só entram se o emoji ainda existe (do servidor) ou o aparelho sabe desenhá-lo.
        val recent = recents.mapNotNull { ref ->
            when (ref) {
                is EmojiRef.Unicode -> unicode.forChar(ref.char)?.let { PickerCell.Unicode(PickerSection.Recent, it) }
                is EmojiRef.Custom -> server.byId[ref.id]?.let { PickerCell.Server(PickerSection.Recent, it) }
            }
        }
        if (recent.isNotEmpty()) {
            out += PickerCell.Header(PickerSection.Recent)
            out += recent
        }
        // A lista do servidor só entra inteira: na primeira leitura ela cresce página a página,
        // e mostrá-la assim faria a grade se rearrumar embaixo do dedo. Até lá, lugares reservados.
        val serverList = usableServerList(server)
        when {
            serverList.isNotEmpty() -> {
                out += PickerCell.Header(PickerSection.Server)
                serverList.mapTo(out) { PickerCell.Server(PickerSection.Server, it) }
            }
            !server.complete && !server.failed -> {
                out += PickerCell.Header(PickerSection.Server)
                repeat(SERVER_PLACEHOLDERS) { out += PickerCell.Placeholder(PickerSection.Server, it) }
            }
        }
        for ((group, emojis) in unicode.groups.withIndex()) {
            if (emojis.isEmpty()) continue
            val section = PickerSection.ofGroup(group)
            out += PickerCell.Header(section)
            emojis.mapTo(out) { PickerCell.Unicode(section, it) }
        }
        return out
    }

    /** A lista do servidor que pode aparecer: inteira, ou o que chegou antes de uma falha. */
    private fun usableServerList(server: ServerEmojis): List<ServerEmoji> =
        if (server.complete || server.failed) server.list else emptyList()

    private fun search(query: String, unicode: UnicodeEmojiCatalog, server: ServerEmojis): List<PickerCell> {
        val out = ArrayList<PickerCell>()
        val key = foldForSearch(query).trim().trim(':')
        if (key.isEmpty()) return out

        val named = usableServerList(server)
            .filter { key in foldForSearch(it.name) }
            // Quem começa com o texto vem antes de quem só contém; dentro de cada grupo, a ordem alfabética da lista.
            .sortedBy { if (foldForSearch(it.name).startsWith(key)) 0 else 1 }
        if (named.isNotEmpty()) {
            out += PickerCell.Header(PickerSection.ServerResults)
            named.mapTo(out) { PickerCell.Server(PickerSection.ServerResults, it) }
        }
        val found = unicode.search(query, MAX_RESULTS)
        if (found.isNotEmpty()) {
            out += PickerCell.Header(PickerSection.UnicodeResults)
            found.mapTo(out) { PickerCell.Unicode(PickerSection.UnicodeResults, it) }
        }
        return out
    }
}
