package androidx.media3.exoplayer.analytics

import android.media.metrics.LogSessionId
import android.os.Build
import androidx.annotation.RequiresApi

import com.google.common.base.Preconditions

class PlayerId(val name: String?) {
    private val logSessionIdApi31: LogSessionIdApi31?

    init {
        this.logSessionIdApi31 = if (Build.VERSION.SDK_INT >= 31) LogSessionIdApi31() else null
    }

    @get:Synchronized
    @get:RequiresApi(31)
    @set:Synchronized
    @set:RequiresApi(31)
    var logSessionId: LogSessionId
        get() = logSessionIdApi31!!.logSessionId
        set(logSessionId) {
            logSessionIdApi31!!.logSessionId = (logSessionId)
        }

    @RequiresApi(31)
    private class LogSessionIdApi31 {
        var logSessionId: LogSessionId = LogSessionId.LOG_SESSION_ID_NONE
            set(value) {
                Preconditions.checkState(this.logSessionId == LogSessionId.LOG_SESSION_ID_NONE)
                field = value
            }
    }

    companion object {
        val UNSET: PlayerId = PlayerId( "")
        val PRELOAD: PlayerId = PlayerId("preload")
    }
}
