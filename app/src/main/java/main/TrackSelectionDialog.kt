package main

import android.app.Dialog
import android.content.DialogInterface
import android.content.res.Resources
import android.os.Bundle
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.ui.TrackSelectionView
import androidx.viewpager.widget.ViewPager
import com.example.plr.R
import com.google.android.material.tabs.TabLayout
import com.google.common.collect.ImmutableList

class TrackSelectionDialog : DialogFragment() {
   fun interface TrackSelectionListener {
        fun onTracksSelected(trackSelectionParameters: TrackSelectionParameters)
    }

    private val tabFragments: SparseArray<TrackSelectionViewFragment>
    private val tabTrackTypes: ArrayList<Int?>

    private var titleId = 0
    private var onClickListener: DialogInterface.OnClickListener? = null
    private var onDismissListener: DialogInterface.OnDismissListener? = null

    init {
        tabFragments = SparseArray<TrackSelectionViewFragment>()
        tabTrackTypes = ArrayList<Int?>()
        // Retain instance across activity re-creation to prevent losing access to init data.
        setRetainInstance(true)
    }

    private fun init(
        tracks: Tracks,
        trackSelectionParameters: TrackSelectionParameters,
        titleId: Int,
        allowAdaptiveSelections: Boolean,
        allowMultipleOverrides: Boolean,
        onClickListener: DialogInterface.OnClickListener,
        onDismissListener: DialogInterface.OnDismissListener
    ) {
        this.titleId = titleId
        this.onClickListener = onClickListener
        this.onDismissListener = onDismissListener

        for (i in SUPPORTED_TRACK_TYPES.indices) {
            val trackType: Int = SUPPORTED_TRACK_TYPES.get(i)!!
            val trackGroups = ArrayList<Tracks.Group>()
            for (trackGroup in tracks.groups) {
                if (trackGroup.type == trackType) {
                    trackGroups.add(trackGroup)
                }
            }
            if (!trackGroups.isEmpty()) {
                val tabFragment = TrackSelectionViewFragment()
                tabFragment.init(
                    trackGroups,
                    trackSelectionParameters.disabledTrackTypes.contains(trackType),
                    trackSelectionParameters.overrides,
                    allowAdaptiveSelections,
                    allowMultipleOverrides
                )
                tabFragments.put(trackType, tabFragment)
                tabTrackTypes.add(trackType)
            }
        }
    }

    /**
     * Returns whether the disabled option is selected for the specified track type.
     * 
     * @param trackType The track type.
     * @return Whether the disabled option is selected for the track type.
     */
    fun getIsDisabled(trackType: Int): Boolean {
        val trackView = tabFragments.get(trackType)
        return trackView != null && trackView.isDisabled
    }

    /**
     * Returns the selected track overrides for the specified track type.
     * 
     * @param trackType The track type.
     * @return The track overrides for the track type.
     */
    fun getOverrides(trackType: Int): MutableMap<TrackGroup, TrackSelectionOverride> {
        val trackView = tabFragments.get(trackType)
        return if (trackView == null) mutableMapOf<TrackGroup, TrackSelectionOverride>() else trackView.overrides!!
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // We need to own the view to let tab layout work correctly on all API levels. We can't use
        // AlertDialog because it owns the view itself, so we use AppCompatDialog instead, themed using
        // the AlertDialog theme overlay with force-enabled title.
        val dialog =
            AppCompatDialog(requireActivity(), R.style.TrackSelectionDialogThemeOverlay)
        dialog.setTitle(titleId)
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener!!.onDismiss(dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val dialogView = inflater.inflate(R.layout.track_selection_dialog, container, false)
        val tabLayout = dialogView.findViewById<TabLayout>(R.id.track_selection_dialog_tab_layout)
        val viewPager = dialogView.findViewById<ViewPager>(R.id.track_selection_dialog_view_pager)
        val cancelButton =
            dialogView.findViewById<Button>(R.id.track_selection_dialog_cancel_button)
        val okButton = dialogView.findViewById<Button>(R.id.track_selection_dialog_ok_button)
        viewPager.setAdapter(FragmentAdapter(getChildFragmentManager()))
        tabLayout.setupWithViewPager(viewPager)
        tabLayout.setVisibility(if (tabFragments.size() > 1) View.VISIBLE else View.GONE)
        cancelButton.setOnClickListener(View.OnClickListener { view: View? -> dismiss() })
        okButton.setOnClickListener(
            View.OnClickListener { view: View? ->
                onClickListener!!.onClick(getDialog(), DialogInterface.BUTTON_POSITIVE)
                dismiss()
            })
        return dialogView
    }

    private inner class FragmentAdapter(fragmentManager: FragmentManager) :
        FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getItem(position: Int): Fragment {
            return tabFragments.get(tabTrackTypes.get(position)!!)
        }

        override fun getCount(): Int {
            return tabTrackTypes.size
        }

        override fun getPageTitle(position: Int): CharSequence {
            return Companion.getTrackTypeString(getResources(), tabTrackTypes.get(position)!!)
        }
    }

    /** Fragment to show a track selection in tab of the track selection dialog.  */
    class TrackSelectionViewFragment : Fragment(), TrackSelectionView.TrackSelectionListener {
        private var trackGroups: MutableList<Tracks.Group>? = null
        private var allowAdaptiveSelections = false
        private var allowMultipleOverrides = false

        /* package */
        var isDisabled: Boolean = false

        /* package */
        var overrides: MutableMap<TrackGroup, TrackSelectionOverride>? = null

        init {
            // Retain instance across activity re-creation to prevent losing access to init data.
            setRetainInstance(true)
        }

        fun init(
            trackGroups: MutableList<Tracks.Group>,
            isDisabled: Boolean,
            overrides: MutableMap<TrackGroup, TrackSelectionOverride>,
            allowAdaptiveSelections: Boolean,
            allowMultipleOverrides: Boolean
        ) {
            this.trackGroups = trackGroups
            this.isDisabled = isDisabled
            this.allowAdaptiveSelections = allowAdaptiveSelections
            this.allowMultipleOverrides = allowMultipleOverrides
            // TrackSelectionView does this filtering internally, but we need to do it here as well to
            // handle the case where the TrackSelectionView is never created.
            this.overrides =
                HashMap<TrackGroup, TrackSelectionOverride>(
                    TrackSelectionView.filterOverrides(
                        overrides,
                        trackGroups,
                        allowMultipleOverrides
                    )
                )
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val rootView: View =
                inflater.inflate(
                    R.layout.exo_track_selection_dialog,
                    container,  /* attachToRoot= */
                    false
                )
            val trackSelectionView =
                rootView.findViewById<TrackSelectionView>(R.id.exo_track_selection_view)
            trackSelectionView.setShowDisableOption(true)
            trackSelectionView.setAllowMultipleOverrides(allowMultipleOverrides)
            trackSelectionView.setAllowAdaptiveSelections(allowAdaptiveSelections)
            trackSelectionView.init(
                trackGroups!!,
                isDisabled,
                overrides!!,  /* trackFormatComparator= */
                null,  /* listener= */
                this
            )
            return rootView
        }

        override fun onTrackSelectionChanged(
            isDisabled: Boolean, overrides: MutableMap<TrackGroup, TrackSelectionOverride>
        ) {
            this.isDisabled = isDisabled
            this.overrides = overrides
        }
    }

    companion object {
        val SUPPORTED_TRACK_TYPES: ImmutableList<Int?> = ImmutableList.of<Int?>(
            C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_IMAGE
        )

        /**
         * Returns whether a track selection dialog will have content to display if initialized with the
         * specified [Player].
         */
        fun willHaveContent(player: Player): Boolean {
            return Companion.willHaveContent(player.currentTracks!!)
        }

        /**
         * Returns whether a track selection dialog will have content to display if initialized with the
         * specified [Tracks].
         */
        fun willHaveContent(tracks: Tracks): Boolean {
            for (trackGroup in tracks.groups) {
                if (SUPPORTED_TRACK_TYPES.contains(trackGroup.type)) {
                    return true
                }
            }
            return false
        }

        /**
         * Creates a dialog for a given [Player], whose parameters will be automatically updated
         * when tracks are selected.
         * 
         * @param player The [Player].
         * @param onDismissListener A [DialogInterface.OnDismissListener] to call when the dialog is
         * dismissed.
         */
        fun createForPlayer(
            player: Player, onDismissListener: DialogInterface.OnDismissListener
        ): TrackSelectionDialog {
            return Companion.createForTracksAndParameters(
                R.string.track_selection_title,
                player.currentTracks!!,
                player.trackSelectionParameters!!,  /* allowAdaptiveSelections= */
                true,  /* allowMultipleOverrides= */
                false,
                {player.trackSelectionParameters = it},
                onDismissListener
            )
        }

        /**
         * Creates a dialog for given [Tracks] and [TrackSelectionParameters].
         * 
         * @param titleId The resource id of the dialog title.
         * @param tracks The [Tracks] describing the tracks to display.
         * @param trackSelectionParameters The initial [TrackSelectionParameters].
         * @param allowAdaptiveSelections Whether adaptive selections (consisting of more than one track)
         * can be made.
         * @param allowMultipleOverrides Whether tracks from multiple track groups can be selected.
         * @param trackSelectionListener Called when tracks are selected.
         * @param onDismissListener [DialogInterface.OnDismissListener] called when the dialog is
         * dismissed.
         */
        fun createForTracksAndParameters(
            titleId: Int,
            tracks: Tracks,
            trackSelectionParameters: TrackSelectionParameters,
            allowAdaptiveSelections: Boolean,
            allowMultipleOverrides: Boolean,
            trackSelectionListener: TrackSelectionListener,
            onDismissListener: DialogInterface.OnDismissListener
        ): TrackSelectionDialog {
            val trackSelectionDialog = TrackSelectionDialog()
            trackSelectionDialog.init(
                tracks,
                trackSelectionParameters,
                titleId,
                allowAdaptiveSelections,
                allowMultipleOverrides,  /* onClickListener= */
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    val builder = trackSelectionParameters.buildUpon()
                    for (i in SUPPORTED_TRACK_TYPES.indices) {
                        val trackType: Int = SUPPORTED_TRACK_TYPES.get(i)!!
                        builder.setTrackTypeDisabled(
                            trackType,
                            trackSelectionDialog.getIsDisabled(trackType)
                        )
                        builder.clearOverridesOfType(trackType)
                        val overrides =
                            trackSelectionDialog.getOverrides(trackType)
                        for (override in overrides.values) {
                            builder.addOverride(override)
                        }
                    }
                    trackSelectionListener.onTracksSelected(builder.build())
                },
                onDismissListener
            )
            return trackSelectionDialog
        }

        private fun getTrackTypeString(resources: Resources, trackType: Int): String {
            when (trackType) {
                C.TRACK_TYPE_VIDEO -> return resources.getString(R.string.track_selection_title_video)
                C.TRACK_TYPE_AUDIO -> return resources.getString(R.string.track_selection_title_audio)
                C.TRACK_TYPE_TEXT -> return resources.getString(R.string.track_selection_title_text)
                C.TRACK_TYPE_IMAGE -> return resources.getString(R.string.track_selection_title_image)
                else -> throw IllegalArgumentException()
            }
        }
    }
}
