package androidx.media3.exoplayer.upstream.experimental

import android.os.Handler

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.BandwidthMeter

interface BandwidthEstimator {
    fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener)
    fun removeEventListener(eventListener: BandwidthMeter.EventListener)
    fun onTransferInitializing(source: DataSource)
    fun onTransferStart(source: DataSource)
    fun onBytesTransferred(source: DataSource, bytesTransferred: Int)
    fun onTransferEnd(source: DataSource)
    val bandwidthEstimate: Long

    fun onNetworkTypeChange(newBandwidthEstimate: Long)

    companion object {
        const val ESTIMATE_NOT_AVAILABLE: Long = Long.MIN_VALUE
    }
}
