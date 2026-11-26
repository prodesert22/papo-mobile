package chat.papo.mobile

import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.EffectiveDm
import chat.papo.mobile.chat.EffectiveNotifications
import chat.papo.mobile.chat.MessageKind
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.countsAsMention
import chat.papo.mobile.chat.shouldNotify
import chat.papo.mobile.chat.shouldNotifyDm
import chat.papo.mobile.chat.showsUnreadDot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationRulesTest {
    private fun channel(
        level: NotificationLevel = NotificationLevel.OnlyMentions,
        suppressEveryone: Boolean = false,
        suppressMentions: Boolean = false,
        muted: ActiveMute? = null,
    ) = EffectiveNotifications(level, suppressEveryone, suppressMentions, muted, sync = true)

    private val channelMute = ActiveMute(MuteSource.Channel, until = null)
    private val serverMute = ActiveMute(MuteSource.Server, until = null)

    // Primeira linha: mensagem própria, conversa aberta e sem permissão cortam antes de qualquer outra regra.

    @Test
    fun mensagemPropriaNuncaNotifica() {
        val all = channel(NotificationLevel.All)
        for (kind in MessageKind.entries) assertFalse(shouldNotify(kind, all, isOwn = true))
    }

    @Test
    fun conversaAbertaNaTelaNaoNotifica() {
        val all = channel(NotificationLevel.All)
        for (kind in MessageKind.entries) assertFalse(shouldNotify(kind, all, conversationOpen = true))
    }

    @Test
    fun semPermissaoDoAndroidNaoNotifica() {
        val all = channel(NotificationLevel.All)
        for (kind in MessageKind.entries) assertFalse(shouldNotify(kind, all, permissionGranted = false))
    }

    @Test
    fun comTudoLiberadoNotificaMensagemComumNoNivelTodas() {
        assertTrue(shouldNotify(MessageKind.Plain, channel(NotificationLevel.All)))
    }

    // Supressão

    @Test
    fun mencaoSuprimidaNaoNotificaMesmoNoNivelTodas() {
        assertFalse(shouldNotify(MessageKind.Mention, channel(NotificationLevel.All, suppressMentions = true)))
    }

    @Test
    fun everyoneSuprimidoNaoNotifica() {
        assertFalse(shouldNotify(MessageKind.Everyone, channel(NotificationLevel.All, suppressEveryone = true)))
    }

    @Test
    fun suprimirMencoesNaoCalaEveryoneNemSuprimirEveryoneCalaMencao() {
        assertTrue(shouldNotify(MessageKind.Everyone, channel(suppressMentions = true)))
        assertTrue(shouldNotify(MessageKind.Mention, channel(suppressEveryone = true)))
    }

    @Test
    fun mensagemComumNuncaESuprimida() {
        val suppressed = channel(NotificationLevel.All, suppressEveryone = true, suppressMentions = true)
        assertTrue(shouldNotify(MessageKind.Plain, suppressed))
    }

    // Nível

    @Test
    fun nivelNadaNaoNotificaNada() {
        val nothing = channel(NotificationLevel.Nothing)
        for (kind in MessageKind.entries) assertFalse(shouldNotify(kind, nothing))
    }

    @Test
    fun mencaoEEveryoneNotificamNoNivelSoMencoes() {
        val only = channel(NotificationLevel.OnlyMentions)
        assertTrue(shouldNotify(MessageKind.Mention, only))
        assertTrue(shouldNotify(MessageKind.Everyone, only))
        assertFalse(shouldNotify(MessageKind.Plain, only))
    }

    // Silêncio

    @Test
    fun mencaoEEveryoneFuramOSilencioDoCanalEDoServidor() {
        for (mute in listOf(channelMute, serverMute)) {
            val muted = channel(NotificationLevel.OnlyMentions, muted = mute)
            assertTrue(shouldNotify(MessageKind.Mention, muted))
            assertTrue(shouldNotify(MessageKind.Everyone, muted))
        }
    }

    @Test
    fun mensagemComumSilenciadaNaoNotificaNemNoNivelTodas() {
        for (mute in listOf(channelMute, serverMute)) {
            assertFalse(shouldNotify(MessageKind.Plain, channel(NotificationLevel.All, muted = mute)))
        }
    }

    @Test
    fun silencioNaoFuraNivelNadaNemSupressao() {
        assertFalse(shouldNotify(MessageKind.Mention, channel(NotificationLevel.Nothing, muted = channelMute)))
        assertFalse(shouldNotify(MessageKind.Mention, channel(muted = channelMute, suppressMentions = true)))
    }

    // DM

    @Test
    fun dmNotificaToda() {
        assertTrue(shouldNotifyDm(EffectiveDm(mute = null, sync = true)))
    }

    @Test
    fun dmSilenciadaNaoNotifica() {
        assertFalse(shouldNotifyDm(EffectiveDm(channelMute, sync = true)))
    }

    @Test
    fun dmPropriaAbertaOuSemPermissaoNaoNotifica() {
        val dm = EffectiveDm(mute = null, sync = true)
        assertFalse(shouldNotifyDm(dm, isOwn = true))
        assertFalse(shouldNotifyDm(dm, conversationOpen = true))
        assertFalse(shouldNotifyDm(dm, permissionGranted = false))
    }

    // Número de menções

    @Test
    fun mencaoEEveryoneContamQuandoNaoSuprimidos() {
        val open = channel()
        assertTrue(countsAsMention(MessageKind.Mention, open))
        assertTrue(countsAsMention(MessageKind.Everyone, open))
    }

    @Test
    fun mensagemComumNaoConta() {
        assertFalse(countsAsMention(MessageKind.Plain, channel(NotificationLevel.All)))
    }

    @Test
    fun supressaoTiraSoOTipoSuprimidoDoNumero() {
        assertFalse(countsAsMention(MessageKind.Mention, channel(suppressMentions = true)))
        assertTrue(countsAsMention(MessageKind.Everyone, channel(suppressMentions = true)))
        assertFalse(countsAsMention(MessageKind.Everyone, channel(suppressEveryone = true)))
        assertTrue(countsAsMention(MessageKind.Mention, channel(suppressEveryone = true)))
    }

    @Test
    fun nivelESilencioNaoMudamONumero() {
        for (mute in listOf(null, channelMute, serverMute)) {
            for (level in NotificationLevel.entries) {
                val effective = channel(level, muted = mute)
                assertTrue(countsAsMention(MessageKind.Mention, effective))
                assertTrue(countsAsMention(MessageKind.Everyone, effective))
            }
        }
    }

    // Círculo de "nova mensagem"

    @Test
    fun silenciadoNaoMostraOCirculo() {
        assertTrue(showsUnreadDot(channel()))
        assertFalse(showsUnreadDot(channel(muted = channelMute)))
        assertFalse(showsUnreadDot(channel(muted = serverMute)))
        assertTrue(showsUnreadDot(EffectiveDm(mute = null, sync = true)))
        assertFalse(showsUnreadDot(EffectiveDm(channelMute, sync = true)))
    }

    @Test
    fun nivelNadaNaoTiraOCirculo() {
        assertTrue(showsUnreadDot(channel(NotificationLevel.Nothing)))
    }
}
