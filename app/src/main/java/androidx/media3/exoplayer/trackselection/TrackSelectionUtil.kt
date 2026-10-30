package androidx.media3.exoplayer.trackselection

import android.graphics.Point
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util.ceilDivide
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

object TrackSelectionUtil {
    fun createTrackSelectionsForDefinitions(
        definitions: Array<ExoTrackSelection.Definition?>,
        adaptiveTrackSelectionFactory: AdaptiveTrackSelectionFactory
    ): Array<ExoTrackSelection?> {
        val selections = arrayOfNulls<ExoTrackSelection>(definitions.size)
        var createdAdaptiveTrackSelection = false
        for (i in definitions.indices) {
            val definition = definitions[i] ?: continue

            if (definition.tracks.size > 1 && !createdAdaptiveTrackSelection) {
                createdAdaptiveTrackSelection = true
                selections[i] =
                    adaptiveTrackSelectionFactory.createAdaptiveTrackSelection(definition)
            } else {
                selections[i] =
                    FixedTrackSelection(definition.group, definition.tracks[0], definition.type)
            }
        }
        return selections
    }

    fun buildTracks(
        mappedTrackInfo: MappedTrackInfo,
        selections: Array<out TrackSelection?>
    ): Tracks {
        val listSelections = Array<List<out TrackSelection>>(selections.size) { i ->
            selections[i]?.let { listOf(it) } ?: listOf()
        }
        return buildTracks(mappedTrackInfo, listSelections)
    }

    fun buildTracks(
        mappedTrackInfo: MappedTrackInfo,
        selections: Array<out List<out TrackSelection>>
    ): Tracks {
        val trackGroups = mutableListOf<Tracks.Group>()
        for (rendererIndex in 0..<mappedTrackInfo.rendererCount) {
            val trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0..<trackGroupArray.length) {
                val trackGroup = trackGroupArray.get(groupIndex)
                val support = mappedTrackInfo.getAdaptiveSupport(
                    rendererIndex, groupIndex, false
                )
                val adaptiveSupported = support != RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
                @C.FormatSupport
                val trackSupport = IntArray(trackGroup!!.length)
                val selected = BooleanArray(trackGroup.length)

                for (trackIndex in 0..<trackGroup.length) {
                    trackSupport[trackIndex] =
                        mappedTrackInfo.getTrackSupport(rendererIndex, groupIndex, trackIndex)
                    var isTrackSelected = false
                    for (selection in selections) {
                        for (j in selection.indices) {
                            val trackSelection: TrackSelection = selection[j]
                            if (trackSelection.trackGroup == trackGroup
                                && trackSelection.indexOf(trackIndex) != C.INDEX_UNSET
                            ) {
                                isTrackSelected = true
                                break
                            }
                        }
                    }
                    selected[trackIndex] = isTrackSelected
                }
                trackGroups.add(
                    Tracks.Group(
                        trackGroup,
                        adaptiveSupported,
                        trackSupport,
                        selected
                    )
                )
            }
        }
        val unmappedTrackGroups = mappedTrackInfo.unmappedTrackGroups
        for (groupIndex in 0..<unmappedTrackGroups.length) {
            val trackGroup = unmappedTrackGroups.get(groupIndex)
            @C.FormatSupport
            val trackSupport = IntArray(trackGroup!!.length)
            trackSupport.fill(C.FORMAT_UNSUPPORTED_TYPE)
            val selected = BooleanArray(trackGroup.length)
            trackGroups.add(
                Tracks.Group(trackGroup,false, trackSupport, selected)
            )
        }
        return Tracks(trackGroups)
    }

    fun getMaxVideoSizeInViewport(
        orientationMayChange: Boolean,
        viewportWidth: Int,
        viewportHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Point {
        var viewportWidth = viewportWidth
        var viewportHeight = viewportHeight
        if (orientationMayChange && (videoWidth > videoHeight) != (viewportWidth > viewportHeight)) {
            // Rotation is allowed, and the video will be larger in the rotated viewport.
            val tempViewportWidth = viewportWidth
            viewportWidth = viewportHeight
            viewportHeight = tempViewportWidth
        }

        if (videoWidth * viewportHeight >= videoHeight * viewportWidth) {
            // Horizontal letter-boxing along top and bottom.
            return Point(viewportWidth, ceilDivide(viewportWidth * videoHeight, videoWidth))
        } else {
            // Vertical letter-boxing along edges.
            return Point(ceilDivide(viewportHeight * videoWidth, videoHeight), viewportHeight)
        }
    }

    fun interface AdaptiveTrackSelectionFactory {
        fun createAdaptiveTrackSelection(trackSelectionDefinition: ExoTrackSelection.Definition): ExoTrackSelection
    }
}
