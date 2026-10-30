package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import android.os.Build

internal object DeviceTypeUtil {
    fun isBluetoothDevice(deviceType: Int): Boolean {
        if (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 31
            && (deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || deviceType == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 33 && deviceType == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
            return true
        }
        return false
    }

    fun isBuiltInEarpiece(deviceType: Int): Boolean {
        return deviceType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }

    fun isBuiltInSpeaker(deviceType: Int): Boolean {
        return deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    fun isHdmiArc(deviceType: Int): Boolean {
        return deviceType == AudioDeviceInfo.TYPE_HDMI_ARC
    }

    fun isHdmiEarc(deviceType: Int): Boolean {
        return Build.VERSION.SDK_INT >= 31 && deviceType == AudioDeviceInfo.TYPE_HDMI_EARC
    }

    fun isUsbDevice(deviceType: Int): Boolean {
        if (deviceType == AudioDeviceInfo.TYPE_USB_DEVICE
            || deviceType == AudioDeviceInfo.TYPE_USB_ACCESSORY
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= 31 && deviceType == AudioDeviceInfo.TYPE_USB_HEADSET) {
            return true
        }
        return false
    }
}
