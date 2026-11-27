package chat.papo.mobile

import android.media.AudioDeviceInfo
import chat.papo.mobile.platform.outputKindOf
import chat.papo.mobile.voice.AudioOutputKind
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioOutputKindTest {
    @Test
    fun tiposDoSistemaViramTiposDoApp() {
        assertEquals(AudioOutputKind.Earpiece, outputKindOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(AudioOutputKind.Speaker, outputKindOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(AudioOutputKind.Wired, outputKindOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioOutputKind.Wired, outputKindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(AudioOutputKind.Usb, outputKindOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioOutputKind.Usb, outputKindOf(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(AudioOutputKind.Bluetooth, outputKindOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(AudioOutputKind.Bluetooth, outputKindOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioOutputKind.HearingAid, outputKindOf(AudioDeviceInfo.TYPE_HEARING_AID))
    }

    @Test
    fun tipoSemEquivalenteEOutro() {
        assertEquals(AudioOutputKind.Other, outputKindOf(AudioDeviceInfo.TYPE_HDMI))
        assertEquals(AudioOutputKind.Other, outputKindOf(AudioDeviceInfo.TYPE_DOCK))
    }
}
