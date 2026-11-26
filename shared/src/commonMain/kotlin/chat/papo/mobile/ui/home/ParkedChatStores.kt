package chat.papo.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * O armazém de ViewModels de cada conversa do painel. Uma conversa tem o dela, e ele sai quando ela deixa de ser a última
 * da conta: guardar todos os que já se abriram deixaria cada um coletando do banco para sempre.
 *
 * Fica num ViewModel do aplicativo (e não num `remember`) para sobreviver a girar o aparelho, como o estado de cada
 * conversa (anexos escolhidos, resposta em andamento) sobrevivia quando ela era uma tela da navegação. Quem trava o
 * app chama [clear]: nada de uma conversa fica em memória depois do bloqueio.
 */
class ParkedChatStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    /** O dono dos ViewModels da conversa de [key] (ver [keyOf]); o mesmo armazém enquanto a chave se repete. */
    fun owner(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /** Libera os armazéns das conversas que não estão em [keys]. */
    fun retain(keys: Set<String>) {
        val gone = stores.keys.filter { it !in keys }
        for (key in gone) stores.remove(key)?.clear()
    }

    /** Libera todos. */
    fun clear() {
        for (store in stores.values) store.clear()
        stores.clear()
    }

    override fun onCleared() = clear()

    companion object {
        /**
         * A chave de uma conversa: a conta, o canal, a mensagem pedida ao abrir e a sessão. A sessão entra para o que uma
         * sessão leu nunca aparecer em outra (a conta que entrou de novo tem outra), como a chave da tela de perfil.
         */
        fun keyOf(accountId: String, chat: ParkedChat, session: Any): String =
            "$accountId/${chat.channelId}/${chat.target?.id}/${session.hashCode()}"

        /** As chaves das conversas que ainda valem: a última de cada conta que tem sessão. */
        fun liveKeys(chats: ParkedChats, sessions: Map<String, Any>): Set<String> =
            chats.entries.mapNotNullTo(HashSet()) { (accountId, chat) -> sessions[accountId]?.let { keyOf(accountId, chat, it) } }
    }
}
