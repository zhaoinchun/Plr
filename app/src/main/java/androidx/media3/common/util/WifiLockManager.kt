package androidx.media3.common.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Looper
import androidx.media3.common.util.Log.w

import java.util.concurrent.atomic.AtomicBoolean

class WifiLockManager(context: Context, wifiLockLooper: Looper, clock: Clock) {
    private val wifiLockManagerInternal: WifiLockManagerInternal
    private val wifiLockHandler: HandlerWrapper
    private val mainHandler: HandlerWrapper

    private var enabled = false
    private var stayAwake = false

    init {
        wifiLockManagerInternal = WifiLockManagerInternal(context.getApplicationContext())
        wifiLockHandler = clock.createHandler(wifiLockLooper,  null)
        mainHandler = clock.createHandler(Looper.getMainLooper(),  null)
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) {
            return
        }
        this.enabled = enabled
        postUpdateWifiLock(enabled, stayAwake)
    }

    fun setStayAwake(stayAwake: Boolean) {
        if (this.stayAwake == stayAwake) {
            return
        }
        this.stayAwake = stayAwake
        if (enabled) {
            postUpdateWifiLock( true, stayAwake)
        }
    }

    private fun postUpdateWifiLock(enabled: Boolean, stayAwake: Boolean) {
        if (shouldAcquireWifilock(enabled, stayAwake)) {
            wifiLockHandler.post(Runnable {
                wifiLockManagerInternal.updateWifiLock(
                    enabled,
                    stayAwake
                )
            })
        } else {
            // When we are about to release a Wifi lock, add emergency safeguard on main thread in case
            // the lock handler thread is unresponsive.
            val emergencyReleaseNeeded = AtomicBoolean(true)
            mainHandler.postDelayed(
                Runnable { wifiLockManagerInternal.forceReleaseWifiLock(emergencyReleaseNeeded) },
                UNREACTIVE_WIFILOCK_HANDLER_RELEASE_DELAY_MS.toLong()
            )
            wifiLockHandler.post(
                Runnable {
                    emergencyReleaseNeeded.set(false)
                    wifiLockManagerInternal.updateWifiLock(enabled, stayAwake)
                })
        }
    }

    private class WifiLockManagerInternal(private val applicationContext: Context) {
        private var wifiLock:WifiLock? = null

        fun updateWifiLock(enabled: Boolean, stayAwake: Boolean) {
            if (enabled && wifiLock == null) {
                if (applicationContext.checkSelfPermission(Manifest.permission.WAKE_LOCK)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    w(TAG, "WAKE_LOCK permission not granted, can't acquire wake lock for playback")
                    return
                }
                val wifiManager =
                    applicationContext.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager?
                if (wifiManager == null) {
                    w(TAG, "WifiManager is null, therefore not creating the WifiLock.")
                    return
                }
                wifiLock =
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                wifiLock!!.setReferenceCounted(false)
            }

            if (wifiLock == null) {
                return
            }

            if (shouldAcquireWifilock(enabled, stayAwake)) {
                wifiLock!!.acquire()
            } else {
                wifiLock!!.release()
            }
        }

        fun forceReleaseWifiLock(shouldForceRelease: AtomicBoolean) {
            if (shouldForceRelease.get()) {
                Thread(
                    Runnable { forceReleaseWifiLockInternal(shouldForceRelease) },
                    WIFI_LOCK_TAG
                ).start()
            }
        }

        @Synchronized
        fun forceReleaseWifiLockInternal(shouldForceRelease: AtomicBoolean) {
            if (shouldForceRelease.get() && wifiLock != null) {
                wifiLock!!.release()
            }
        }
    }

    companion object {
        private const val TAG = "WifiLockManager"
        private const val WIFI_LOCK_TAG = "ExoPlayer:WifiLockManager"
        private const val UNREACTIVE_WIFILOCK_HANDLER_RELEASE_DELAY_MS = 1000

        private fun shouldAcquireWifilock(enabled: Boolean, stayAwake: Boolean): Boolean {
            return enabled && stayAwake
        }
    }
}
