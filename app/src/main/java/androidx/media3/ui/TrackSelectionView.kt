package androidx.media3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckedTextView
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

import com.example.plr.R
import java.util.Arrays

class TrackSelectionView(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    
    interface TrackSelectionListener {
        fun onTrackSelectionChanged(
            isDisabled: Boolean, overrides: MutableMap<TrackGroup, TrackSelectionOverride>?
        )
    }

    private val selectableItemBackgroundResourceId: Int
    private val inflater: LayoutInflater
    private val disableView: CheckedTextView
    private val defaultView: CheckedTextView
    private val componentListener: ComponentListener
    private val trackGroups: MutableList<Tracks.Group>
    val overrides: MutableMap<TrackGroup, TrackSelectionOverride>
    private var allowAdaptiveSelections = false
    private var allowMultipleOverrides = false
    private var trackNameProvider: TrackNameProvider
    private var trackViews: Array<Array<CheckedTextView>>? = null
    var isDisabled: Boolean = false
        private set
    private var trackInfoComparator: Comparator<TrackInfo?>? = null
    private var listener: TrackSelectionListener? = null

    init {
        setOrientation(VERTICAL)
        // Don't save view hierarchy as it needs to be reinitialized with a call to init.
        setSaveFromParentEnabled(false)

        val attributeArray =
            context
                .getTheme()
                .obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        selectableItemBackgroundResourceId = attributeArray.getResourceId(0, 0)
        attributeArray.recycle()

        inflater = LayoutInflater.from(context)
        componentListener = ComponentListener()
        trackNameProvider = DefaultTrackNameProvider(getResources())
        trackGroups = ArrayList<Tracks.Group>()
        overrides = HashMap<TrackGroup, TrackSelectionOverride>()

        // View for disabling the renderer.
        disableView =
            inflater.inflate(
                android.R.layout.simple_list_item_single_choice,
                this,
                false
            ) as CheckedTextView
        disableView.setBackgroundResource(selectableItemBackgroundResourceId)
        disableView.setText(R.string.exo_track_selection_none)
        disableView.setEnabled(false)
        disableView.setFocusable(true)
        disableView.setOnClickListener(componentListener)
        disableView.setVisibility(GONE)
        addView(disableView)
        // Divider view.
        addView(inflater.inflate(R.layout.exo_list_divider, this, false))
        // View for clearing the override to allow the selector to use its default selection logic.
        defaultView =
            inflater.inflate(
                android. R.layout.simple_list_item_single_choice,
                this,
                false
            ) as CheckedTextView
        defaultView.setBackgroundResource(selectableItemBackgroundResourceId)
        defaultView.setText(R.string.exo_track_selection_auto)
        defaultView.setEnabled(false)
        defaultView.setFocusable(true)
        defaultView.setOnClickListener(componentListener)
        addView(defaultView)
    }

    fun setAllowAdaptiveSelections(allowAdaptiveSelections: Boolean) {
        if (this.allowAdaptiveSelections != allowAdaptiveSelections) {
            this.allowAdaptiveSelections = allowAdaptiveSelections
            updateViews()
        }
    }

    fun setAllowMultipleOverrides(allowMultipleOverrides: Boolean) {
        if (this.allowMultipleOverrides != allowMultipleOverrides) {
            this.allowMultipleOverrides = allowMultipleOverrides
            if (!allowMultipleOverrides && overrides.size > 1) {
                // Re-filter the overrides to retain only one of them.
                val filteredOverrides: MutableMap<TrackGroup, TrackSelectionOverride> =
                    filterOverrides(overrides, trackGroups,  false)
                overrides.clear()
                overrides.putAll(filteredOverrides)
            }
            updateViews()
        }
    }

    fun setShowDisableOption(showDisableOption: Boolean) {
        disableView.setVisibility(if (showDisableOption) VISIBLE else GONE)
    }

    fun setTrackNameProvider(trackNameProvider: TrackNameProvider?) {
        this.trackNameProvider =trackNameProvider!!
        updateViews()
    }

    fun init(
        trackGroups: MutableList<Tracks.Group>,
        isDisabled: Boolean,
        overrides: Map<TrackGroup, TrackSelectionOverride>,
        trackFormatComparator: Comparator<Format?>?,
        listener: TrackSelectionListener?
    ) {
        this.isDisabled = isDisabled
        this.trackInfoComparator =
            if (trackFormatComparator == null)
                null
            else
                Comparator { o1: TrackInfo?, o2: TrackInfo? ->
                    trackFormatComparator.compare(
                        o1!!.format,
                        o2!!.format
                    )
                }
        this.listener = listener

        this.trackGroups.clear()
        this.trackGroups.addAll(trackGroups)
        this.overrides.clear()
        this.overrides.putAll(filterOverrides(overrides, trackGroups, allowMultipleOverrides))
        updateViews()
    }

    // Private methods.
    private fun updateViews() {
        // Remove previous per-track views.
        for (i in getChildCount() - 1 downTo 3) {
            removeViewAt(i)
        }

        if (trackGroups.isEmpty()) {
            // The view is not initialized.
            disableView.setEnabled(false)
            defaultView.setEnabled(false)
            return
        }
        disableView.setEnabled(true)
        defaultView.setEnabled(true)

        // Add per-track views.
        val trackViews = arrayOfNulls<Array<CheckedTextView?>>(trackGroups.size)
        val enableMultipleChoiceForMultipleOverrides = shouldEnableMultiGroupSelection()
        for (trackGroupIndex in trackGroups.indices) {
            val trackGroup = trackGroups.get(trackGroupIndex)
            val enableMultipleChoiceForAdaptiveSelections =
                shouldEnableAdaptiveSelection(trackGroup)
            trackViews[trackGroupIndex] = arrayOfNulls<CheckedTextView>(trackGroup.length)

            val trackInfos = arrayOfNulls<TrackInfo>(trackGroup.length)
            for (trackIndex in 0..<trackGroup.length) {
                trackInfos[trackIndex] = TrackInfo(trackGroup, trackIndex)
            }
            if (trackInfoComparator != null) {
                Arrays.sort<TrackInfo?>(trackInfos, trackInfoComparator)
            }

            for (trackIndex in trackInfos.indices) {
                if (trackIndex == 0) {
                    addView(inflater.inflate(R.layout.exo_list_divider, this, false))
                }
                val trackViewLayoutId =
                    if (enableMultipleChoiceForAdaptiveSelections || enableMultipleChoiceForMultipleOverrides)
                        android. R.layout.simple_list_item_multiple_choice
                    else
                        android.  R.layout.simple_list_item_single_choice
                val trackView =
                    inflater.inflate(trackViewLayoutId, this, false) as CheckedTextView
                trackView.setBackgroundResource(selectableItemBackgroundResourceId)
                trackView.setText(trackNameProvider.getTrackName(trackInfos[trackIndex]!!.format))
                trackView.setTag(trackInfos[trackIndex])
                if (trackGroup.isTrackSupported(trackIndex)) {
                    trackView.setFocusable(true)
                    trackView.setOnClickListener(componentListener)
                } else {
                    trackView.setFocusable(false)
                    trackView.setEnabled(false)
                }
                trackViews[trackGroupIndex]!![trackIndex] = trackView
                addView(trackView)
            }
        }
        this.trackViews = trackViews as Array<Array<CheckedTextView>>

        updateViewStates()
    }

    private fun updateViewStates() {
        disableView.setChecked(isDisabled)
        defaultView.setChecked(!isDisabled && overrides.isEmpty())
        val trackViews = trackViews!!
        for (i in trackViews.indices) {
            val override = overrides.get(trackGroups[i].mediaTrackGroup)
            for (j in trackViews[i]!!.indices) {
                if (override != null) {
                    val trackInfo =trackViews[i]!![j]!!.getTag() as TrackInfo
                    trackViews[i]!![j]!!.setChecked(override.trackIndices.contains(trackInfo.trackIndex))
                } else {
                    trackViews[i]!![j]!!.setChecked(false)
                }
            }
        }
    }

    private fun onClick(view: View) {
        if (view === disableView) {
            onDisableViewClicked()
        } else if (view === defaultView) {
            onDefaultViewClicked()
        } else {
            onTrackViewClicked(view)
        }
        updateViewStates()
        if (listener != null) {
            listener!!.onTrackSelectionChanged(this.isDisabled, this.overrides)
        }
    }

    private fun onDisableViewClicked() {
        isDisabled = true
        overrides.clear()
    }

    private fun onDefaultViewClicked() {
        isDisabled = false
        overrides.clear()
    }

    private fun onTrackViewClicked(view: View) {
        isDisabled = false
        val trackInfo = view.getTag() as TrackInfo
        val mediaTrackGroup = trackInfo.trackGroup.mediaTrackGroup
        val trackIndex = trackInfo.trackIndex
        val override = overrides.get(mediaTrackGroup)
        if (override == null) {
            // Start new override.
            if (!allowMultipleOverrides && !overrides.isEmpty()) {
                // Removed other overrides if we don't allow multiple overrides.
                overrides.clear()
            }
            overrides.put(
                mediaTrackGroup,
                TrackSelectionOverride(mediaTrackGroup, listOf(trackIndex))
            )
        } else {
            // An existing override is being modified.
            val trackIndices = ArrayList<Int>(override.trackIndices)
            val isCurrentlySelected = (view as CheckedTextView).isChecked()
            val isAdaptiveAllowed = shouldEnableAdaptiveSelection(trackInfo.trackGroup)
            val isUsingCheckBox = isAdaptiveAllowed || shouldEnableMultiGroupSelection()
            if (isCurrentlySelected && isUsingCheckBox) {
                // Remove the track from the override.
                trackIndices.remove(trackIndex as Int?)
                if (trackIndices.isEmpty()) {
                    // The last track has been removed, so remove the whole override.
                    overrides.remove(mediaTrackGroup)
                } else {
                    overrides.put(
                        mediaTrackGroup,
                        TrackSelectionOverride(mediaTrackGroup, trackIndices)
                    )
                }
            } else if (!isCurrentlySelected) {
                if (isAdaptiveAllowed) {
                    // Add new track to adaptive override.
                    trackIndices.add(trackIndex)
                    overrides.put(
                        mediaTrackGroup,
                        TrackSelectionOverride(mediaTrackGroup, trackIndices)
                    )
                } else {
                    // Replace existing track in override.
                    overrides.put(
                        mediaTrackGroup,
                        TrackSelectionOverride(mediaTrackGroup, listOf(trackIndex))
                    )
                }
            }
        }
    }

    private fun shouldEnableAdaptiveSelection(trackGroup: Tracks.Group): Boolean {
        return allowAdaptiveSelections && trackGroup.isAdaptiveSupported
    }

    private fun shouldEnableMultiGroupSelection(): Boolean {
        return allowMultipleOverrides && trackGroups.size > 1
    }

    // Internal classes.
    private inner class ComponentListener : OnClickListener {
        override fun onClick(view: View) {
            this@TrackSelectionView.onClick(view)
        }
    }

    private class TrackInfo(val trackGroup: Tracks.Group, val trackIndex: Int) {
        val format: Format
            get() = trackGroup.getTrackFormat(trackIndex)
    }

    companion object {
        
        fun filterOverrides(
            overrides: Map<TrackGroup, TrackSelectionOverride>,
            trackGroups: MutableList<Tracks.Group>,
            allowMultipleOverrides: Boolean
        ): MutableMap<TrackGroup, TrackSelectionOverride> {
            val filteredOverrides = HashMap<TrackGroup, TrackSelectionOverride>()
            for (i in trackGroups.indices) {
                val trackGroup = trackGroups[i]
                val override = overrides.get(trackGroup.mediaTrackGroup)
                if (override != null && (allowMultipleOverrides || filteredOverrides.isEmpty())) {
                    filteredOverrides.put(override.mediaTrackGroup, override)
                }
            }
            return filteredOverrides
        }
    }
}
