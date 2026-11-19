package main

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.MediaItem.LocalConfiguration
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.getDrmUuid
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList

/** Util to read from and populate an intent.  */
object IntentUtil {
    // Actions.
    const val ACTION_VIEW: String = "main.action.VIEW"
    const val ACTION_VIEW_LIST: String = "main.action.VIEW_LIST"

    // Activity extras.
    const val PREFER_EXTENSION_DECODERS_EXTRA: String = "prefer_extension_decoders"

    // Media item configuration extras.
    const val URI_EXTRA: String = "uri"
    const val TITLE_EXTRA: String = "title"
    const val MIME_TYPE_EXTRA: String = "mime_type"
    const val CLIP_START_POSITION_MS_EXTRA: String = "clip_start_position_ms"
    const val CLIP_END_POSITION_MS_EXTRA: String = "clip_end_position_ms"
    const val IMAGE_DURATION_MS: String = "image_duration_ms"

    const val AD_TAG_URI_EXTRA: String = "ad_tag_uri"

    const val DRM_SCHEME_EXTRA: String = "drm_scheme"
    const val DRM_LICENSE_URI_EXTRA: String = "drm_license_uri"
    const val DRM_KEY_REQUEST_PROPERTIES_EXTRA: String = "drm_key_request_properties"
    const val DRM_SESSION_FOR_CLEAR_CONTENT: String = "drm_session_for_clear_content"
    const val DRM_MULTI_SESSION_EXTRA: String = "drm_multi_session"
    const val DRM_FORCE_DEFAULT_LICENSE_URI_EXTRA: String = "drm_force_default_license_uri"

    const val SUBTITLE_URI_EXTRA: String = "subtitle_uri"
    const val SUBTITLE_MIME_TYPE_EXTRA: String = "subtitle_mime_type"
    const val SUBTITLE_LANGUAGE_EXTRA: String = "subtitle_language"
    const val REPEAT_MODE_EXTRA: String = "repeat_mode"

    @JvmStatic
    fun parseRepeatModeExtra(repeatMode: String): Int {
        when (repeatMode) {
            "OFF" -> return Player.REPEAT_MODE_OFF
            "ONE" -> return Player.REPEAT_MODE_ONE
            "ALL" -> return Player.REPEAT_MODE_ALL
            else -> throw IllegalArgumentException(
                "Argument " + repeatMode + " does not match any of the repeat modes: OFF|ONE|ALL"
            )
        }
    }

    /** Creates a list of [media items][MediaItem] from an [Intent].  */
    @JvmStatic
    fun createMediaItemsFromIntent(intent: Intent): MutableList<MediaItem?> {
        val mediaItems: MutableList<MediaItem?> = ArrayList<MediaItem?>()
        if (ACTION_VIEW_LIST == intent.getAction()) {
            var index = 0
            while (intent.hasExtra(URI_EXTRA + "_" + index)) {
                val uri = Uri.parse(intent.getStringExtra(URI_EXTRA + "_" + index))
                mediaItems.add(
                    createMediaItemFromIntent(
                        uri,
                        intent,  /* extrasKeySuffix= */
                        "_" + index
                    )
                )
                index++
            }
        } else {
            val uri = intent.getData()
            mediaItems.add(createMediaItemFromIntent(uri, intent,  /* extrasKeySuffix= */""))
        }
        return mediaItems
    }

    /** Populates the intent with the given list of [media items][MediaItem].  */
    fun addToIntent(mediaItems: MutableList<MediaItem>, intent: Intent) {
        Preconditions.checkArgument(!mediaItems.isEmpty())
        if (mediaItems.size == 1) {
            val mediaItem = mediaItems.get(0)
            val localConfiguration =
                Preconditions.checkNotNull<LocalConfiguration?>(mediaItem.localConfiguration)
            intent.setAction(ACTION_VIEW).setData(mediaItem.localConfiguration!!.uri)
            if (mediaItem.mediaMetadata.title != null) {
                intent.putExtra(TITLE_EXTRA, mediaItem.mediaMetadata.title)
            }
            IntentUtil.addLocalConfigurationToIntent(
                localConfiguration,
                intent,  /* extrasKeySuffix= */
                ""
            )
            addClippingConfigurationToIntent(
                mediaItem.clippingConfiguration, intent,  /* extrasKeySuffix= */""
            )
        } else {
            intent.setAction(ACTION_VIEW_LIST)
            for (i in mediaItems.indices) {
                val mediaItem = mediaItems.get(i)
                val localConfiguration =
                    Preconditions.checkNotNull<LocalConfiguration?>(mediaItem.localConfiguration)
                intent.putExtra(URI_EXTRA + ("_" + i), localConfiguration.uri.toString())
                IntentUtil.addLocalConfigurationToIntent(
                    localConfiguration,
                    intent,  /* extrasKeySuffix= */
                    "_" + i
                )
                addClippingConfigurationToIntent(
                    mediaItem.clippingConfiguration, intent,  /* extrasKeySuffix= */"_" + i
                )
                if (mediaItem.mediaMetadata.title != null) {
                    intent.putExtra(TITLE_EXTRA + ("_" + i), mediaItem.mediaMetadata.title)
                }
            }
        }
    }

    private fun createMediaItemFromIntent(
        uri: Uri?, intent: Intent, extrasKeySuffix: String
    ): MediaItem {
        val mimeType = intent.getStringExtra(MIME_TYPE_EXTRA + extrasKeySuffix)
        val title = intent.getStringExtra(TITLE_EXTRA + extrasKeySuffix)
        val adTagUri = intent.getStringExtra(AD_TAG_URI_EXTRA + extrasKeySuffix)
        val subtitleConfiguration =
            createSubtitleConfiguration(intent, extrasKeySuffix)
        val imageDurationMs = intent.getLongExtra(IMAGE_DURATION_MS + extrasKeySuffix, C.TIME_UNSET)
        val builder =
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mimeType)
                .setMediaMetadata(MediaMetadata(title = title))
                .setClippingConfiguration(
                    ClippingConfiguration(
                        startPositionUs = Util.msToUs(
                            intent.getLongExtra(CLIP_START_POSITION_MS_EXTRA + extrasKeySuffix, 0)
                        ),
                        endPositionUs = Util.msToUs(
                            intent.getLongExtra(
                                CLIP_END_POSITION_MS_EXTRA + extrasKeySuffix, C.TIME_END_OF_SOURCE
                            )
                        )
                    )
                )
                .setImageDurationMs(imageDurationMs)
        if (adTagUri != null) {
            builder.setAdsConfiguration(
                MediaItem.AdsConfiguration(adTagUri = adTagUri.toUri())
            )
        }
        if (subtitleConfiguration != null) {
            builder.setSubtitleConfigurations(
                ImmutableList.of<SubtitleConfiguration>(
                    subtitleConfiguration
                )
            )
        }

        return populateDrmPropertiesFromIntent(builder, intent, extrasKeySuffix).build()
    }

    private fun createSubtitleConfiguration(
        intent: Intent, extrasKeySuffix: String?
    ): SubtitleConfiguration? {
        if (!intent.hasExtra(SUBTITLE_URI_EXTRA + extrasKeySuffix)) {
            return null
        }
        return SubtitleConfiguration(
            uri = intent.getStringExtra(SUBTITLE_URI_EXTRA + extrasKeySuffix)!!.toUri(),
            mimeType = intent.getStringExtra(SUBTITLE_MIME_TYPE_EXTRA + extrasKeySuffix),
            language = intent.getStringExtra(SUBTITLE_LANGUAGE_EXTRA + extrasKeySuffix),
            selectionFlags = C.SELECTION_FLAG_DEFAULT
        )
    }

    private fun populateDrmPropertiesFromIntent(
        builder: MediaItem.Builder, intent: Intent, extrasKeySuffix: String
    ): MediaItem.Builder {
        val schemeKey = DRM_SCHEME_EXTRA + extrasKeySuffix
        val drmSchemeExtra = intent.getStringExtra(schemeKey)
        if (drmSchemeExtra == null) {
            return builder
        }
        val headers: MutableMap<String, String> = HashMap<String, String>()
        val keyRequestPropertiesArray =
            intent.getStringArrayExtra(DRM_KEY_REQUEST_PROPERTIES_EXTRA + extrasKeySuffix)
        if (keyRequestPropertiesArray != null) {
            var i = 0
            while (i < keyRequestPropertiesArray.size) {
                headers.put(keyRequestPropertiesArray[i], keyRequestPropertiesArray[i + 1])
                i += 2
            }
        }
        val drmUuid = getDrmUuid(drmSchemeExtra)
        if (drmUuid != null) {
            DrmConfiguration(
                scheme = drmUuid,
                licenseUri = intent.getStringExtra(DRM_LICENSE_URI_EXTRA + extrasKeySuffix)
                    ?.toUri(),
                multiSession = intent.getBooleanExtra(
                    DRM_MULTI_SESSION_EXTRA + extrasKeySuffix,
                    false
                ),
                forceDefaultLicenseUri = intent.getBooleanExtra(
                    DRM_FORCE_DEFAULT_LICENSE_URI_EXTRA + extrasKeySuffix, false
                ),
                licenseRequestHeaders = headers
            ).apply {
                setForceSessionsForAudioAndVideoTracks(
                    intent.getBooleanExtra(
                        DRM_SESSION_FOR_CLEAR_CONTENT + extrasKeySuffix,
                        false
                    )
                )
                builder.setDrmConfiguration(this)
            }
        }
        return builder
    }

    private fun addLocalConfigurationToIntent(
        localConfiguration: LocalConfiguration, intent: Intent, extrasKeySuffix: String?
    ) {
        intent
            .putExtra(MIME_TYPE_EXTRA + extrasKeySuffix, localConfiguration.mimeType)
            .putExtra(
                AD_TAG_URI_EXTRA + extrasKeySuffix,
                if (localConfiguration.adsConfiguration != null)
                    localConfiguration.adsConfiguration.adTagUri.toString()
                else
                    null
            )
        if (localConfiguration.drmConfiguration != null) {
            addDrmConfigurationToIntent(
                localConfiguration.drmConfiguration,
                intent,
                extrasKeySuffix
            )
        }
        if (!localConfiguration.subtitleConfigurations.isEmpty()) {
            Preconditions.checkState(localConfiguration.subtitleConfigurations.size == 1)
            val subtitleConfiguration =
                localConfiguration.subtitleConfigurations.get(0)
            intent.putExtra(
                SUBTITLE_URI_EXTRA + extrasKeySuffix,
                subtitleConfiguration.uri.toString()
            )
            intent.putExtra(
                SUBTITLE_MIME_TYPE_EXTRA + extrasKeySuffix,
                subtitleConfiguration.mimeType
            )
            intent.putExtra(
                SUBTITLE_LANGUAGE_EXTRA + extrasKeySuffix,
                subtitleConfiguration.language
            )
        }
        if (localConfiguration.imageDurationMs != C.TIME_UNSET) {
            intent.putExtra(IMAGE_DURATION_MS + extrasKeySuffix, localConfiguration.imageDurationMs)
        }
    }

    private fun addDrmConfigurationToIntent(
        drmConfiguration: DrmConfiguration, intent: Intent, extrasKeySuffix: String?
    ) {
        intent.putExtra(DRM_SCHEME_EXTRA + extrasKeySuffix, drmConfiguration.scheme.toString())
        intent.putExtra(
            DRM_LICENSE_URI_EXTRA + extrasKeySuffix,
            if (drmConfiguration.licenseUri != null) drmConfiguration.licenseUri.toString() else null
        )
        intent.putExtra(DRM_MULTI_SESSION_EXTRA + extrasKeySuffix, drmConfiguration.multiSession)
        intent.putExtra(
            DRM_FORCE_DEFAULT_LICENSE_URI_EXTRA + extrasKeySuffix,
            drmConfiguration.forceDefaultLicenseUri
        )

        val drmKeyRequestProperties =
            arrayOfNulls<String>(drmConfiguration.licenseRequestHeaders.size * 2)
        var index = 0
        for (entry in drmConfiguration.licenseRequestHeaders.entries) {
            drmKeyRequestProperties[index++] = entry.key
            drmKeyRequestProperties[index++] = entry.value
        }
        intent.putExtra(DRM_KEY_REQUEST_PROPERTIES_EXTRA + extrasKeySuffix, drmKeyRequestProperties)

        val forcedDrmSessionTrackTypes =
            drmConfiguration.forcedSessionTrackTypes
        if (!forcedDrmSessionTrackTypes.isEmpty()) {
            // Only video and audio together are supported.
            Preconditions.checkState(
                forcedDrmSessionTrackTypes.size == 2 && forcedDrmSessionTrackTypes.contains(C.TRACK_TYPE_VIDEO)
                        && forcedDrmSessionTrackTypes.contains(C.TRACK_TYPE_AUDIO)
            )
            intent.putExtra(DRM_SESSION_FOR_CLEAR_CONTENT + extrasKeySuffix, true)
        }
    }

    private fun addClippingConfigurationToIntent(
        clippingConfiguration: ClippingConfiguration,
        intent: Intent,
        extrasKeySuffix: String?
    ) {
        if (clippingConfiguration.startPositionMs != 0L) {
            intent.putExtra(
                CLIP_START_POSITION_MS_EXTRA + extrasKeySuffix,
                clippingConfiguration.startPositionMs
            )
        }
        if (clippingConfiguration.endPositionMs != C.TIME_END_OF_SOURCE) {
            intent.putExtra(
                CLIP_END_POSITION_MS_EXTRA + extrasKeySuffix, clippingConfiguration.endPositionMs
            )
        }
    }
}
