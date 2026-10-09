package nl.tinnisoft.audio.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OutputDevice(
    val id: Int,
    val kind: Kind,
    /** Product name, e.g. the name of Bluetooth earbuds; null when unknown. */
    val name: String?,
) {
    enum class Kind { SPEAKER, WIRED, BLUETOOTH, USB, HEARING_AID, OTHER }

    /** Headphone-like outputs use the (usually stricter) headphone volume limit. */
    val isHeadphones: Boolean get() = kind != Kind.SPEAKER && kind != Kind.OTHER
}

/** Tracks the current media output (speaker, wired, Bluetooth A2DP/LE …). */
class OutputDeviceMonitor(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _current = MutableStateFlow(detect())
    val current: StateFlow<OutputDevice> = _current

    /** Called (main thread) when a headphone-type device is connected. */
    var onHeadphonesConnected: (() -> Unit)? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refresh()
            if (addedDevices.any { it.isSink && kindOf(it).let { k -> k != OutputDevice.Kind.SPEAKER && k != OutputDevice.Kind.OTHER } }) {
                onHeadphonesConnected?.invoke()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        _current.value = detect()
    }

    private fun detect(): OutputDevice {
        val routed: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            audioManager.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { routedInfo ->
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                    it.type == routedInfo.type && it.address == routedInfo.address
                } ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == routedInfo.type }
            }
        } else {
            null
        }
        val device = routed ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { kindOf(it) != OutputDevice.Kind.OTHER }
            .maxByOrNull { priority(kindOf(it)) }
        if (device == null) return OutputDevice(-1, OutputDevice.Kind.SPEAKER, null)
        val kind = kindOf(device)
        val name = when {
            kind == OutputDevice.Kind.SPEAKER -> null
            kind == OutputDevice.Kind.BLUETOOTH && !canReadBluetoothName() -> null
            else -> device.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        }
        return OutputDevice(device.id, kind, name)
    }

    private fun canReadBluetoothName(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun priority(kind: OutputDevice.Kind) = when (kind) {
        OutputDevice.Kind.BLUETOOTH -> 5
        OutputDevice.Kind.HEARING_AID -> 5
        OutputDevice.Kind.USB -> 4
        OutputDevice.Kind.WIRED -> 4
        OutputDevice.Kind.SPEAKER -> 1
        OutputDevice.Kind.OTHER -> 0
    }

    companion object {
        fun kindOf(d: AudioDeviceInfo): OutputDevice.Kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDevice.Kind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            -> OutputDevice.Kind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputDevice.Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> OutputDevice.Kind.USB
            AudioDeviceInfo.TYPE_HEARING_AID -> OutputDevice.Kind.HEARING_AID
            else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                OutputDevice.Kind.BLUETOOTH
            } else {
                OutputDevice.Kind.OTHER
            }
        }
    }
}
