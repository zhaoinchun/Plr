package androidx.media3.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.provider.Settings
import androidx.annotation.IntRange
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.SystemClock

import androidx.media3.common.util.Util.isWear
import com.google.common.base.Preconditions
import java.util.concurrent.TimeUnit

class WearUnsuitableOutputPlaybackSuppressionResolverListener
@VisibleForTesting internal constructor(
    context: Context,
    @IntRange(from = 0) autoResumeTimeoutMs: Long,
    clock: Clock
) : Player.Listener {
    private val applicationContext: Context
    private val autoResumeTimeoutAfterUnsuitableOutputSuppressionMs: Long
    private val clock: Clock

    private val wakeLock: WakeLock?

    private var unsuitableOutputPlaybackSuppressionStartRealtimeMs: Long

    constructor(
        context: Context,
        @IntRange(from = 0) autoResumeTimeoutMs: Long = DEFAULT_PLAYBACK_SUPPRESSION_AUTO_RESUME_TIMEOUT_MS
    ) : this(context, autoResumeTimeoutMs, Clock.DEFAULT)

    init {
        Preconditions.checkArgument(autoResumeTimeoutMs >= 0)
        applicationContext = context.getApplicationContext()
        autoResumeTimeoutAfterUnsuitableOutputSuppressionMs = autoResumeTimeoutMs
        this.clock = clock
        unsuitableOutputPlaybackSuppressionStartRealtimeMs = C.TIME_UNSET
        var wakeLock: WakeLock? = null
        val powerManager =
            applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager?
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock.setReferenceCounted(false)
        }
        this.wakeLock = wakeLock
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!isWear(applicationContext)) {
            return
        }
        if ((events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
                    || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))
            && player.playWhenReady
            && (player.playbackSuppressionReason
                    == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
        ) {
            player.pause()
            unsuitableOutputPlaybackSuppressionStartRealtimeMs = clock.elapsedRealtime()
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(autoResumeTimeoutAfterUnsuitableOutputSuppressionMs)
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                launchSystemMediaOutputSwitcherUi(applicationContext)
            }
        } else if (events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
            && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && unsuitableOutputPlaybackSuppressionStartRealtimeMs != C.TIME_UNSET && (clock.elapsedRealtime() - unsuitableOutputPlaybackSuppressionStartRealtimeMs
                    < autoResumeTimeoutAfterUnsuitableOutputSuppressionMs)
        ) {
            unsuitableOutputPlaybackSuppressionStartRealtimeMs = C.TIME_UNSET
            player.play()
            if (wakeLock != null) {
                wakeLock.release()
            }
        }
    }

    companion object {
        
        private const val OUTPUT_SWITCHER_INTENT_ACTION_NAME =
            "com.android.settings.panel.action.MEDIA_OUTPUT"

        private const val EXTRA_OUTPUT_SWITCHER_PACKAGE_NAME =
            "com.android.settings.panel.extra.PACKAGE_NAME"

        private const val EXTRA_BLUETOOTH_SETTINGS_CLOSE_ON_CONNECT = "EXTRA_CLOSE_ON_CONNECT"

        private const val EXTRA_BLUETOOTH_SETTINGS_CONNECTION_ONLY = "EXTRA_CONNECTION_ONLY"

        private const val EXTRA_BLUETOOTH_SETTINGS_FILTER_TYPE =
            "android.bluetooth.devicepicker.extra.FILTER_TYPE"

        private const val FILTER_TYPE_AUDIO = 1

        private const val WAKE_LOCK_TAG =
            "WearUnsuitableOutputPlaybackSuppressionResolverListener:WakeLock"

        val DEFAULT_PLAYBACK_SUPPRESSION_AUTO_RESUME_TIMEOUT_MS: Long = TimeUnit.MINUTES.toMillis(5)

        private fun launchSystemMediaOutputSwitcherUi(context: Context) {
            val outputSwitcherLaunchIntent: Intent =
                Intent(OUTPUT_SWITCHER_INTENT_ACTION_NAME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_OUTPUT_SWITCHER_PACKAGE_NAME, context.getPackageName())
            val outputSwitcherSystemComponentName: ComponentName? =
                getSystemOrSystemUpdatedAppComponent(context, outputSwitcherLaunchIntent)
            if (outputSwitcherSystemComponentName != null) {
                outputSwitcherLaunchIntent.setComponent(outputSwitcherSystemComponentName)
                context.startActivity(outputSwitcherLaunchIntent)
            } else {
                val bluetoothSettingsLaunchIntent: Intent =
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(EXTRA_BLUETOOTH_SETTINGS_CLOSE_ON_CONNECT, true)
                        .putExtra(EXTRA_BLUETOOTH_SETTINGS_CONNECTION_ONLY, true)
                        .putExtra(EXTRA_BLUETOOTH_SETTINGS_FILTER_TYPE, FILTER_TYPE_AUDIO)
                val bluetoothSettingsSystemComponentName: ComponentName? =
                    getSystemOrSystemUpdatedAppComponent(context, bluetoothSettingsLaunchIntent)
                if (bluetoothSettingsSystemComponentName != null) {
                    bluetoothSettingsLaunchIntent.setComponent(bluetoothSettingsSystemComponentName)
                    context.startActivity(bluetoothSettingsLaunchIntent)
                }
            }
        }

        private fun getSystemOrSystemUpdatedAppComponent(
            context: Context, intent: Intent
        ): ComponentName? {
            val packageManager = context.getPackageManager()
            val resolveInfos = packageManager.queryIntentActivities(intent,  0)
            for (resolveInfo in resolveInfos) {
                val activityInfo = resolveInfo.activityInfo
                if (activityInfo == null || activityInfo.applicationInfo == null) {
                    continue
                }
                val appInfo = activityInfo.applicationInfo
                val systemAndUpdatedSystemAppFlags =
                    ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                if ((systemAndUpdatedSystemAppFlags and appInfo.flags) != 0) {
                    return ComponentName(activityInfo.packageName, activityInfo.name)
                }
            }
            return null
        }
    }
}
