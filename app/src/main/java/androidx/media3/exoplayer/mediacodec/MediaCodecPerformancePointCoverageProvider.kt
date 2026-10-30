package androidx.media3.exoplayer.mediacodec

import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.os.Build
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException

internal object MediaCodecPerformancePointCoverageProvider {
    private var shouldIgnorePerformancePoints:Boolean = false
    const val COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED: Int = 0
    const val COVERAGE_RESULT_NO: Int = 1
    const val COVERAGE_RESULT_YES: Int = 2

    fun areResolutionAndFrameRateCovered(
        videoCapabilities: VideoCapabilities, width: Int, height: Int, frameRate: Double
    ): @PerformancePointCoverageResult Int {
        if (Build.VERSION.SDK_INT < 29 || (shouldIgnorePerformancePoints)) {
            return COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED
        }
        return Api29.areResolutionAndFrameRateCovered(videoCapabilities, width, height, frameRate)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED, COVERAGE_RESULT_NO, COVERAGE_RESULT_YES]
    )
    internal annotation class PerformancePointCoverageResult

    @RequiresApi(29)
    private object Api29 {
        fun areResolutionAndFrameRateCovered(
            videoCapabilities: VideoCapabilities, width: Int, height: Int, frameRate: Double
        ): @PerformancePointCoverageResult Int {
            val performancePointList =
                videoCapabilities.supportedPerformancePoints
            if (performancePointList.isNullOrEmpty()) {
                return COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED
            }

            // Round frame rate down to to avoid situations where a range check in
            // covers fails due to slightly exceeding the limits for a standard format
            // (e.g., 1080p at 30 fps). [Internal ref: b/134706676]
            val targetPerformancePoint =
                PerformancePoint(width, height, frameRate.toInt())

            val performancePointCoverageResult: @PerformancePointCoverageResult Int =
                evaluatePerformancePointCoverage(performancePointList, targetPerformancePoint)

            if (performancePointCoverageResult == COVERAGE_RESULT_NO
                && shouldIgnorePerformancePoints == null
            ) {
                // See https://github.com/google/ExoPlayer/issues/10898,
                // https://github.com/androidx/media/issues/693,
                // https://github.com/androidx/media/issues/966 and [internal ref: b/267324685].
                shouldIgnorePerformancePoints = shouldIgnorePerformancePoints()
                if (shouldIgnorePerformancePoints) {
                    return COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED
                }
            }
            return performancePointCoverageResult
        }

        fun shouldIgnorePerformancePoints(): Boolean {
            if (Build.VERSION.SDK_INT >= 37) {
                // The same check as below is tested in CTS and we should get reliable results from API 37.
                return false
            }
            val h264SecureRequiredSupportResult: @PerformancePointCoverageResult Int =
                evaluateH264RequiredSupport( true)
            if (Build.VERSION.SDK_INT >= 35) {
                // From API 35, only non-secure codecs are tested in CTS and we still need to check the
                // secure codec is correctly marked as supported (or not defined at all).
                return h264SecureRequiredSupportResult == COVERAGE_RESULT_NO
            }
            // Below API 35, we accept the performance points if the non-secure ones are correctly defined
            // and the secure ones are either undefined or correctly marked as supported.
            val h264RequiredSupportResult: @PerformancePointCoverageResult Int =
                evaluateH264RequiredSupport( false)
            return h264RequiredSupportResult != COVERAGE_RESULT_YES
                    || h264SecureRequiredSupportResult == COVERAGE_RESULT_NO
        }

        fun evaluateH264RequiredSupport(
            requiresSecureDecoder: Boolean
        ): @PerformancePointCoverageResult Int {
            try {
                val formatH264 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
                // Null check required to pass RequiresNonNull annotation on getDecoderInfosSoftMatch.
                if (formatH264.sampleMimeType != null) {
                    val decoderInfos =
                        MediaCodecUtil.getDecoderInfosSoftMatch(
                            MediaCodecSelector.DEFAULT,
                            formatH264,  
                            requiresSecureDecoder,  
                            false
                        )
                    for (i in decoderInfos.indices) {
                        if (decoderInfos[i].capabilities != null) {
                            val videoCapabilities =
                                decoderInfos[i].capabilities!!.videoCapabilities
                            if (videoCapabilities != null) {
                                val performancePointListH264 = videoCapabilities.supportedPerformancePoints
                                if (!performancePointListH264.isNullOrEmpty()) {
                                    val targetPerformancePointH264 =
                                        PerformancePoint(1280, 720, 60)
                                    return evaluatePerformancePointCoverage(
                                        performancePointListH264, targetPerformancePointH264
                                    )
                                }
                            }
                        }
                    }
                }
                return COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED
            } catch (ignored: DecoderQueryException) {
                return COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED
            }
        }

        fun evaluatePerformancePointCoverage(
            performancePointList: List<PerformancePoint?>,
            targetPerformancePoint: PerformancePoint
        ): @PerformancePointCoverageResult Int {
            for (i in performancePointList.indices) {
                if (performancePointList[i]!!.covers(targetPerformancePoint)) {
                    return COVERAGE_RESULT_YES
                }
            }
            return COVERAGE_RESULT_NO
        }
    }
}
