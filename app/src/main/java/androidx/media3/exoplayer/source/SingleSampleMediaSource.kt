package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.Consumer
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.MoreObjects
import com.google.common.base.Supplier
import java.util.concurrent.Executor

@Deprecated(
    """The only use for this class is subtitle playback, but it is only compatible with
      legacy subtitle decoding, which is not supported by default. Users of this class almost
      certainly need to {@linkplain TextRenderer#experimentalSetLegacyDecodingEnabled(boolean)
 *     enable legacy subtitle decoding} on their {@link ExoPlayer} instance in order to avoid
      playback errors when trying to play subtitles."""
)
class SingleSampleMediaSource private constructor(
    trackId: String?,
    subtitleConfiguration: MediaItem.SubtitleConfiguration,
    private val dataSourceFactory: DataSource.Factory,
    private val durationUs: Long,
    private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    private val treatLoadErrorsAsEndOfStream: Boolean,
    tag: Any?,
    private val downloadExecutorSupplier: Supplier<ReleasableExecutor>?
) : BaseMediaSource() {

    class Factory(private val dataSourceFactory: DataSource.Factory) {
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy =
            DefaultLoadErrorHandlingPolicy()
        private var treatLoadErrorsAsEndOfStream: Boolean = true
        private var tag: Any? = null
        private var trackId: String? = null
        private var downloadExecutorSupplier: Supplier<ReleasableExecutor>? = null

        fun setTag(tag: Any?): Factory {
            this.tag = tag
            return this
        }

        fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy?): Factory {
            this.loadErrorHandlingPolicy =
                loadErrorHandlingPolicy ?: DefaultLoadErrorHandlingPolicy()
            return this
        }

        fun setTreatLoadErrorsAsEndOfStream(treatLoadErrorsAsEndOfStream: Boolean): Factory {
            this.treatLoadErrorsAsEndOfStream = treatLoadErrorsAsEndOfStream
            return this
        }

        fun <T : Executor?> setDownloadExecutor(
            downloadExecutor: Supplier<T>, downloadExecutorReleaser: Consumer<T>
        ): Factory {
            this.downloadExecutorSupplier =
                Supplier {
                    ReleasableExecutor.from<T>(
                        downloadExecutor.get(),
                        downloadExecutorReleaser
                    )
                }
            return this
        }

        fun createMediaSource(
            subtitleConfiguration: MediaItem.SubtitleConfiguration, durationUs: Long
        ): SingleSampleMediaSource {
            return SingleSampleMediaSource(
                trackId,
                subtitleConfiguration,
                dataSourceFactory,
                durationUs,
                loadErrorHandlingPolicy,
                treatLoadErrorsAsEndOfStream,
                tag,
                downloadExecutorSupplier
            )
        }
    }

    private val dataSpec: DataSpec
    private val format: Format
    private val timeline: Timeline
    override val mediaItem: MediaItem

    private var transferListener: TransferListener? = null

    init {
        this.mediaItem =
            MediaItem.Builder()
                .setUri(Uri.EMPTY)
                .setMediaId(subtitleConfiguration.uri.toString())
                .setSubtitleConfigurations(
                    listOf(
                        subtitleConfiguration
                    )
                )
                .setTag(tag)
                .build()
        this.format =
            Format.Builder()
                .setSampleMimeType(
                    MoreObjects.firstNonNull<String?>(
                        subtitleConfiguration.mimeType,
                        MimeTypes.TEXT_UNKNOWN
                    )
                )
                .setLanguage(subtitleConfiguration.language)
                .setSelectionFlags(subtitleConfiguration.selectionFlags)
                .setRoleFlags(subtitleConfiguration.roleFlags)
                .setLabel(subtitleConfiguration.label)
                .setId(subtitleConfiguration.id ?: trackId)
                .build()
        this.dataSpec =
            DataSpec.Builder()
                .setUri(subtitleConfiguration.uri)
                .setFlags(DataSpec.FLAG_ALLOW_GZIP)
                .build()
        this.timeline =
            SinglePeriodTimeline(
                durationUs,  
                true,  
                false,  
                false,  
                null,
                mediaItem
            )
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        transferListener = mediaTransferListener
        refreshSourceInfo(timeline)
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // Do nothing.
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        return SingleSampleMediaPeriod(
            dataSpec,
            dataSourceFactory,
            transferListener,
            format,
            durationUs,
            loadErrorHandlingPolicy,
            createEventDispatcher(id),
            treatLoadErrorsAsEndOfStream,
            downloadExecutorSupplier?.get()
        )
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as SingleSampleMediaPeriod).release()
    }

    override fun releaseSourceInternal() {
        // Do nothing.
    }
}
