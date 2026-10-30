package androidx.media3.exoplayer.source

import androidx.media3.exoplayer.LoadingInfo

interface SequenceableLoader {
    interface Callback<T : SequenceableLoader> {
        fun onContinueLoadingRequested(source: T)
    }

    val bufferedPositionUs: Long
    val nextLoadPositionUs: Long

    fun continueLoading(loadingInfo: LoadingInfo): Boolean

    val isLoading: Boolean

    fun reevaluateBuffer(positionUs: Long)
}
