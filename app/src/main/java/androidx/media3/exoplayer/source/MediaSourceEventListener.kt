package androidx.media3.exoplayer.source

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Util.postOrRun
import androidx.media3.common.util.Util.usToMs
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

interface MediaSourceEventListener {
    fun onLoadStarted(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int
    ) {
    }

    fun onLoadCompleted(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
    }

    fun onLoadCanceled(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
    }

    fun onLoadError(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
    }

    fun onUpstreamDiscarded(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
    ) {
    }

    fun onDownstreamFormatChanged(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
    ) {
    }

    class EventDispatcher private constructor(
        private val listenerAndHandlers: CopyOnWriteArrayList<ListenerAndHandler>,
        val windowIndex: Int,
        val mediaPeriodId: MediaPeriodId?
    ) {
        constructor() : this(
            CopyOnWriteArrayList<ListenerAndHandler>(),
            0,
            null
        )

        fun withParameters(windowIndex: Int, mediaPeriodId: MediaPeriodId?): EventDispatcher {
            return EventDispatcher(listenerAndHandlers, windowIndex, mediaPeriodId)
        }

        fun addEventListener(handler: Handler, eventListener: MediaSourceEventListener) {
            listenerAndHandlers.add(ListenerAndHandler(handler, eventListener))
        }

        fun removeEventListener(eventListener: MediaSourceEventListener) {
            for (listenerAndHandler in listenerAndHandlers) {
                if (listenerAndHandler.listener === eventListener) {
                    listenerAndHandlers.remove(listenerAndHandler)
                }
            }
        }

        fun loadStarted(loadEventInfo: LoadEventInfo, dataType: @C.DataType Int, retryCount: Int) {
            loadStarted(
                loadEventInfo,
                dataType,  
                C.TRACK_TYPE_UNKNOWN,  
                null,  
                C.SELECTION_REASON_UNKNOWN,  
                null,  
                C.TIME_UNSET,  
                C.TIME_UNSET,
                retryCount
            )
        }

        fun loadStarted(
            loadEventInfo: LoadEventInfo,
            dataType: @C.DataType Int,
            trackType: @C.TrackType Int,
            trackFormat: Format?,
            trackSelectionReason: @C.SelectionReason Int,
            trackSelectionData: Any?,
            mediaStartTimeUs: Long,
            mediaEndTimeUs: Long,
            retryCount: Int
        ) {
            loadStarted(
                loadEventInfo,
                MediaLoadData(
                    dataType,
                    trackType,
                    trackFormat,
                    trackSelectionReason,
                    trackSelectionData,
                    usToMs(mediaStartTimeUs),
                    usToMs(mediaEndTimeUs)
                ),
                retryCount
            )
        }

        fun loadStarted(
            loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, retryCount: Int
        ) {
            dispatchEvent { listener: MediaSourceEventListener ->
                listener.onLoadStarted(
                    windowIndex, mediaPeriodId, loadEventInfo, mediaLoadData, retryCount
                )
            }
        }

        fun loadCompleted(
            loadEventInfo: LoadEventInfo,
            dataType: @C.DataType Int,
            trackType: @C.TrackType Int = C.TRACK_TYPE_UNKNOWN,
            trackFormat: Format? = null,
            trackSelectionReason: @C.SelectionReason Int = C.SELECTION_REASON_UNKNOWN,
            trackSelectionData: Any? = null,
            mediaStartTimeUs: Long = C.TIME_UNSET,
            mediaEndTimeUs: Long = C.TIME_UNSET
        ) {
            loadCompleted(
                loadEventInfo,
                MediaLoadData(
                    dataType,
                    trackType,
                    trackFormat,
                    trackSelectionReason,
                    trackSelectionData,
                    usToMs(mediaStartTimeUs),
                    usToMs(mediaEndTimeUs)
                )
            )
        }

        fun loadCompleted(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            dispatchEvent { listener: MediaSourceEventListener ->
                listener.onLoadCompleted(
                    windowIndex,
                    mediaPeriodId,
                    loadEventInfo,
                    mediaLoadData
                )
            }
        }

        fun loadCanceled(
            loadEventInfo: LoadEventInfo,
            dataType: @C.DataType Int,
            trackType: @C.TrackType Int = C.TRACK_TYPE_UNKNOWN,
            trackFormat: Format? = null,
            trackSelectionReason: @C.SelectionReason Int = C.SELECTION_REASON_UNKNOWN,
            trackSelectionData: Any? = null,
            mediaStartTimeUs: Long = C.TIME_UNSET,
            mediaEndTimeUs: Long = C.TIME_UNSET
        ) {
            loadCanceled(
                loadEventInfo,
                MediaLoadData(
                    dataType,
                    trackType,
                    trackFormat,
                    trackSelectionReason,
                    trackSelectionData,
                    usToMs(mediaStartTimeUs),
                    usToMs(mediaEndTimeUs)
                )
            )
        }

        fun loadCanceled(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            dispatchEvent { listener: MediaSourceEventListener ->
                listener.onLoadCanceled(
                    windowIndex,
                    mediaPeriodId,
                    loadEventInfo,
                    mediaLoadData
                )
            }
        }

        fun loadError(
            loadEventInfo: LoadEventInfo,
            dataType: @C.DataType Int,
            error: IOException,
            wasCanceled: Boolean
        ) {
            loadError(
                loadEventInfo,
                dataType,  
                C.TRACK_TYPE_UNKNOWN,  
                null,  
                C.SELECTION_REASON_UNKNOWN,  
                null,  
                C.TIME_UNSET,  
                C.TIME_UNSET,
                error,
                wasCanceled
            )
        }

        fun loadError(
            loadEventInfo: LoadEventInfo,
            dataType: @C.DataType Int,
            trackType: @C.TrackType Int,
            trackFormat: Format?,
            trackSelectionReason: @C.SelectionReason Int,
            trackSelectionData: Any?,
            mediaStartTimeUs: Long,
            mediaEndTimeUs: Long,
            error: IOException,
            wasCanceled: Boolean
        ) {
            loadError(
                loadEventInfo,
                MediaLoadData(
                    dataType,
                    trackType,
                    trackFormat,
                    trackSelectionReason,
                    trackSelectionData,
                    usToMs(mediaStartTimeUs),
                    usToMs(mediaEndTimeUs)
                ),
                error,
                wasCanceled
            )
        }

        fun loadError(
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            dispatchEvent { listener: MediaSourceEventListener ->
                listener.onLoadError(
                    windowIndex, mediaPeriodId, loadEventInfo, mediaLoadData, error, wasCanceled
                )
            }
        }

        fun upstreamDiscarded(trackType: Int, mediaStartTimeUs: Long, mediaEndTimeUs: Long) {
            upstreamDiscarded(
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    trackType,
                    null,
                    C.SELECTION_REASON_ADAPTIVE,  
                    null,
                    usToMs(mediaStartTimeUs),
                    usToMs(mediaEndTimeUs)
                )
            )
        }

        fun upstreamDiscarded(mediaLoadData: MediaLoadData) {
            val mediaPeriodId = mediaPeriodId!!
            dispatchEvent { listener: MediaSourceEventListener ->
                listener.onUpstreamDiscarded(
                    windowIndex,
                    mediaPeriodId,
                    mediaLoadData
                )
            }
        }

        fun downstreamFormatChanged(
            trackType: @C.TrackType Int,
            trackFormat: Format?,
            trackSelectionReason: @C.SelectionReason Int,
            trackSelectionData: Any?,
            mediaTimeUs: Long
        ) {
            downstreamFormatChanged(
                MediaLoadData(
                    C.DATA_TYPE_MEDIA,
                    trackType,
                    trackFormat,
                    trackSelectionReason,
                    trackSelectionData,
                    usToMs(mediaTimeUs),
                    C.TIME_UNSET
                )
            )
        }

        fun downstreamFormatChanged(mediaLoadData: MediaLoadData) {
            dispatchEvent { listener: MediaSourceEventListener ->
                listener.onDownstreamFormatChanged(
                    windowIndex,
                    mediaPeriodId,
                    mediaLoadData
                )
            }
        }

        fun dispatchEvent(event: (MediaSourceEventListener) -> Unit) {
            for (listenerAndHandler in listenerAndHandlers) {
                val listener = listenerAndHandler.listener
                postOrRun(listenerAndHandler.handler) { event(listener) }
            }
        }

        private class ListenerAndHandler(
            var handler: Handler,
            var listener: MediaSourceEventListener
        )
    }
}
