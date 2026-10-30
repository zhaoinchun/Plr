package androidx.media3.extractor.mp3

import androidx.media3.common.C
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekPoint
import kotlin.math.max

class ConstantBitrateSeeker private constructor(
    inputLength: Long,
    firstFramePosition: Long,
    bitrate: Int,
    frameSize: Int,
    allowSeeksIfLengthUnknown: Boolean,
    isEstimated: Boolean,
    var _durationUs: Long
) : ConstantBitrateSeekMap(
    inputLength,
    firstFramePosition,
    bitrate,
    frameSize,
    allowSeeksIfLengthUnknown,
    isEstimated
), Seeker {
    override val dataEndPosition: Long

    constructor(
        inputLength: Long,
        firstFramePosition: Long,
        mpegAudioHeader: MpegAudioUtil.Header,
        allowSeeksIfLengthUnknown: Boolean
    ) : this(
        inputLength,
        firstFramePosition,
        mpegAudioHeader.bitrate,
        mpegAudioHeader.frameSize,
        allowSeeksIfLengthUnknown,  
        C.TIME_UNSET
    )

    constructor(
        inputLength: Long,
        firstFramePosition: Long,
        bitrate: Int,
        frameSize: Int,
        allowSeeksIfLengthUnknown: Boolean,
        durationUs: Long
    ) : this(
        inputLength,
        firstFramePosition,
        bitrate,
        frameSize,
        allowSeeksIfLengthUnknown,  
        true,
        durationUs
    )

    init {
        dataEndPosition =
            if (inputLength != C.LENGTH_UNSET.toLong()) inputLength else C.INDEX_UNSET.toLong()
    }

    override fun getTimeUs(position: Long): Long {
        return getTimeUsAtPosition(position)
    }

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        if (_durationUs != C.TIME_UNSET && timeUs >= _durationUs && dataEndPosition != C.INDEX_UNSET.toLong()) {
            val finalFramePosition = max(firstFramePosition, dataEndPosition - frameSize)
            val frameDurationUs = getTimeUsAtPosition(firstFramePosition + frameSize)
            return SeekPoints(
                SeekPoint(max(0, _durationUs - frameDurationUs), finalFramePosition)
            )
        }
        return super.getSeekPoints(timeUs)
    }

    override val dataStartPosition: Long
        get() = firstFramePosition

    override var durationUs: Long
        get() = if (_durationUs != C.TIME_UNSET) _durationUs else super.durationUs
        set(value) {
            super.durationUs = value
        }

    override val averageBitrate: Int
        get() = bitrate

    fun copyWithNewDataEndPosition(dataEndPosition: Long): ConstantBitrateSeeker {
        return ConstantBitrateSeeker( 
            dataEndPosition,
            firstFramePosition,
            averageBitrate,
            frameSize,
            shouldAllowSeeksIfLengthUnknown(),  
            false,
            _durationUs
        )
    }
}
