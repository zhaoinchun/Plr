package androidx.media3.exoplayer.source

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator

abstract class WrappingMediaSource(
    protected val mediaSource: MediaSource
) : CompositeMediaSource<Void>() {
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        prepareSourceInternal()
    }

    protected open fun prepareSourceInternal() {
        prepareChildSource()
    }

    override val initialTimeline: Timeline?
        get() = mediaSource.initialTimeline

    override val isSingleWindow: Boolean
        get() = mediaSource.isSingleWindow

    override val mediaItem: MediaItem
        get() = mediaSource.mediaItem

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return mediaSource.canUpdateMediaItem(mediaItem)
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        mediaSource.updateMediaItem(mediaItem)
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        return mediaSource.createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod(mediaPeriod)
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Void?, mediaSource: MediaSource, newTimeline: Timeline
    ) {
        onChildSourceInfoRefreshed(newTimeline)
    }

    protected open fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        refreshSourceInfo(newTimeline)
    }

    override fun getWindowIndexForChildWindowIndex(childSourceId: Void?, windowIndex: Int): Int {
        return getWindowIndexForChildWindowIndex(windowIndex)
    }

    protected fun getWindowIndexForChildWindowIndex(windowIndex: Int): Int {
        return windowIndex
    }

    override fun getMediaPeriodIdForChildMediaPeriodId(
        childSourceId: Void?, mediaPeriodId: MediaPeriodId
    ): MediaPeriodId? {
        return getMediaPeriodIdForChildMediaPeriodId(mediaPeriodId)
    }
    protected open fun getMediaPeriodIdForChildMediaPeriodId(mediaPeriodId: MediaPeriodId): MediaPeriodId? {
        return mediaPeriodId
    }

    override fun getMediaTimeForChildMediaTime(
        childSourceId: Void?, mediaTimeMs: Long, mediaPeriodId: MediaPeriodId?
    ): Long {
        return getMediaTimeForChildMediaTime(mediaTimeMs, mediaPeriodId)
    }

    protected fun getMediaTimeForChildMediaTime(
        mediaTimeMs: Long, mediaPeriodId: MediaPeriodId?
    ): Long {
        return mediaTimeMs
    }

    protected fun prepareChildSource() {
        prepareChildSource(CHILD_SOURCE_ID, mediaSource)
    }

    protected fun enableChildSource() {
        enableChildSource(CHILD_SOURCE_ID)
    }

    protected fun disableChildSource() {
        disableChildSource(CHILD_SOURCE_ID)
    }

    protected fun releaseChildSource() {
        releaseChildSource(CHILD_SOURCE_ID)
    }

    companion object {
        private val CHILD_SOURCE_ID: Void? = null
    }
}
