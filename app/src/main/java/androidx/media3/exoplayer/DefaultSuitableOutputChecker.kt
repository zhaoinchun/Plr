package androidx.media3.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.MediaRouter2.RouteCallback
import android.media.MediaRouter2.RoutingController
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.util.BackgroundThreadStateHandler
import androidx.media3.common.util.BackgroundThreadStateHandler.StateChangeListener
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.isWear

import java.util.concurrent.Executor

internal class DefaultSuitableOutputChecker : SuitableOutputChecker {
    private val impl: SuitableOutputChecker

    init {
        impl = if (Build.VERSION.SDK_INT >= 35) {
            ImplApi35()
        } else {
            ImplApi23()
        }
    }

    override fun enable(
        callback: SuitableOutputChecker.Callback,
        context: Context,
        callbackLooper: Looper,
        backgroundLooper: Looper,
        clock: Clock
    ) {
        impl.enable(callback, context, callbackLooper, backgroundLooper, clock)
    }

    override fun disable() {
        impl.disable()
    }

    override val isSelectedOutputSuitableForPlayback: Boolean
        get() = impl.isSelectedOutputSuitableForPlayback

    @RequiresApi(35)
    private class ImplApi35 : SuitableOutputChecker {
        private var router:MediaRouter2? = null
        private var routeCallback:RouteCallback? = null
        private var controllerCallback: MediaRouter2.ControllerCallback? = null
        private var isSuitableForPlaybackState:BackgroundThreadStateHandler<Boolean>? =
            null

        @SuppressLint("ThreadSafe") // Handler is thread-safe, but not annotated.
        override fun enable(
            callback: SuitableOutputChecker.Callback,
            context: Context,
            callbackLooper: Looper,
            backgroundLooper: Looper,
            clock: Clock
        ) {
            isSuitableForPlaybackState =
                BackgroundThreadStateHandler<Boolean>( 
                    true,
                    backgroundLooper,
                    callbackLooper,
                    clock,  
                    StateChangeListener { oldState: Boolean, newState: Boolean ->
                        callback.onSelectedOutputSuitabilityChanged(
                            newState!!
                        )
                    })
            isSuitableForPlaybackState!!.runInBackground(
                Runnable {
                    isSuitableForPlaybackState!!
                    router = MediaRouter2.getInstance(context)
                    routeCallback = object : RouteCallback() {}
                    val backgroundExecutor = Executor { runnable: Runnable? ->
                        isSuitableForPlaybackState!!.runInBackground(runnable!!)
                    }
                    router!!.registerRouteCallback(
                        backgroundExecutor, routeCallback!!, EMPTY_DISCOVERY_PREFERENCE
                    )
                    controllerCallback =
                        object : MediaRouter2.ControllerCallback() {
                            override fun onControllerUpdated(controller: RoutingController) {
                                isSuitableForPlaybackState!!.setStateInBackground(
                                    isSelectedOutputSuitableForPlayback(router)
                                )
                            }
                        }
                    router!!.registerControllerCallback(backgroundExecutor, controllerCallback!!)
                    isSuitableForPlaybackState!!.setStateInBackground(
                        isSelectedOutputSuitableForPlayback(router)
                    )
                })
        }

        override fun disable() {
            isSuitableForPlaybackState!!.runInBackground(
                    Runnable {
                        router!!
                            .unregisterControllerCallback(controllerCallback!!)
                        controllerCallback = null
                        router!!.unregisterRouteCallback(routeCallback!!)
                    })
        }

        override val isSelectedOutputSuitableForPlayback: Boolean
            get() =  (if (isSuitableForPlaybackState == null) true else isSuitableForPlaybackState?.get())!!

        companion object {
            private val EMPTY_DISCOVERY_PREFERENCE =
                RouteDiscoveryPreference.Builder( 
                    listOf(),  false
                )
                    .build()

            private fun isSelectedOutputSuitableForPlayback(router: MediaRouter2?): Boolean {
                val transferReason =
                    router!!.getSystemController()
                        .getRoutingSessionInfo().getTransferReason()
                val wasTransferInitiatedBySelf =
                    router!!.getSystemController().wasTransferInitiatedBySelf()
                for (routeInfo in router.getSystemController().getSelectedRoutes()) {
                    if (isRouteSuitableForMediaPlayback(
                            routeInfo, transferReason, wasTransferInitiatedBySelf
                        )
                    ) {
                        return true
                    }
                }
                return false
            }

            private fun isRouteSuitableForMediaPlayback(
                routeInfo: MediaRoute2Info, transferReason: Int, wasTransferInitiatedBySelf: Boolean
            ): Boolean {
                val suitabilityStatus = routeInfo.getSuitabilityStatus()

                if (suitabilityStatus == MediaRoute2Info.SUITABILITY_STATUS_SUITABLE_FOR_MANUAL_TRANSFER) {
                    return (transferReason == RoutingSessionInfo.TRANSFER_REASON_SYSTEM_REQUEST
                            || transferReason == RoutingSessionInfo.TRANSFER_REASON_APP)
                            && wasTransferInitiatedBySelf
                }

                return suitabilityStatus == MediaRoute2Info.SUITABILITY_STATUS_SUITABLE_FOR_DEFAULT_TRANSFER
            }
        }
    }

    private class ImplApi23 : SuitableOutputChecker {
        private var audioManager: AudioManager? = null
        private var audioDeviceCallback:AudioDeviceCallback? = null
        private var isSuitableForPlaybackState:BackgroundThreadStateHandler<Boolean>? =
            null

        override fun enable(
            callback: SuitableOutputChecker.Callback,
            context: Context,
            callbackLooper: Looper,
            backgroundLooper: Looper,
            clock: Clock
        ) {
            isSuitableForPlaybackState =
                BackgroundThreadStateHandler<Boolean>( 
                    true,
                    backgroundLooper,
                    callbackLooper,
                    clock,  
                    StateChangeListener { oldState: Boolean?, newState: Boolean? ->
                        callback.onSelectedOutputSuitabilityChanged(
                            newState!!
                        )
                    })
            isSuitableForPlaybackState!!.runInBackground(
                Runnable {
                    isSuitableForPlaybackState!!
                    if (!isWear(context)) {
                        return@Runnable
                    }
                    val audioManager =
                        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
                    if (audioManager == null) {
                        return@Runnable
                    }
                    this.audioManager = audioManager
                    audioDeviceCallback =
                        object : AudioDeviceCallback() {
                            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo?>?) {
                                isSuitableForPlaybackState!!.setStateInBackground(
                                    hasSupportedAudioOutput()
                                )
                            }

                            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo?>?) {
                                isSuitableForPlaybackState!!.setStateInBackground(
                                    hasSupportedAudioOutput()
                                )
                            }
                        }
                    audioManager.registerAudioDeviceCallback(
                        audioDeviceCallback,
                        Handler(Looper.myLooper()!!)
                    )
                    isSuitableForPlaybackState!!.setStateInBackground(hasSupportedAudioOutput())
                })
        }

        override fun disable() {
            isSuitableForPlaybackState!!.runInBackground(
                    Runnable {
                        if (audioManager != null) {
                            audioManager!!.unregisterAudioDeviceCallback(audioDeviceCallback!! )
                        }
                    })
        }

        override val isSelectedOutputSuitableForPlayback: Boolean
            get() = (if (isSuitableForPlaybackState == null) true else isSuitableForPlaybackState!!.get())!!

        fun hasSupportedAudioOutput(): Boolean {
            val audioDeviceInfos =
                audioManager!!.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in audioDeviceInfos) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.getType() == AudioDeviceInfo.TYPE_LINE_ANALOG || device.getType() == AudioDeviceInfo.TYPE_LINE_DIGITAL || device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    return true
                }
                if (Build.VERSION.SDK_INT >= 26 && device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true
                }
                if (Build.VERSION.SDK_INT >= 28 && device.getType() == AudioDeviceInfo.TYPE_HEARING_AID) {
                    return true
                }
                if (Build.VERSION.SDK_INT >= 31
                    && (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || device.getType() == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                ) {
                    return true
                }
                if (Build.VERSION.SDK_INT >= 33 && device.getType() == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                    return true
                }
            }
            return false
        }
    }
}
