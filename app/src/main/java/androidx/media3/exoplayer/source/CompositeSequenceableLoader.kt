package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.exoplayer.LoadingInfo
import kotlin.math.min

class CompositeSequenceableLoader(
    loaders: List<SequenceableLoader>,
    loaderTrackTypes: List<List<Int>>
) : SequenceableLoader {
    private val loadersWithTrackTypes: List<SequenceableLoaderWithTrackTypes> = List(loaders.size) {
        SequenceableLoaderWithTrackTypes(loaders[it], loaderTrackTypes[it])
    }
    private var lastAudioVideoBufferedPositionUs = C.TIME_UNSET

    override val bufferedPositionUs: Long
        get() {
            var bufferedPositionUs = Long.MAX_VALUE
            var bufferedPositionAudioVideoUs = Long.MAX_VALUE
            for (i in loadersWithTrackTypes.indices) {
                val loader = loadersWithTrackTypes[i]
                val loaderBufferedPositionUs = loader.bufferedPositionUs

                if (loader.trackTypes.contains(C.TRACK_TYPE_AUDIO)
                    || loader.trackTypes.contains(C.TRACK_TYPE_VIDEO)
                    || loader.trackTypes.contains(C.TRACK_TYPE_IMAGE)
                ) {
                    if (loaderBufferedPositionUs != C.TIME_END_OF_SOURCE) {
                        bufferedPositionAudioVideoUs =
                            min(bufferedPositionAudioVideoUs, loaderBufferedPositionUs)
                    }
                }
                if (loaderBufferedPositionUs != C.TIME_END_OF_SOURCE) {
                    bufferedPositionUs = min(bufferedPositionUs, loaderBufferedPositionUs)
                }
            }
            if (bufferedPositionAudioVideoUs != Long.MAX_VALUE) {
                lastAudioVideoBufferedPositionUs = bufferedPositionAudioVideoUs
                return bufferedPositionAudioVideoUs
            } else if (bufferedPositionUs != Long.MAX_VALUE) {
                // If lastAudioVideoBufferedPositionUs != C.TIME_UNSET, then we know there's at least one a/v
                // track (because this is the only time we end up assigning lastAudioVideoBufferedPositionUs
                // on a previous invocation).
                return if (lastAudioVideoBufferedPositionUs != C.TIME_UNSET)
                    lastAudioVideoBufferedPositionUs
                else
                    bufferedPositionUs
            } else {
                return C.TIME_END_OF_SOURCE
            }
        }

    override val nextLoadPositionUs: Long
        get() {
            var nextLoadPositionUs = Long.MAX_VALUE
            for (i in loadersWithTrackTypes.indices) {
                val loader = loadersWithTrackTypes[i]
                val loaderNextLoadPositionUs = loader.nextLoadPositionUs
                if (loaderNextLoadPositionUs != C.TIME_END_OF_SOURCE) {
                    nextLoadPositionUs = min(nextLoadPositionUs, loaderNextLoadPositionUs)
                }
            }
            return if (nextLoadPositionUs == Long.MAX_VALUE) C.TIME_END_OF_SOURCE else nextLoadPositionUs
        }

    override fun reevaluateBuffer(positionUs: Long) {
        for (i in loadersWithTrackTypes.indices) {
            loadersWithTrackTypes[i].reevaluateBuffer(positionUs)
        }
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        var madeProgress = false
        var madeProgressThisIteration: Boolean
        do {
            madeProgressThisIteration = false
            val nextLoadPositionUs = nextLoadPositionUs
            if (nextLoadPositionUs == C.TIME_END_OF_SOURCE) {
                break
            }
            for (i in loadersWithTrackTypes.indices) {
                val loaderNextLoadPositionUs = loadersWithTrackTypes[i].nextLoadPositionUs
                val isLoaderBehind =
                    loaderNextLoadPositionUs != C.TIME_END_OF_SOURCE
                            && loaderNextLoadPositionUs <= loadingInfo.playbackPositionUs
                if (loaderNextLoadPositionUs == nextLoadPositionUs || isLoaderBehind) {
                    madeProgressThisIteration =
                        madeProgressThisIteration or loadersWithTrackTypes[i]
                            .continueLoading(loadingInfo)
                }
            }
            madeProgress = madeProgress or madeProgressThisIteration
        } while (madeProgressThisIteration)
        return madeProgress
    }

    override val isLoading: Boolean
        get() {
            for (i in loadersWithTrackTypes.indices) {
                if (loadersWithTrackTypes[i].isLoading) {
                    return true
                }
            }
            return false
        }

    private class SequenceableLoaderWithTrackTypes(
        private val loader: SequenceableLoader,
        trackTypes: List<Int>
    ) : SequenceableLoader by loader {
        val trackTypes: List<Int> = trackTypes.toList()
    }
}
