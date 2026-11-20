package main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.JsonReader
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.ExpandableListView.OnChildClickListener
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.LocalConfiguration
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.getAdaptiveMimeTypeForContentType
import androidx.media3.common.util.Util.getDrmUuid
import androidx.media3.common.util.Util.inferContentType
import androidx.media3.common.util.Util.inferContentTypeForExtension
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSourceUtil.closeQuietly
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.DownloadService
import com.example.plr.R
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** An activity for selecting from a list of media samples.  */
class SampleChooserActivity : AppCompatActivity(), DownloadTracker.Listener, OnChildClickListener {
    private lateinit var uris: Array<String>
    private var useExtensionRenderers = false
    private var downloadTracker: DownloadTracker? = null
    private var sampleAdapter: SampleAdapter? = null
    private var preferExtensionDecodersMenuItem: MenuItem? = null
    private var sampleListView: ExpandableListView? = null
    private var downloadMediaItemWaitingForNotificationPermission: MediaItem? = null
    private var notificationPermissionToastShown = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.sample_chooser_activity)
        sampleAdapter = SampleAdapter()
        sampleListView = findViewById<ExpandableListView>(R.id.sample_list)

        sampleListView!!.setAdapter(sampleAdapter)
        sampleListView!!.setOnChildClickListener(this)

        val intent = getIntent()
        val dataUri = intent.getDataString()
        if (dataUri != null) {
            uris = arrayOf<String>(dataUri)
        } else {
            val uriList = ArrayList<String?>()
            val assetManager = getAssets()
            try {
                for (asset in assetManager.list("")!!) {
                    if (asset.endsWith(".exolist.json")) {
                        uriList.add("asset:///" + asset)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "One or more sample lists failed to load", e)
                Toast.makeText(
                    getApplicationContext(),
                    R.string.sample_list_load_error,
                    Toast.LENGTH_LONG
                )
                    .show()
            }
            uris = arrayOfNulls<String>(uriList.size) as Array<String>
            uriList.toArray(uris)
            Arrays.sort(uris)
        }

        useExtensionRenderers = DemoUtil.useExtensionRenderers()
        downloadTracker = DemoUtil.getDownloadTracker( /* context= */this)
        loadSample()
        startDownloadService()
    }

    private fun startDownloadService() {
        // Starting the service in the foreground causes notification flicker if there is no scheduled
        // action. Starting it in the background throws an exception if the app is in the background too
        // (e.g. if device screen is locked).
        try {
            DownloadService.start(this, DemoDownloadService::class.java)
        } catch (e: IllegalStateException) {
            DownloadService.startForeground(this, DemoDownloadService::class.java)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = getMenuInflater()
        inflater.inflate(R.menu.sample_chooser_menu, menu)
        preferExtensionDecodersMenuItem = menu.findItem(R.id.prefer_extension_decoders)
        preferExtensionDecodersMenuItem!!.setVisible(useExtensionRenderers)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        item.setChecked(!item.isChecked())
        return true
    }

    public override fun onStart() {
        super.onStart()
        downloadTracker!!.addListener(this)
        sampleAdapter!!.notifyDataSetChanged()
    }

    public override fun onStop() {
        downloadTracker!!.removeListener(this)
        super.onStop()
    }

    override fun onDownloadsChanged() {
        sampleAdapter!!.notifyDataSetChanged()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!notificationPermissionToastShown
            && (grantResults.size == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            Toast.makeText(
                getApplicationContext(), R.string.post_notification_not_granted, Toast.LENGTH_LONG
            )
                .show()
            notificationPermissionToastShown = true
        }
        if (downloadMediaItemWaitingForNotificationPermission != null) {
            // Download with or without permission to post notifications.
            toggleDownload(downloadMediaItemWaitingForNotificationPermission!!)
            downloadMediaItemWaitingForNotificationPermission = null
        }
    }

    private fun loadSample() {
        val loaderTask = SampleListLoader()
        loaderTask.execute(*uris)
    }

    private fun onPlaylistGroups(groups: MutableList<PlaylistGroup>, sawError: Boolean) {
        if (sawError) {
            Toast.makeText(
                getApplicationContext(),
                R.string.sample_list_load_error,
                Toast.LENGTH_LONG
            )
                .show()
        }
        sampleAdapter!!.setPlaylistGroups(groups)

        val preferences = getPreferences(MODE_PRIVATE)
        val groupPosition = preferences.getInt(GROUP_POSITION_PREFERENCE_KEY,  /* defValue= */-1)
        val childPosition = preferences.getInt(CHILD_POSITION_PREFERENCE_KEY,  /* defValue= */-1)
        // Clear the group and child position if either are unset or if either are out of bounds.
        if (groupPosition != -1 && childPosition != -1 && groupPosition < groups.size && childPosition < groups.get(
                groupPosition
            ).playlists.size
        ) {
            sampleListView!!.expandGroup(groupPosition) // shouldExpandGroup does not work without this.
            sampleListView!!.setSelectedChild(
                groupPosition,
                childPosition,  /* shouldExpandGroup= */
                true
            )
        }
    }

    override fun onChildClick(
        parent: ExpandableListView?, view: View, groupPosition: Int, childPosition: Int, id: Long
    ): Boolean {
        // Save the selected item first to be able to restore it if the tested code crashes.
        val prefEditor = getPreferences(MODE_PRIVATE).edit()
        prefEditor.putInt(GROUP_POSITION_PREFERENCE_KEY, groupPosition)
        prefEditor.putInt(CHILD_POSITION_PREFERENCE_KEY, childPosition)
        prefEditor.apply()

        val playlistHolder: PlaylistHolder = view.getTag() as PlaylistHolder
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(
            IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA,
            isNonNullAndChecked(preferExtensionDecodersMenuItem)
        )
        IntentUtil.addToIntent(playlistHolder.mediaItems, intent)
        startActivity(intent)
        return true
    }

    private fun onSampleDownloadButtonClicked(playlistHolder: PlaylistHolder) {
        val downloadUnsupportedStringId = getDownloadUnsupportedStringId(playlistHolder)
        if (downloadUnsupportedStringId != 0) {
            Toast.makeText(getApplicationContext(), downloadUnsupportedStringId, Toast.LENGTH_LONG)
                .show()
        } else if (!notificationPermissionToastShown && Build.VERSION.SDK_INT >= 33 && (checkSelfPermission(
                Api33.postNotificationPermissionString
            )
                    != PackageManager.PERMISSION_GRANTED)
        ) {
            downloadMediaItemWaitingForNotificationPermission = playlistHolder.mediaItems.get(0)
            requestPermissions(
                arrayOf<String?>(Api33.postNotificationPermissionString),  /* requestCode= */0
            )
        } else {
            toggleDownload(playlistHolder.mediaItems.get(0)!!)
        }
    }

    private fun toggleDownload(mediaItem: MediaItem) {
        val renderersFactory =
            DemoUtil.buildRenderersFactory( /* context= */
                this, isNonNullAndChecked(preferExtensionDecodersMenuItem)
            )
        downloadTracker!!.toggleDownload(getSupportFragmentManager(), mediaItem, renderersFactory)
    }

    private fun getDownloadUnsupportedStringId(playlistHolder: PlaylistHolder): Int {
        if (playlistHolder.mediaItems.size > 1) {
            return R.string.download_playlist_unsupported
        }
        val localConfiguration =
            Preconditions.checkNotNull<LocalConfiguration?>(playlistHolder.mediaItems.get(0)!!.localConfiguration)
        if (localConfiguration.adsConfiguration != null) {
            return R.string.download_ads_unsupported
        }
        val drmConfiguration = localConfiguration.drmConfiguration
        if (drmConfiguration != null && drmConfiguration.scheme != C.WIDEVINE_UUID) {
            return R.string.download_only_widevine_drm_supported
        }
        val scheme = localConfiguration.uri.getScheme()
        if (!("http" == scheme || "https" == scheme)) {
            return R.string.download_scheme_unsupported
        }
        return 0
    }

    private inner class SampleListLoader {
        private val executorService: ExecutorService

        private var sawError = false

        init {
            executorService = Executors.newSingleThreadExecutor()
        }

        fun execute(vararg uris: String?) {
            executorService.execute(
                Runnable {
                    val result: MutableList<PlaylistGroup> = ArrayList<PlaylistGroup>()
                    val context = getApplicationContext()
                    val dataSource = DemoUtil.getDataSourceFactory(context).createDataSource()
                    for (uri in uris) {
                        val dataSpec = DataSpec(Uri.parse(uri))
                        val inputStream: InputStream = DataSourceInputStream(dataSource, dataSpec)
                        try {
                            readPlaylistGroups(
                                JsonReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)),
                                result
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading sample list: " + uri, e)
                            sawError = true
                        } finally {
                            closeQuietly(dataSource)
                        }
                    }
                    Handler(Looper.getMainLooper())
                        .post(
                            Runnable {
                                onPlaylistGroups(result, sawError)
                            })
                })
        }

        @Throws(IOException::class)
        fun readPlaylistGroups(reader: JsonReader, groups: MutableList<PlaylistGroup>) {
            reader.beginArray()
            while (reader.hasNext()) {
                readPlaylistGroup(reader, groups)
            }
            reader.endArray()
        }

        @Throws(IOException::class)
        fun readPlaylistGroup(reader: JsonReader, groups: MutableList<PlaylistGroup>) {
            var groupName: String? = ""
            val playlistHolders: ArrayList<PlaylistHolder?> = ArrayList<PlaylistHolder?>()

            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                when (name) {
                    "name" -> groupName = reader.nextString()
                    "samples" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            playlistHolders.add(readEntry(reader, false))
                        }
                        reader.endArray()
                    }

                    "_comment" -> reader.nextString() // Ignore.
                    else -> throw IOException("Unsupported name: " + name,  /* cause= */null)
                }
            }
            reader.endObject()

            val group = getGroup(groupName, groups)
            group.playlists.addAll(playlistHolders)
        }

        @Throws(IOException::class)
        fun readEntry(reader: JsonReader, insidePlaylist: Boolean): PlaylistHolder {
            var uri: Uri? = null
            var extension: String? = null
            var mimeType: String? = null
            var title: String? = null
            var children: ArrayList<PlaylistHolder?>? = null
            var subtitleUri: Uri? = null
            var subtitleMimeType: String? = null
            var subtitleLanguage: String? = null
            var drmUuid: UUID? = null
            var drmLicenseUri: String? = null
            var drmLicenseRequestHeaders = ImmutableMap.of<String?, String?>()
            var drmSessionForClearContent = false
            var drmMultiSession = false
            var drmForceDefaultLicenseUri = false

            val mediaItem = MediaItem.Builder()
            reader.beginObject()
            var startPositionMs = 0L
            var endPositionMs = C.TIME_END_OF_SOURCE
            while (reader.hasNext()) {
                val name = reader.nextName()
                when (name) {
                    "name" -> title = reader.nextString()
                    "uri" -> uri = Uri.parse(reader.nextString())
                    "extension" -> extension = reader.nextString()
                    "mime_type" -> mimeType = reader.nextString()
                    "clip_start_position_ms" -> startPositionMs = reader.nextLong()
                    "clip_end_position_ms" -> endPositionMs = reader.nextLong()
                    "image_duration_ms" -> mediaItem.setImageDurationMs(reader.nextLong())
                    "ad_tag_uri" -> mediaItem.setAdsConfiguration(
                        MediaItem.AdsConfiguration(reader.nextString().toUri())
                    )

                    "drm_scheme" -> drmUuid = getDrmUuid(reader.nextString())
                    "drm_license_uri", "drm_license_url" -> drmLicenseUri = reader.nextString()
                    "drm_key_request_properties" -> {
                        val requestHeaders: MutableMap<String?, String?> =
                            HashMap<String?, String?>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            requestHeaders.put(reader.nextName(), reader.nextString())
                        }
                        reader.endObject()
                        drmLicenseRequestHeaders =
                            ImmutableMap.copyOf<String?, String?>(requestHeaders)
                    }

                    "drm_session_for_clear_content" -> drmSessionForClearContent =
                        reader.nextBoolean()

                    "drm_multi_session" -> drmMultiSession = reader.nextBoolean()
                    "drm_force_default_license_uri" -> drmForceDefaultLicenseUri =
                        reader.nextBoolean()

                    "subtitle_uri" -> subtitleUri = Uri.parse(reader.nextString())
                    "subtitle_mime_type" -> subtitleMimeType = reader.nextString()
                    "subtitle_language" -> subtitleLanguage = reader.nextString()
                    "playlist" -> {
                        Preconditions.checkState(!insidePlaylist, "Invalid nesting of playlists")
                        children = ArrayList<PlaylistHolder?>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            children.add(readEntry(reader,  /* insidePlaylist= */true))
                        }
                        reader.endArray()
                    }

                    else -> throw IOException(
                        "Unsupported attribute name: " + name,  /* cause= */
                        null
                    )
                }
            }
            reader.endObject()

            if (children != null) {
                val mediaItems: MutableList<MediaItem?> = ArrayList<MediaItem?>()
                for (i in children.indices) {
                    mediaItems.addAll(children.get(i)!!.mediaItems)
                }
                return PlaylistHolder(title, mediaItems)
            } else {
                if (!TextUtils.isEmpty(mimeType)) {
                    Preconditions.checkState(
                        TextUtils.isEmpty(extension),
                        "Only one of mime_type or extension should be set"
                    )
                    mediaItem.setMimeType(mimeType)
                } else {
                    mediaItem.setMimeType(
                        getAdaptiveMimeTypeForContentType(
                            if (TextUtils.isEmpty(extension))
                                Util.inferContentType(uri!!)
                            else
                                Util.inferContentTypeForExtension(extension!!)
                        )
                    )
                }
                val clippingConfiguration = MediaItem.ClippingConfiguration(
                    startPositionUs = Util.msToUs(startPositionMs),
                    endPositionUs = Util.msToUs(endPositionMs),
                )
                mediaItem
                    .setUri(uri)
                    .setMediaMetadata(MediaMetadata(title = title))
                    .setClippingConfiguration(clippingConfiguration)
                if (drmUuid != null) {
                    mediaItem.setDrmConfiguration(
                        MediaItem.DrmConfiguration(
                            scheme = drmUuid,
                            licenseUri = drmLicenseUri?.toUri(),
                            licenseRequestHeaders = drmLicenseRequestHeaders,
                            multiSession = drmMultiSession,
                            forceDefaultLicenseUri = drmForceDefaultLicenseUri
                        ).apply {
                            setForceSessionsForAudioAndVideoTracks(drmSessionForClearContent)
                        })
                } else {
                    Preconditions.checkState(
                        drmLicenseUri == null,
                        "drm_uuid is required if drm_license_uri is set."
                    )
                    Preconditions.checkState(
                        drmLicenseRequestHeaders.isEmpty(),
                        "drm_uuid is required if drm_key_request_properties is set."
                    )
                    Preconditions.checkState(
                        !drmSessionForClearContent,
                        "drm_uuid is required if drm_session_for_clear_content is set."
                    )
                    Preconditions.checkState(
                        !drmMultiSession,
                        "drm_uuid is required if drm_multi_session is set."
                    )
                    Preconditions.checkState(
                        !drmForceDefaultLicenseUri,
                        "drm_uuid is required if drm_force_default_license_uri is set."
                    )
                }
                if (subtitleUri != null) {
                    val subtitleConfiguration: SubtitleConfiguration =
                        SubtitleConfiguration(uri=subtitleUri,
                            mimeType = subtitleMimeType!!,
                            language = subtitleLanguage)
                    mediaItem.setSubtitleConfigurations(
                        ImmutableList.of<SubtitleConfiguration>(
                            subtitleConfiguration
                        )
                    )
                }
                return PlaylistHolder(title, mutableListOf<MediaItem?>(mediaItem.build()))
            }
        }

        fun getGroup(groupName: String?, groups: MutableList<PlaylistGroup>): PlaylistGroup {
            for (i in groups.indices) {
                if (groupName == groups.get(i).title) {
                    return groups.get(i)
                }
            }
            val group = PlaylistGroup(groupName)
            groups.add(group)
            return group
        }
    }

    private inner class SampleAdapter : BaseExpandableListAdapter(), View.OnClickListener {
        private var playlistGroups: MutableList<PlaylistGroup>

        init {
            playlistGroups = mutableListOf<PlaylistGroup>()
        }

        fun setPlaylistGroups(playlistGroups: MutableList<PlaylistGroup>) {
            this.playlistGroups = playlistGroups
            notifyDataSetChanged()
        }

        override fun getChild(groupPosition: Int, childPosition: Int): PlaylistHolder? {
            return getGroup(groupPosition)!!.playlists.get(childPosition)
        }

        override fun getChildId(groupPosition: Int, childPosition: Int): Long {
            return childPosition.toLong()
        }

        override fun getChildView(
            groupPosition: Int,
            childPosition: Int,
            isLastChild: Boolean,
            convertView: View?,
            parent: ViewGroup?
        ): View {
            var view = convertView
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.sample_list_item, parent, false)
                val downloadButton = view.findViewById<View>(R.id.download_button)
                downloadButton.setOnClickListener(this)
                downloadButton.setFocusable(false)
            }
            initializeChildView(view, getChild(groupPosition, childPosition)!!)
            return view
        }

        override fun getChildrenCount(groupPosition: Int): Int {
            return getGroup(groupPosition)!!.playlists.size
        }

        override fun getGroup(groupPosition: Int): PlaylistGroup? {
            return playlistGroups.get(groupPosition)
        }

        override fun getGroupId(groupPosition: Int): Long {
            return groupPosition.toLong()
        }

        override fun getGroupView(
            groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?
        ): View {
            var view = convertView
            if (view == null) {
                view =
                    getLayoutInflater()
                        .inflate(android.R.layout.simple_expandable_list_item_1, parent, false)
            }
            (view as TextView).setText(getGroup(groupPosition)!!.title)
            return view
        }

        override fun getGroupCount(): Int {
            return playlistGroups.size
        }

        override fun hasStableIds(): Boolean {
            return false
        }

        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean {
            return true
        }

        override fun onClick(view: View) {
            onSampleDownloadButtonClicked((view.getTag() as PlaylistHolder?)!!)
        }

        fun initializeChildView(view: View, playlistHolder: PlaylistHolder) {
            view.setTag(playlistHolder)
            val sampleTitle = view.findViewById<TextView>(R.id.sample_title)
            sampleTitle.setText(playlistHolder.title)

            val canDownload = getDownloadUnsupportedStringId(playlistHolder) == 0
            val isDownloaded =
                canDownload && downloadTracker!!.isDownloaded(playlistHolder.mediaItems.get(0)!!)
            val downloadButton = view.findViewById<ImageButton>(R.id.download_button)
            downloadButton.setTag(playlistHolder)
            downloadButton.setColorFilter(
                if (canDownload) (if (isDownloaded) -0xbd5a0b else -0x424243) else -0x99999a
            )
            downloadButton.setImageResource(
                if (isDownloaded) R.drawable.ic_download_done else R.drawable.ic_download
            )
        }
    }

    private class PlaylistHolder(title: String?, mediaItems: MutableList<MediaItem?>) {
        val title: String?
        val mediaItems: MutableList<MediaItem>

        init {
            Preconditions.checkArgument(!mediaItems.isEmpty())
            this.title = title
            this.mediaItems =
                Collections.unmodifiableList<MediaItem?>(ArrayList<MediaItem?>(mediaItems))
        }
    }

    private class PlaylistGroup(val title: String?) {
        val playlists: MutableList<PlaylistHolder?>

        init {
            this.playlists = ArrayList<PlaylistHolder?>()
        }
    }

    @RequiresApi(33)
    private object Api33 {
        val postNotificationPermissionString: String
            get() = Manifest.permission.POST_NOTIFICATIONS
    }

    companion object {
        private const val TAG = "SampleChooserActivity"
        private const val GROUP_POSITION_PREFERENCE_KEY = "sample_chooser_group_position"
        private const val CHILD_POSITION_PREFERENCE_KEY = "sample_chooser_child_position"

        private fun isNonNullAndChecked(menuItem: MenuItem?): Boolean {
            // Temporary workaround for layouts that do not inflate the options menu.
            return menuItem != null && menuItem.isChecked()
        }
    }
}
