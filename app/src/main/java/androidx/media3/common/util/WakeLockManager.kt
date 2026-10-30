package androidx.media3.common.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.media3.common.util.Log.w

import java.util.concurrent.atomic.AtomicBoolean

class WakeLockManager(context: Context, wakeLockLooper: Looper, clock: Clock) {
    private val wakeLockManagerInternal: WakeLockManagerInternal
    private val wakeLockHandler: HandlerWrapper
    private val mainHandler: HandlerWrapper

    private var enabled = false
    private var stayAwake = false

    init {
        wakeLockManagerInternal = WakeLockManagerInternal(context.getApplicationContext())
        wakeLockHandler = clock.createHandler(wakeLockLooper,  null)
        mainHandler = clock.createHandler(Looper.getMainLooper(),  null)
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) {
            return
        }
        this.enabled = enabled
        postUpdateWakeLock(enabled, stayAwake)
    }

    fun setStayAwake(stayAwake: Boolean) {
        if (this.stayAwake == stayAwake) {
            return
        }
        this.stayAwake = stayAwake
        if (enabled) {
            postUpdateWakeLock( true, stayAwake)
        }
    }

    private fun postUpdateWakeLock(enabled: Boolean, stayAwake: Boolean) {
        if (shouldAcquireWakelock(enabled, stayAwake)) {
            wakeLockHandler.post(Runnable {
                wakeLockManagerInternal.updateWakeLock(
                    enabled,
                    stayAwake
                )
            })
        } else {
            // When we are about to release a wakelock, add emergency safeguard on main thread in case
            // the wakelock handler thread is unresponsive.
            val emergencyReleaseNeeded = AtomicBoolean(true)
            mainHandler.postDelayed(
                Runnable { wakeLockManagerInternal.forceReleaseWakeLock(emergencyReleaseNeeded) },
                UNREACTIVE_WAKELOCK_HANDLER_RELEASE_DELAY_MS.toLong()
            )
            wakeLockHandler.post(
                Runnable {
                    emergencyReleaseNeeded.set(false)
                    wakeLockManagerInternal.updateWakeLock(enabled, stayAwake)
                })
        }
    }

    private class WakeLockManagerInternal(private val applicationContext: Context) {
        private var wakeLock:WakeLock? = null

        // WakelockTimeout suppressed because the time the wake lock is needed for is unknown (could be
        // listening to radio with screen off for multiple hours), therefore we can not determine a
        // reasonable timeout that would not affect the user.
        @SuppressLint("WakelockTimeout")
        @Synchronized
        fun updateWakeLock(enabled: Boolean, stayAwake: Boolean) {
            if (enabled && wakeLock == null) {
                if (applicationContext.checkSelfPermission(Manifest.permission.WAKE_LOCK)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    w(TAG, "WAKE_LOCK permission not granted, can't acquire wake lock for playback")
                    return
                }
                val powerManager =
                    applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager?
                if (powerManager == null) {
                    w(TAG, "PowerManager is null, therefore not creating the WakeLock.")
                    return
                }
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                wakeLock!!.setReferenceCounted(false)
            }

            if (wakeLock == null) {
                return
            }

            if (shouldAcquireWakelock(enabled, stayAwake)) {
                wakeLock!!.acquire()
            } else {
                wakeLock!!.release()
            }
        }

        fun forceReleaseWakeLock(shouldForceRelease: AtomicBoolean) {
            if (shouldForceRelease.get()) {
                Thread(
                    Runnable { forceReleaseWakeLockInternal(shouldForceRelease) },
                    WAKE_LOCK_TAG
                ).start()
            }
        }

        @Synchronized
        fun forceReleaseWakeLockInternal(shouldForceRelease: AtomicBoolean) {
            if (shouldForceRelease.get() && wakeLock != null) {
                wakeLock!!.release()
            }
        }
    }

    companion object {
        private const val TAG = "WakeLockManager"
        private const val WAKE_LOCK_TAG = "ExoPlayer:WakeLockManager"
        private const val UNREACTIVE_WAKELOCK_HANDLER_RELEASE_DELAY_MS = 1000

        private fun shouldAcquireWakelock(enabled: Boolean, stayAwake: Boolean): Boolean {
            return enabled && stayAwake
        }
    }
}
