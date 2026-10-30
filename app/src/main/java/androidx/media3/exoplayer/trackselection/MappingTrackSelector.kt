package androidx.media3.exoplayer.trackselection

import android.util.Pair
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import kotlin.math.max
import kotlin.math.min

abstract class MappingTrackSelector : TrackSelector() {
    class MappedTrackInfo(
        private val rendererNames: Array<String>,
        private val rendererTrackTypes: IntArray,
        private val rendererTrackGroups: Array<TrackGroupArray>,
        private val rendererMixedMimeTypeAdaptiveSupports: IntArray,
        private val rendererFormatSupports: Array<Array<IntArray>>,
         val unmappedTrackGroups: TrackGroupArray
    ) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(
            AnnotationTarget.FIELD,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.VALUE_PARAMETER,
            AnnotationTarget.LOCAL_VARIABLE,
            AnnotationTarget.CLASS,
            AnnotationTarget.TYPE,
            AnnotationTarget.TYPE_PARAMETER
        )
        @IntDef(
            value = [RENDERER_SUPPORT_NO_TRACKS, RENDERER_SUPPORT_UNSUPPORTED_TRACKS, RENDERER_SUPPORT_EXCEEDS_CAPABILITIES_TRACKS, RENDERER_SUPPORT_PLAYABLE_TRACKS]
        )
        annotation class RendererSupport

        val rendererCount = rendererTrackTypes.size

        fun getRendererName(rendererIndex: Int): String {
            return rendererNames[rendererIndex]
        }

        fun getRendererType(rendererIndex: Int): @C.TrackType Int {
            return rendererTrackTypes[rendererIndex]
        }

        fun getTrackGroups(rendererIndex: Int): TrackGroupArray {
            return rendererTrackGroups[rendererIndex]
        }

        @RendererSupport
        fun getRendererSupport(rendererIndex: Int): @RendererSupport Int {
            var bestRendererSupport: @RendererSupport Int = RENDERER_SUPPORT_NO_TRACKS
            val rendererFormatSupport = rendererFormatSupports[rendererIndex]
            for (trackGroupFormatSupport in rendererFormatSupport) {
                for (trackFormatSupport: @RendererCapabilities.Capabilities Int in trackGroupFormatSupport) {
                    val trackRendererSupport: Int
                    when (RendererCapabilities.getFormatSupport(trackFormatSupport)) {
                        C.FORMAT_HANDLED -> return RENDERER_SUPPORT_PLAYABLE_TRACKS
                        C.FORMAT_EXCEEDS_CAPABILITIES -> trackRendererSupport =
                            RENDERER_SUPPORT_EXCEEDS_CAPABILITIES_TRACKS

                        C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_UNSUPPORTED_DRM -> trackRendererSupport =
                            RENDERER_SUPPORT_UNSUPPORTED_TRACKS

                        else -> throw IllegalStateException()
                    }
                    bestRendererSupport = max(bestRendererSupport, trackRendererSupport)
                }
            }
            return bestRendererSupport
        }

        @RendererSupport
        fun getTypeSupport(trackType: @C.TrackType Int): @RendererSupport Int {
            var bestRendererSupport: @RendererSupport Int = RENDERER_SUPPORT_NO_TRACKS
            for (i in 0..<rendererCount) {
                if (rendererTrackTypes[i] == trackType) {
                    bestRendererSupport = max(bestRendererSupport, getRendererSupport(i))
                }
            }
            return bestRendererSupport
        }

        fun getCapabilities(
            rendererIndex: Int,
            groupIndex: Int,
            trackIndex: Int
        ): @RendererCapabilities.Capabilities Int {
            return rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
        }

        @C.FormatSupport
        fun getTrackSupport(
            rendererIndex: Int,
            groupIndex: Int,
            trackIndex: Int
        ): @C.FormatSupport Int {
            return RendererCapabilities.getFormatSupport(
                getCapabilities(rendererIndex, groupIndex, trackIndex)
            )
        }

        fun getAdaptiveSupport(
            rendererIndex: Int, groupIndex: Int, includeCapabilitiesExceededTracks: Boolean
        ): @AdaptiveSupport Int {
            val trackCount = rendererTrackGroups[rendererIndex].get(groupIndex)!!.length
            // Iterate over the tracks in the group, recording the indices of those to consider.
            var trackIndices = IntArray(trackCount)
            var trackIndexCount = 0
            for (i in 0..<trackCount) {
                val fixedSupport: @C.FormatSupport Int =
                    getTrackSupport(rendererIndex, groupIndex, i)
                if (fixedSupport == C.FORMAT_HANDLED
                    || (includeCapabilitiesExceededTracks && fixedSupport == C.FORMAT_EXCEEDS_CAPABILITIES)
                ) {
                    trackIndices[trackIndexCount++] = i
                }
            }
            trackIndices = trackIndices.copyOf()
            return getAdaptiveSupport(rendererIndex, groupIndex, trackIndices)
        }

        fun getAdaptiveSupport(
            rendererIndex: Int, groupIndex: Int, trackIndices: IntArray
        ): @AdaptiveSupport Int {
            var handledTrackCount = 0
            var adaptiveSupport: @AdaptiveSupport Int = RendererCapabilities.ADAPTIVE_SEAMLESS
            var multipleMimeTypes = false
            var firstSampleMimeType: String? = null
            for (i in trackIndices.indices) {
                val trackIndex = trackIndices[i]
                val sampleMimeType =
                    rendererTrackGroups[rendererIndex].get(groupIndex)!!
                        .getFormat(trackIndex).sampleMimeType
                if (handledTrackCount++ == 0) {
                    firstSampleMimeType = sampleMimeType
                } else {
                    multipleMimeTypes = multipleMimeTypes or (firstSampleMimeType != sampleMimeType)
                }
                adaptiveSupport =
                    min(
                        adaptiveSupport,
                        RendererCapabilities.getAdaptiveSupport(
                            rendererFormatSupports[rendererIndex][groupIndex][i]
                        )
                    )
            }
            return if (multipleMimeTypes)
                min(adaptiveSupport, rendererMixedMimeTypeAdaptiveSupports[rendererIndex])
            else
                adaptiveSupport
        }

        companion object {
            const val RENDERER_SUPPORT_NO_TRACKS: Int = 0
            const val RENDERER_SUPPORT_UNSUPPORTED_TRACKS: Int = 1
            const val RENDERER_SUPPORT_EXCEEDS_CAPABILITIES_TRACKS: Int = 2
            const val RENDERER_SUPPORT_PLAYABLE_TRACKS: Int = 3
        }
    }

    var currentMappedTrackInfo: MappedTrackInfo? = null
        private set

    override fun onSelectionActivated(info: Any?) {
        currentMappedTrackInfo = info as MappedTrackInfo?
    }

    @Throws(ExoPlaybackException::class)
    override fun selectTracks(
        rendererCapabilities: Array<RendererCapabilities>,
        trackGroups: TrackGroupArray,
        periodId: MediaPeriodId,
        timeline: Timeline
    ): TrackSelectorResult {
        // Structures into which data will be written during the selection. The extra item at the end
        // of each array is to store data associated with track groups that cannot be associated with
        // any renderer.
        val rendererTrackGroupCounts = IntArray(rendererCapabilities.size + 1)
        val rendererTrackGroups = mutableListOf<Array<TrackGroup?>>()
        val rendererFormatSupports = mutableListOf<Array<IntArray>>()
        repeat(rendererCapabilities.size + 1) {
            rendererTrackGroups.add(arrayOfNulls<TrackGroup>(trackGroups.length))
            rendererFormatSupports.add(Array<IntArray>(trackGroups.length) { intArrayOf() })
        }

        // Determine the extent to which each renderer supports mixed mimeType adaptation.
        val rendererMixedMimeTypeAdaptationSupports: IntArray =
            getMixedMimeTypeAdaptationSupports(rendererCapabilities)

        // Associate each track group to a preferred renderer, and evaluate the support that the
        // renderer provides for each track in the group.
        for (groupIndex in 0..<trackGroups.length) {
            val group = trackGroups.get(groupIndex)
            // Associate the group to a preferred renderer.
            val preferUnassociatedRenderer = group.type == C.TRACK_TYPE_METADATA
            val rendererIndex: Int =
                findRenderer(
                    rendererCapabilities,
                    group,
                    rendererTrackGroupCounts,
                    preferUnassociatedRenderer
                )
            // Evaluate the support that the renderer provides for each track in the group.
            val rendererFormatSupport =
                if (rendererIndex == rendererCapabilities.size)
                    IntArray(group.length)
                else
                    getFormatSupport(rendererCapabilities[rendererIndex], group)
            // Stash the results.
            val rendererTrackGroupCount = rendererTrackGroupCounts[rendererIndex]
            rendererTrackGroups[rendererIndex][rendererTrackGroupCount] = group
            rendererFormatSupports[rendererIndex][rendererTrackGroupCount] = rendererFormatSupport
            rendererTrackGroupCounts[rendererIndex]=rendererTrackGroupCounts[rendererIndex]+1
        }

        // Create a track group array for each renderer, and trim each rendererFormatSupports entry.
        val listRendererTrackGroupArrays = mutableListOf<TrackGroupArray>()
        val rendererNames = Array(rendererCapabilities.size) { "" }
        val rendererTrackTypes = IntArray(rendererCapabilities.size)
        for (i in rendererCapabilities.indices) {
            val rendererTrackGroupCount = rendererTrackGroupCounts[i]
            listRendererTrackGroupArrays.add(
                TrackGroupArray(
                    Util.nullSafeArrayCopy<TrackGroup>(
                        rendererTrackGroups[i],
                        rendererTrackGroupCount
                    ) as Array<TrackGroup>
                )
            )

            rendererFormatSupports[i] = rendererFormatSupports[i]
            rendererNames[i] = rendererCapabilities[i].name
            rendererTrackTypes[i] = rendererCapabilities[i].trackType
        }
        val rendererTrackGroupArrays = listRendererTrackGroupArrays.toTypedArray()

        // Create a track group array for track groups not mapped to a renderer.
        val unmappedTrackGroupCount = rendererTrackGroupCounts[rendererCapabilities.size]
        val rendererTrackGroups2 = rendererTrackGroups[rendererCapabilities.size]
            .copyOf(unmappedTrackGroupCount)
        val unmappedTrackGroupArray =
            TrackGroupArray(rendererTrackGroups2 as Array<TrackGroup> )
        // Package up the track information and selections.
        val mappedTrackInfo =
            MappedTrackInfo(
                rendererNames,
                rendererTrackTypes,
                rendererTrackGroupArrays,
                rendererMixedMimeTypeAdaptationSupports,
                rendererFormatSupports.toTypedArray(),
                unmappedTrackGroupArray
            )

        val result =
            selectTracks(
                mappedTrackInfo,
                rendererFormatSupports.toTypedArray(),
                rendererMixedMimeTypeAdaptationSupports,
                periodId,
                timeline
            )
        val tracks = TrackSelectionUtil.buildTracks(mappedTrackInfo, result.second)

        return TrackSelectorResult(result.first, result.second, tracks, mappedTrackInfo)
    }

    @Throws(ExoPlaybackException::class)
    protected abstract fun selectTracks(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupport: IntArray,
        mediaPeriodId: MediaPeriodId,
        timeline: Timeline
    ): Pair<Array<RendererConfiguration?>, Array<ExoTrackSelection?>>

    companion object {
        @Throws(ExoPlaybackException::class)
        private fun findRenderer(
            rendererCapabilities: Array<RendererCapabilities>,
            group: TrackGroup,
            rendererTrackGroupCounts: IntArray,
            preferUnassociatedRenderer: Boolean
        ): Int {
            var bestRendererIndex = rendererCapabilities.size
            var bestFormatSupportLevel: @C.FormatSupport Int = C.FORMAT_UNSUPPORTED_TYPE
            var bestRendererIsUnassociated = true
            for (rendererIndex in rendererCapabilities.indices) {
                val rendererCapability = rendererCapabilities[rendererIndex]
                var formatSupportLevel: @C.FormatSupport Int = C.FORMAT_UNSUPPORTED_TYPE
                for (trackIndex in 0..<group.length) {
                    val trackFormatSupportLevel: @C.FormatSupport Int =
                        RendererCapabilities.getFormatSupport(
                            rendererCapability.supportsFormat(group.getFormat(trackIndex))
                        )
                    formatSupportLevel = max(formatSupportLevel, trackFormatSupportLevel)
                }
                val rendererIsUnassociated = rendererTrackGroupCounts[rendererIndex] == 0
                if (formatSupportLevel > bestFormatSupportLevel
                    || (formatSupportLevel == bestFormatSupportLevel && preferUnassociatedRenderer
                            && !bestRendererIsUnassociated && rendererIsUnassociated)
                ) {
                    bestRendererIndex = rendererIndex
                    bestFormatSupportLevel = formatSupportLevel
                    bestRendererIsUnassociated = rendererIsUnassociated
                }
            }
            return bestRendererIndex
        }

        @Throws(ExoPlaybackException::class)
        private fun getFormatSupport(
            rendererCapabilities: RendererCapabilities, group: TrackGroup
        ): IntArray {
            val formatSupport = IntArray(group.length) { i ->
                rendererCapabilities.supportsFormat(group.getFormat(i))
            }
            return formatSupport
        }

        @Throws(ExoPlaybackException::class)
        private fun getMixedMimeTypeAdaptationSupports(
            rendererCapabilities: Array<RendererCapabilities>
        ): IntArray {
            val mixedMimeTypeAdaptationSupport = IntArray(rendererCapabilities.size) { i ->
                rendererCapabilities[i].supportsMixedMimeTypeAdaptation()
            }
            return mixedMimeTypeAdaptationSupport
        }
    }
}
