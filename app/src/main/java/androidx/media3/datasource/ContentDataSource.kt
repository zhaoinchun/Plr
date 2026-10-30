package androidx.media3.datasource

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min

class ContentDataSource(context: Context) : BaseDataSource( false) {
    class ContentDataSourceException(
        cause: IOException?,
        @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int
    ) : DataSourceException(cause, errorCode) {
        @Deprecated("Use {@link #ContentDataSourceException(IOException, int)}.")
        constructor(cause: IOException?) : this(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    private val resolver: ContentResolver = context.contentResolver

    override var uri: Uri? = null
    private var assetFileDescriptor: AssetFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    @Throws(ContentDataSourceException::class)  // We are inlining EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT.
    override fun open(dataSpec: DataSpec): Long {
        try {
            val uri = dataSpec.uri.normalizeScheme()
            this.uri = uri

            transferInitializing(dataSpec)

            val assetFileDescriptor: AssetFileDescriptor?
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val providerOptions = Bundle()
                // We don't want compatible media transcoding.
                providerOptions.putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
                assetFileDescriptor =
                    resolver.openTypedAssetFileDescriptor(
                        uri,  
                        "*/*",
                        providerOptions
                    )
            } else {
                // This path supports file URIs, although support may be removed in the future. See
                // [Internal ref: b/195384732].
                assetFileDescriptor = resolver.openAssetFileDescriptor(uri, "r")
            }
            this.assetFileDescriptor = assetFileDescriptor
            if (assetFileDescriptor == null) {
                // assetFileDescriptor may be null if the provider recently crashed.
                throw ContentDataSourceException(
                    IOException("Could not open file descriptor for: " + uri),
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                )
            }

            val assetFileDescriptorLength = assetFileDescriptor.getLength()
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            this.inputStream = inputStream

            // We can't rely only on the "skipped < dataSpec.position" check below to detect whether the
            // position is beyond the end of the asset being read. This is because the file may contain
            // multiple assets, and there's nothing to prevent InputStream.skip() from succeeding by
            // skipping into the data of the next asset. Hence we also need to check against the asset
            // length explicitly, which is guaranteed to be set unless the asset extends to the end of the
            // file.
            if (assetFileDescriptorLength != AssetFileDescriptor.UNKNOWN_LENGTH
                && dataSpec.position > assetFileDescriptorLength
            ) {
                throw ContentDataSourceException( 
                    null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            }
            val assetFileDescriptorOffset = assetFileDescriptor.getStartOffset()
            val skipped =
                (inputStream.skip(assetFileDescriptorOffset + dataSpec.position)
                        - assetFileDescriptorOffset)
            if (skipped != dataSpec.position) {
                // We expect the skip to be satisfied in full. If it isn't then we're probably trying to
                // read beyond the end of the last resource in the file.
                throw ContentDataSourceException( 
                    null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            }
            if (assetFileDescriptorLength == AssetFileDescriptor.UNKNOWN_LENGTH) {
                // The asset must extend to the end of the file. We can try and resolve the length with
                // FileInputStream.getChannel().size().
                val channel = inputStream.getChannel()
                val channelSize = channel.size()
                if (channelSize == 0L) {
                    bytesRemaining = C.LENGTH_UNSET.toLong()
                } else {
                    bytesRemaining = channelSize - channel.position()
                    if (bytesRemaining < 0) {
                        // The skip above was satisfied in full, but skipped beyond the end of the file.
                        throw ContentDataSourceException( 
                            null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                        )
                    }
                }
            } else {
                bytesRemaining = assetFileDescriptorLength - skipped
                if (bytesRemaining < 0) {
                    throw ContentDataSourceException( 
                        null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                    )
                }
            }
        } catch (e: ContentDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw ContentDataSourceException(
                e,
                if (e is FileNotFoundException)
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                else
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining =
                if (bytesRemaining == C.LENGTH_UNSET.toLong()) dataSpec.length else min(
                    bytesRemaining,
                    dataSpec.length
                )
        }
        opened = true
        transferStarted(dataSpec)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else bytesRemaining
    }

    @Throws(ContentDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesRead: Int
        try {
            val bytesToRead =
                if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else min(
                    bytesRemaining,
                    length.toLong()
                ).toInt()
            bytesRead = inputStream!!.read(buffer, offset, bytesToRead)
        } catch (e: IOException) {
            throw ContentDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead.toLong()
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    @Throws(ContentDataSourceException::class)
    override fun close() {
        uri = null
        try {
            if (inputStream != null) {
                inputStream!!.close()
            }
        } catch (e: IOException) {
            throw ContentDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            inputStream = null
            try {
                if (assetFileDescriptor != null) {
                    assetFileDescriptor!!.close()
                }
            } catch (e: IOException) {
                throw ContentDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            } finally {
                assetFileDescriptor = null
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }
}
