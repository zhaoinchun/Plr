package androidx.media3.exoplayer.source

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.base.Preconditions

abstract class BaseMediaSource : MediaSource {
    private val mediaSourceCallers: ArrayList<MediaSourceCaller> = ArrayList(1)
    private val enabledMediaSourceCallers: HashSet<MediaSourceCaller> = HashSet(1)
    private val eventDispatcher = MediaSourceEventListener.EventDispatcher()
    private val drmEventDispatcher = DrmSessionEventListener.EventDispatcher()

    private var looper: Looper? = null
    private var timeline: Timeline? = null
    private var playerId: PlayerId? = null
    private var bandwidthMeter: BandwidthMeter? = null

    protected abstract fun prepareSourceInternal(mediaTransferListener: TransferListener?)
    protected open fun enableInternal() {}
    protected open fun disableInternal() {}
    protected abstract fun releaseSourceInternal()
    protected fun refreshSourceInfo(timeline: Timeline) {
        this.timeline = timeline
        for (caller in mediaSourceCallers) {
            caller.onSourceInfoRefreshed(this, timeline)
        }
    }

    protected fun createEventDispatcher(mediaPeriodId: MediaPeriodId?): MediaSourceEventListener.EventDispatcher {
        return eventDispatcher.withParameters(0, mediaPeriodId)
    }

    protected fun createEventDispatcher(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?
    ): MediaSourceEventListener.EventDispatcher {
        return eventDispatcher.withParameters(windowIndex, mediaPeriodId)
    }

    protected fun createDrmEventDispatcher(
        mediaPeriodId: MediaPeriodId?
    ): DrmSessionEventListener.EventDispatcher {
        return drmEventDispatcher.withParameters(0, mediaPeriodId)
    }

    protected fun createDrmEventDispatcher(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?
    ): DrmSessionEventListener.EventDispatcher {
        return drmEventDispatcher.withParameters(windowIndex, mediaPeriodId)
    }

    protected val isEnabled: Boolean
        get() = !enabledMediaSourceCallers.isEmpty()

    protected fun getPlayerId(): PlayerId {
        return playerId!!
    }

    protected fun setPlayerId(playerId: PlayerId) {
        this.playerId = playerId
    }

    protected fun getBandwidthMeter(): BandwidthMeter {
        return bandwidthMeter!!
    }

    protected fun setBandwidthMeter(bandwidthMeter: BandwidthMeter) {
        this.bandwidthMeter = bandwidthMeter
    }

    protected fun prepareSourceCalled(): Boolean {
        return !mediaSourceCallers.isEmpty()
    }

    override fun addEventListener(handler: Handler, eventListener: MediaSourceEventListener) {
        eventDispatcher.addEventListener(handler, eventListener)
    }

    override fun removeEventListener(eventListener: MediaSourceEventListener) {
        eventDispatcher.removeEventListener(eventListener)
    }

    override fun addDrmEventListener(handler: Handler, eventListener: DrmSessionEventListener) {
        drmEventDispatcher.addEventListener(handler, eventListener)
    }

    override fun removeDrmEventListener(eventListener: DrmSessionEventListener) {
        drmEventDispatcher.removeEventListener(eventListener)
    }

    override fun prepareSource(
        caller: MediaSourceCaller, playerId: PlayerId, bandwidthMeter: BandwidthMeter
    ) {
        val looper = Looper.myLooper()
        Preconditions.checkArgument(this.looper == null || this.looper == looper)
        this.playerId = playerId
        this.bandwidthMeter = bandwidthMeter
        val timeline = this.timeline
        mediaSourceCallers.add(caller)
        if (this.looper == null) {
            this.looper = looper
            enabledMediaSourceCallers.add(caller)
            prepareSourceInternal(bandwidthMeter.transferListener)
        } else if (timeline != null) {
            enable(caller)
            caller.onSourceInfoRefreshed(this, timeline)
        }
    }

    override fun enable(caller: MediaSourceCaller) {
        looper!!
        val wasDisabled = enabledMediaSourceCallers.isEmpty()
        enabledMediaSourceCallers.add(caller)
        if (wasDisabled) {
            enableInternal()
        }
    }

    override fun disable(caller: MediaSourceCaller) {
        val wasEnabled = !enabledMediaSourceCallers.isEmpty()
        enabledMediaSourceCallers.remove(caller)
        if (wasEnabled && enabledMediaSourceCallers.isEmpty()) {
            disableInternal()
        }
    }

    override fun releaseSource(caller: MediaSourceCaller) {
        mediaSourceCallers.remove(caller)
        if (mediaSourceCallers.isEmpty()) {
            looper = null
            timeline = null
            playerId = null
            enabledMediaSourceCallers.clear()
            releaseSourceInternal()
        } else {
            disable(caller)
        }
    }
}
