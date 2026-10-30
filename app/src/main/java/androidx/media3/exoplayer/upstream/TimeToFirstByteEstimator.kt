package androidx.media3.exoplayer.upstream

import androidx.media3.datasource.DataSpec

interface TimeToFirstByteEstimator {
    val timeToFirstByteEstimateUs: Long
    fun reset()
    fun onTransferInitializing(dataSpec: DataSpec)
    fun onTransferStart(dataSpec: DataSpec)
}
