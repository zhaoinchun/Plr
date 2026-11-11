package androidx.media3.exoplayer.upstream

import androidx.annotation.IntDef
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.google.common.base.Preconditions
import java.io.IOException

interface LoadErrorHandlingPolicy {

    class LoadErrorInfo(
        val loadEventInfo: LoadEventInfo,
        val mediaLoadData: MediaLoadData,
        val exception: IOException,
        val errorCount: Int
    )

    class FallbackOptions(
        val numberOfLocations: Int,
        val numberOfExcludedLocations: Int,
        val numberOfTracks: Int,
        val numberOfExcludedTracks: Int,
        val locationSteeringActive: Boolean
    ) {
        fun isFallbackAvailable(type:  Int): Boolean {
            return if (type == FALLBACK_TYPE_LOCATION)
                numberOfLocations - numberOfExcludedLocations > 1
            else
                numberOfTracks - numberOfExcludedTracks > 1
        }
    }

    class FallbackSelection(type:  Int, exclusionDurationMs: Long) {
        val type:  Int
        val exclusionDurationMs: Long

        init {
            Preconditions.checkArgument(exclusionDurationMs >= 0)
            this.type = type
            this.exclusionDurationMs = exclusionDurationMs
        }
    }

    fun getFallbackSelectionFor(
        fallbackOptions: FallbackOptions, loadErrorInfo: LoadErrorInfo
    ): FallbackSelection?

    fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long
    fun onLoadTaskConcluded(loadTaskId: Long) {}
    fun getMinimumLoadableRetryCount(dataType: Int): Int

    companion object {
        const val FALLBACK_TYPE_LOCATION: Int = 1
        const val FALLBACK_TYPE_TRACK: Int = 2
    }
}
