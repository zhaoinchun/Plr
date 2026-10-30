package androidx.media3.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.StyleRes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

import com.example.plr.R
import java.lang.reflect.Constructor

class TrackSelectionDialogBuilder {
    
    fun interface DialogCallback {
        
        fun onTracksSelected(
            isDisabled: Boolean,
            overrides: MutableMap<TrackGroup, TrackSelectionOverride>?
        )
    }

    private val context: Context?
    private val title: CharSequence?
    private val trackGroups: MutableList<Tracks.Group>
    private val callback: DialogCallback

    @StyleRes
    private var themeResId = 0
    private var allowAdaptiveSelections = false
    private var allowMultipleOverrides = false
    private var showDisableOption = false
    private var trackNameProvider: TrackNameProvider? = null
    private var isDisabled = false
    private var overrides: Map<TrackGroup, TrackSelectionOverride> = mapOf()
    private var trackFormatComparator: Comparator<Format?>? = null

    constructor(
        context: Context?,
        title: CharSequence?,
        trackGroups: MutableList<Tracks.Group>,
        callback: DialogCallback
    ) {
        this.context = context
        this.title = title
        this.trackGroups = trackGroups.toMutableList()
        this.callback = callback
    }

    constructor(
        context: Context?,
        title: CharSequence?,
        player: Player,
        trackType: @C.TrackType Int
    ) {
        this.context = context
        this.title = title
        val tracks =
            if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) player.currentTracks else Tracks.EMPTY
        val allTrackGroups: List<Tracks.Group> = tracks!!.groups
        trackGroups = ArrayList<Tracks.Group>()
        for (i in allTrackGroups.indices) {
            val trackGroup = allTrackGroups[i]
            if (trackGroup.type == trackType) {
                trackGroups.add(trackGroup)
            }
        }
        overrides = player.trackSelectionParameters!!.overrides
        callback =
            DialogCallback { isDisabled: Boolean, overrides: MutableMap<TrackGroup, TrackSelectionOverride>? ->
                if (!player.isCommandAvailable(
                        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS
                    )
                ) {
                    return@DialogCallback
                }
                val parametersBuilder =
                    player.trackSelectionParameters!!.buildUpon()
                parametersBuilder.setTrackTypeDisabled(trackType, isDisabled)
                parametersBuilder.clearOverridesOfType(trackType)
                for (override in overrides!!.values) {
                    parametersBuilder.addOverride(override)
                }
                player.trackSelectionParameters = parametersBuilder.build()
            }
    }

    fun setTheme(@StyleRes themeResId: Int): TrackSelectionDialogBuilder {
        this.themeResId = themeResId
        return this
    }

    fun setIsDisabled(isDisabled: Boolean): TrackSelectionDialogBuilder {
        this.isDisabled=(isDisabled)
        return this
    }

    fun setOverride(override: TrackSelectionOverride?): TrackSelectionDialogBuilder {
        return setOverrides(
            if (override == null) mapOf() else
                mapOf(override.mediaTrackGroup to   override)
        )
    }

    fun setOverrides(overrides: Map<TrackGroup, TrackSelectionOverride>): TrackSelectionDialogBuilder {
        this.overrides = overrides.toMap()
        return this
    }

    fun setAllowAdaptiveSelections(allowAdaptiveSelections: Boolean): TrackSelectionDialogBuilder {
        this.allowAdaptiveSelections = allowAdaptiveSelections
        return this
    }

    fun setAllowMultipleOverrides(allowMultipleOverrides: Boolean): TrackSelectionDialogBuilder {
        this.allowMultipleOverrides = allowMultipleOverrides
        return this
    }

    fun setShowDisableOption(showDisableOption: Boolean): TrackSelectionDialogBuilder {
        this.showDisableOption = showDisableOption
        return this
    }

    fun setTrackFormatComparator(trackFormatComparator: Comparator<Format?>?) {
        this.trackFormatComparator = trackFormatComparator
    }

    fun setTrackNameProvider(
        trackNameProvider: TrackNameProvider?
    ): TrackSelectionDialogBuilder {
        this.trackNameProvider = trackNameProvider
        return this
    }

    fun build(): Dialog? {
        val dialog = buildForAndroidX()
        return if (dialog == null) buildForPlatform() else dialog
    }

    private fun buildForPlatform(): Dialog? {
        val builder = AlertDialog.Builder(context, themeResId)

        // Inflate with the builder's context to ensure the correct style is used.
        val dialogInflater = LayoutInflater.from(builder.getContext())
        // TODO: b/301602565 - See if there's a reasonable non-null root view group we could use here.
        val dialogView: View =
            dialogInflater.inflate(R.layout.exo_track_selection_dialog,  null)
        val okClickListener = setUpDialogView(dialogView)

        return builder
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, okClickListener)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    // Reflection calls can't verify null safety of return values or parameters.
    private fun buildForAndroidX(): Dialog? {
        try {
            // This method uses reflection to avoid a dependency on AndroidX appcompat that adds 800KB to
            // the APK size even with shrinking. See https://issuetracker.google.com/161514204.
            // LINT.IfChange
            val builderClazz = Class.forName("androidx.appcompat.app.AlertDialog\$Builder")
            val builderConstructor: Constructor<*> =
                builderClazz.getConstructor(Context::class.java, Int::class.javaPrimitiveType)
            val builder: Any = builderConstructor.newInstance(context, themeResId)

            // Inflate with the builder's context to ensure the correct style is used.
            val builderContext = builderClazz.getMethod("getContext").invoke(builder) as Context?
            val dialogInflater = LayoutInflater.from(builderContext)
            // TODO: b/301602565 - See if there's a reasonable non-null root view group we could use here.
            val dialogView: View =
                dialogInflater.inflate(R.layout.exo_track_selection_dialog,  null)
            val okClickListener = setUpDialogView(dialogView)

            builderClazz.getMethod("setTitle", CharSequence::class.java).invoke(builder, title)
            builderClazz.getMethod("setView", View::class.java).invoke(builder, dialogView)
            builderClazz
                .getMethod(
                    "setPositiveButton",
                    Int::class.javaPrimitiveType,
                    DialogInterface.OnClickListener::class.java
                )
                .invoke(builder, android.R.string.ok, okClickListener)
            builderClazz
                .getMethod(
                    "setNegativeButton",
                    Int::class.javaPrimitiveType,
                    DialogInterface.OnClickListener::class.java
                )
                .invoke(builder, android.R.string.cancel, null)
            return builderClazz.getMethod("create").invoke(builder) as Dialog?
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
        } catch (e: ClassNotFoundException) {
            // Expected if the AndroidX compat library is not available.
            return null
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    private fun setUpDialogView(dialogView: View): DialogInterface.OnClickListener {
        val selectionView =
            dialogView.findViewById<TrackSelectionView>(R.id.exo_track_selection_view)
        selectionView.setAllowMultipleOverrides(allowMultipleOverrides)
        selectionView.setAllowAdaptiveSelections(allowAdaptiveSelections)
        selectionView.setShowDisableOption(showDisableOption)
        if (trackNameProvider != null) {
            selectionView.setTrackNameProvider(trackNameProvider)
        }
        selectionView.init(
            trackGroups, isDisabled, overrides, trackFormatComparator,  null
        )
        return DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
            callback.onTracksSelected(
                selectionView.isDisabled,
                selectionView.overrides
            )
        }
    }
}
