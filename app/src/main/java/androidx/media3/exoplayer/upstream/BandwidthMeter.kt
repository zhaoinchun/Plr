package androidx.media3.exoplayer.upstream

import android.os.Handler
import androidx.media3.common.C

import androidx.media3.datasource.TransferListener
import com.google.common.base.Preconditions
import java.util.concurrent.CopyOnWriteArrayList

interface BandwidthMeter {
    interface EventListener {
        fun onBandwidthSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long)

        class EventDispatcher {
            private val listeners: CopyOnWriteArrayList<HandlerAndListener>
            init {
                listeners = CopyOnWriteArrayList<HandlerAndListener>()
            }

            fun addListener(eventHandler: Handler, eventListener: EventListener) {
                removeListener(eventListener)
                listeners.add(HandlerAndListener(eventHandler, eventListener))
            }

            fun removeListener(eventListener: EventListener) {
                for (handlerAndListener in listeners) {
                    if (handlerAndListener.listener === eventListener) {
                        handlerAndListener.release()
                        listeners.remove(handlerAndListener)
                    }
                }
            }

            fun bandwidthSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long) {
                for (handlerAndListener in listeners) {
                    if (!handlerAndListener.released) {
                        handlerAndListener.handler.post(
                            Runnable {
                                handlerAndListener.listener.onBandwidthSample(
                                    elapsedMs, bytesTransferred, bitrateEstimate
                                )
                            })
                    }
                }
            }

            private class HandlerAndListener(
                val handler: Handler,
                val listener: EventListener
            ) {
                var released = false

                fun release() {
                    released = true
                }
            }
        }
    }

    val bitrateEstimate: Long
    val timeToFirstByteEstimateUs: Long
        get() = C.TIME_UNSET
    val transferListener: TransferListener?

    fun addEventListener(eventHandler: Handler, eventListener: EventListener)
    fun removeEventListener(eventListener: EventListener)

    companion object {
        val NO_OP: BandwidthMeter = object : BandwidthMeter {
            override val bitrateEstimate: Long = 0
            override val transferListener: TransferListener? = null
            override fun addEventListener(eventHandler: Handler, eventListener: EventListener) {}
            override fun removeEventListener(eventListener: EventListener) {}
        }
    }
}
