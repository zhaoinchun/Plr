package androidx.media3.exoplayer.trackselection

import android.os.SystemClock
import androidx.annotation.CallSuper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup

import androidx.media3.common.util.Util.addWithOverflowDefault
import androidx.media3.exoplayer.source.chunk.MediaChunk
import com.google.common.base.Preconditions
import java.util.Arrays
import kotlin.math.max

abstract class BaseTrackSelection(
    val group: TrackGroup,
    tracks: IntArray,
    override val type: @TrackSelection.Type Int = TrackSelection.TYPE_UNSET,
    formatComparator: Comparator<Format> = DEFAULT_FORMAT_COMPARATOR
) : ExoTrackSelection {
    protected val length: Int = tracks.size
    protected val tracks: IntArray
    private val formats: Array<Format>
    private val excludeUntilTimes: LongArray = LongArray(length)
    private var hashCode = 0
    protected var playWhenReady: Boolean = false
        private set

    init {
        Preconditions.checkState(tracks.isNotEmpty())
        formats = Array(tracks.size) {
            group.getFormat(tracks[it])
        }
        Arrays.sort(formats, formatComparator)

        // Set the format indices in the same order.
        this.tracks = IntArray(length) {
            group.indexOf(formats[it])
        }
    }

    override val trackGroup: TrackGroup
        get() = group

    override fun length(): Int {
        return tracks.size
    }

    override fun getFormat(index: Int): Format {
        return formats[index]
    }

    override fun getIndexInTrackGroup(index: Int): Int {
        return tracks[index]
    }

    override fun indexOf(format: Format): Int {
        val index = formats.indexOfFirst { it === format }
        return if (index == -1) C.INDEX_UNSET else index
    }

    override fun indexOf(indexInTrackGroup: Int): Int {
        val index = tracks.indexOfFirst { it == indexInTrackGroup }
        return if (index == -1) C.INDEX_UNSET else index
    }

    override val selectedFormat: Format
        get() = formats[selectedIndex]

    override val selectedIndexInTrackGroup: Int
        get() = tracks[selectedIndex]

    override fun enable() {
        // Do nothing.
    }

    override fun disable() {
        // Do nothing.
    }

    override fun onPlaybackSpeed(playbackSpeed: Float) {
        // Do nothing.
    }

    override fun evaluateQueueSize(playbackPositionUs: Long, queue: List<MediaChunk>): Int {
        return queue.size
    }

    override fun excludeTrack(index: Int, exclusionDurationMs: Long): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        var canExclude = isTrackExcluded(index, nowMs)
        var i = 0
        while (i < length && !canExclude) {
            canExclude = i != index && !isTrackExcluded(i, nowMs)
            i++
        }
        if (!canExclude) {
            return false
        }
        excludeUntilTimes[index] =
            max(
                excludeUntilTimes[index],
                addWithOverflowDefault(nowMs, exclusionDurationMs, Long.MAX_VALUE)
            )
        return true
    }

    override fun isTrackExcluded(index: Int, nowMs: Long): Boolean {
        return excludeUntilTimes[index] > nowMs
    }

    override fun clearTrackExclusions() {
        Arrays.fill(excludeUntilTimes, 0)
    }

    @CallSuper
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        this.playWhenReady = playWhenReady
    }

    // Object overrides.
    override fun hashCode(): Int {
        if (hashCode == 0) {
            hashCode = 31 * System.identityHashCode(group) + tracks.contentHashCode()
        }
        return hashCode
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as BaseTrackSelection
        return group == other.group && tracks.contentEquals(other.tracks)
    }

    companion object {
        val DEFAULT_FORMAT_COMPARATOR: Comparator<Format> =
            Comparator { firstFormat: Format, secondFormat: Format ->
                secondFormat.bitrate.compareTo(firstFormat.bitrate)
            }
    }
}
