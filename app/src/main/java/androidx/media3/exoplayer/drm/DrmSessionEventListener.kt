package androidx.media3.exoplayer.drm

import android.os.Handler

import androidx.media3.common.util.Util.postOrRun
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import java.util.concurrent.CopyOnWriteArrayList

interface DrmSessionEventListener {
    fun onDrmSessionAcquired(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        @DrmSession.State state: @DrmSession.State Int
    ) {
    }

    @Deprecated("Implement {@link #onDrmKeysLoaded(int, MediaPeriodId, KeyRequestInfo)} instead")
    fun onDrmKeysLoaded(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
    }

    fun onDrmKeysLoaded(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, keyRequestInfo: KeyRequestInfo
    ) {
    }

    fun onDrmSessionManagerError(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, error: Exception
    ) {
    }

    fun onDrmKeysRestored(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {}
    fun onDrmKeysRemoved(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {}
    fun onDrmSessionReleased(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {}

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

        fun addEventListener(handler: Handler, eventListener: DrmSessionEventListener) {
            listenerAndHandlers.add(ListenerAndHandler(handler, eventListener))
        }

        fun removeEventListener(eventListener: DrmSessionEventListener) {
            for (listenerAndHandler in listenerAndHandlers) {
                if (listenerAndHandler.listener === eventListener) {
                    listenerAndHandlers.remove(listenerAndHandler)
                }
            }
        }

        fun drmSessionAcquired(@DrmSession.State state: @DrmSession.State Int) {
            for (listenerAndHandler in listenerAndHandlers) {
                val listener = listenerAndHandler.listener
                postOrRun(
                    listenerAndHandler.handler,
                    {
                        listener.onDrmSessionAcquired(windowIndex, mediaPeriodId, state)
                    })
            }
        }

        fun drmKeysLoaded(keyRequestInfo: KeyRequestInfo) {
            for (listenerAndHandler in listenerAndHandlers) {
                val listener = listenerAndHandler.listener
                postOrRun(
                    listenerAndHandler.handler
                ) {
                    listener.onDrmKeysLoaded(windowIndex, mediaPeriodId)
                    listener.onDrmKeysLoaded(windowIndex, mediaPeriodId, keyRequestInfo)
                }
            }
        }

        fun drmSessionManagerError(error: Exception) {
            for (listenerAndHandler in listenerAndHandlers) {
                val listener = listenerAndHandler.listener
                postOrRun(
                    listenerAndHandler.handler
                ) {
                    listener.onDrmSessionManagerError(windowIndex, mediaPeriodId, error)
                }
            }
        }

        fun drmKeysRestored() {
            for (listenerAndHandler in listenerAndHandlers) {
                val listener = listenerAndHandler.listener
                postOrRun(listenerAndHandler.handler) {
                    listener.onDrmKeysRestored(windowIndex, mediaPeriodId)
                }
            }
        }

        fun drmKeysRemoved() {
            for (listenerAndHandler in listenerAndHandlers) {
                val listener = listenerAndHandler.listener
                postOrRun(listenerAndHandler.handler) {
                    listener.onDrmKeysRemoved(windowIndex, mediaPeriodId)
                }
            }
        }

        fun drmSessionReleased() {
            for (listenerAndHandler in listenerAndHandlers) {
                val listener = listenerAndHandler.listener
                postOrRun(
                    listenerAndHandler.handler,
                    { listener.onDrmSessionReleased(windowIndex, mediaPeriodId) })
            }
        }

        private class ListenerAndHandler(
            var handler: Handler,
            var listener: DrmSessionEventListener
        )
    }
}
