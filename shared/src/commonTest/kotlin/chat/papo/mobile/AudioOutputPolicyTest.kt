package chat.papo.mobile

import chat.papo.mobile.voice.AudioOutput
import chat.papo.mobile.voice.AudioOutputKind
import chat.papo.mobile.voice.AudioOutputPolicy
import chat.papo.mobile.voice.OutputControl
import chat.papo.mobile.voice.outputControlFor
import chat.papo.mobile.voice.toggleTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioOutputPolicyTest {
    private val earpiece = AudioOutput("1", AudioOutputKind.Earpiece)
    private val speaker = AudioOutput("2", AudioOutputKind.Speaker)
    private val wired = AudioOutput("3", AudioOutputKind.Wired, "Fone de teste")
    private val bluetooth = AudioOutput("4", AudioOutputKind.Bluetooth, "Caixa de teste")
    private val hearingAid = AudioOutput("5", AudioOutputKind.HearingAid)
    private val dock = AudioOutput("6", AudioOutputKind.Other, "Base de teste")
    private val usb = AudioOutput("7", AudioOutputKind.Usb)
    private val phone = listOf(earpiece, speaker)

    @Test
    fun semSaidasNaoHaDecisao() {
        assertNull(AudioOutputPolicy().decide(emptyList()))
    }

    @Test
    fun noInicioSemFoneTocaNoAltoFalante() {
        assertEquals(speaker, AudioOutputPolicy().decide(phone))
    }

    @Test
    fun noInicioBluetoothVenceOFioEOFioVenceOAltoFalante() {
        assertEquals(bluetooth, AudioOutputPolicy().decide(phone + wired + bluetooth))
        assertEquals(hearingAid, AudioOutputPolicy().decide(phone + wired + hearingAid))
        assertEquals(wired, AudioOutputPolicy().decide(phone + wired))
        assertEquals(usb, AudioOutputPolicy().decide(phone + usb))
    }

    @Test
    fun saidaQueNaoEFoneNuncaViraSaidaSozinha() {
        assertEquals(speaker, AudioOutputPolicy().decide(phone + dock))
    }

    @Test
    fun aparelhoSoComAlgumasSaidasUsaAPrimeiraQueHouver() {
        assertEquals(speaker, AudioOutputPolicy().decide(listOf(speaker)))
        assertEquals(earpiece, AudioOutputPolicy().decide(listOf(earpiece)))
        assertEquals(dock, AudioOutputPolicy().decide(listOf(dock)))
    }

    @Test
    fun listaVaziaAntesDoInicioNaoContaComoInicio() {
        val policy = AudioOutputPolicy()
        assertNull(policy.decide(emptyList()))
        // Quando o sistema lista de verdade, vale a regra do início (alto-falante), não a de "a saída sumiu" (auricular).
        assertEquals(speaker, policy.decide(phone))
    }

    @Test
    fun foneQueLigaDuranteAChamadaPassaASerASaida() {
        val policy = AudioOutputPolicy()
        assertEquals(speaker, policy.decide(phone))
        assertEquals(bluetooth, policy.decide(phone + bluetooth))
    }

    @Test
    fun foneQueLigaVenceAteUmaEscolhaAnterior() {
        val policy = AudioOutputPolicy()
        policy.decide(phone)
        assertEquals(earpiece, policy.choose("1", phone))
        assertEquals(wired, policy.decide(phone + wired))
    }

    @Test
    fun maisDeUmFoneNovoFicaComOMelhor() {
        val policy = AudioOutputPolicy()
        policy.decide(phone)
        assertEquals(bluetooth, policy.decide(phone + wired + bluetooth))
    }

    @Test
    fun escolhaDaPessoaVaiAteOAparelhoSumir() {
        val policy = AudioOutputPolicy()
        policy.decide(phone + bluetooth)
        assertEquals(earpiece, policy.choose("1", phone + bluetooth))
        // Nada mudou, ou mudou algo que não é fone: a escolha fica.
        assertEquals(earpiece, policy.decide(phone + bluetooth))
        assertEquals(earpiece, policy.decide(phone + bluetooth + dock))
        // O fone que não estava em uso sai: também fica.
        assertEquals(earpiece, policy.decide(phone + dock))
    }

    @Test
    fun escolhaDeUmaSaidaQueNaoExisteNaoMudaNada() {
        val policy = AudioOutputPolicy()
        assertEquals(speaker, policy.decide(phone))
        assertNull(policy.choose("99", phone))
        assertEquals(speaker, policy.decide(phone))
    }

    @Test
    fun saidaEmUsoQueSomeCaiNoAuricular() {
        val policy = AudioOutputPolicy()
        assertEquals(bluetooth, policy.decide(phone + bluetooth))
        assertEquals(earpiece, policy.decide(phone))
    }

    @Test
    fun saidaEmUsoQueSomeVaiParaOutroFoneQueSigaLigado() {
        val policy = AudioOutputPolicy()
        assertEquals(bluetooth, policy.decide(phone + wired + bluetooth))
        assertEquals(wired, policy.decide(phone + wired))
    }

    @Test
    fun tabletSemAuricularCaiNoAltoFalante() {
        val policy = AudioOutputPolicy()
        assertEquals(bluetooth, policy.decide(listOf(speaker, bluetooth)))
        assertEquals(speaker, policy.decide(listOf(speaker)))
    }

    @Test
    fun foneQueVoltaDepoisDeSairContaComoNovo() {
        val policy = AudioOutputPolicy()
        policy.decide(phone + bluetooth)
        assertEquals(earpiece, policy.decide(phone))
        assertEquals(bluetooth, policy.decide(phone + bluetooth))
    }

    @Test
    fun repetirAMesmaListaNaoMudaADecisao() {
        val policy = AudioOutputPolicy()
        assertEquals(bluetooth, policy.decide(phone + bluetooth))
        assertEquals(bluetooth, policy.decide(phone + bluetooth))
        assertEquals(bluetooth, policy.decide(phone + bluetooth))
    }

    @Test
    fun botaoAlternaSoComAltoFalanteEAuricular() {
        assertEquals(OutputControl.Toggle, outputControlFor(phone))
        assertEquals(OutputControl.Picker, outputControlFor(phone + bluetooth))
        assertEquals(OutputControl.Picker, outputControlFor(listOf(speaker, wired)))
        assertEquals(OutputControl.None, outputControlFor(listOf(speaker)))
        assertEquals(OutputControl.None, outputControlFor(emptyList()))
    }

    @Test
    fun alternarVaiDoAltoFalanteAoAuricularEDeQualquerOutraAoAltoFalante() {
        assertEquals(earpiece, toggleTarget(phone, speaker))
        assertEquals(speaker, toggleTarget(phone, earpiece))
        assertEquals(speaker, toggleTarget(phone + bluetooth, bluetooth))
        assertEquals(speaker, toggleTarget(phone, null))
        assertNull(toggleTarget(listOf(speaker), speaker))
    }
}
