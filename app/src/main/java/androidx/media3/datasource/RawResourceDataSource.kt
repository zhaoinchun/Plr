package androidx.media3.datasource

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.net.Uri
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.google.common.base.Preconditions
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

class RawResourceDataSource(context: Context) : BaseDataSource( false) {
    class RawResourceDataSourceException : DataSourceException {
        @Deprecated("Use {@link #RawResourceDataSourceException(String, Throwable, int)}.")
        constructor(message: String?) : super(
            message,  
            null,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )

        @Deprecated("Use {@link #RawResourceDataSourceException(String, Throwable, int)}.")
        constructor(cause: Throwable?) : super(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        constructor(
            message: String?,
            cause: Throwable?,
            @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int
        ) : super(message, cause, errorCode)
    }

    private val applicationContext: Context

    private var dataSpec: DataSpec? = null
    private var assetFileDescriptor: AssetFileDescriptor? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    init {
        this.applicationContext = context.getApplicationContext()
    }

    @Throws(RawResourceDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        assetFileDescriptor = openAssetFileDescriptor(applicationContext, dataSpec)

        val assetFileDescriptorLength = assetFileDescriptor!!.getLength()
        val inputStream = FileInputStream(assetFileDescriptor!!.getFileDescriptor())
        this.inputStream = inputStream

        try {
            // We can't rely only on the "skipped < dataSpec.position" check below to detect whether the
            // position is beyond the end of the resource being read. This is because the file will
            // typically contain multiple resources, and there's nothing to prevent InputStream.skip()
            // from succeeding by skipping into the data of the next resource. Hence we also need to check
            // against the resource length explicitly, which is guaranteed to be set unless the resource
            // extends to the end of the file.
            if (assetFileDescriptorLength != AssetFileDescriptor.UNKNOWN_LENGTH
                && dataSpec.position > assetFileDescriptorLength
            ) {
                throw RawResourceDataSourceException( 
                    null,  
                    null,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            }
            val assetFileDescriptorOffset = assetFileDescriptor!!.getStartOffset()
            val skipped =
                (inputStream.skip(assetFileDescriptorOffset + dataSpec.position)
                        - assetFileDescriptorOffset)
            if (skipped != dataSpec.position) {
                // We expect the skip to be satisfied in full. If it isn't then we're probably trying to
                // read beyond the end of the last resource in the file.
                throw RawResourceDataSourceException( 
                    null,  
                    null,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            }
            if (assetFileDescriptorLength == AssetFileDescriptor.UNKNOWN_LENGTH) {
                // The asset must extend to the end of the file. We can try and resolve the length with
                // FileInputStream.getChannel().size().
                val channel = inputStream.getChannel()
                if (channel.size() == 0L) {
                    bytesRemaining = C.LENGTH_UNSET.toLong()
                } else {
                    bytesRemaining = channel.size() - channel.position()
                    if (bytesRemaining < 0) {
                        // The skip above was satisfied in full, but skipped beyond the end of the file.
                        throw RawResourceDataSourceException( 
                            null,  
                            null,
                            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                        )
                    }
                }
            } else {
                bytesRemaining = assetFileDescriptorLength - skipped
                if (bytesRemaining < 0) {
                    throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                }
            }
        } catch (e: RawResourceDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw RawResourceDataSourceException( 
                null, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED
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

    @Throws(RawResourceDataSourceException::class)
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
            throw RawResourceDataSourceException( 
                null, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        if (bytesRead == -1) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                // End of stream reached having not read sufficient data.
                throw RawResourceDataSourceException(
                    "End of stream reached having not read sufficient data.",
                    EOFException(),
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                )
            }
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead.toLong()
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override val uri: Uri?
        get() = dataSpec?.uri

    @Throws(RawResourceDataSourceException::class)
    override fun close() {
        dataSpec = null
        try {
            if (inputStream != null) {
                inputStream!!.close()
            }
        } catch (e: IOException) {
            throw RawResourceDataSourceException( 
                null, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        } finally {
            inputStream = null
            try {
                if (assetFileDescriptor != null) {
                    assetFileDescriptor!!.close()
                }
            } catch (e: IOException) {
                throw RawResourceDataSourceException( 
                    null, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                )
            } finally {
                assetFileDescriptor = null
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }

    companion object {
        @Suppress("deprecation") // Using deprecated scheme
        @Deprecated(
            """Use {@code new
   *     Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).path(Integer.toString(rawResourceId)).build()}
        instead."""
        )
        fun buildRawResourceUri(rawResourceId: Int): Uri? {
            return Uri.parse(RAW_RESOURCE_SCHEME + ":///" + rawResourceId)
        }

        @Deprecated("Use {@link ContentResolver#SCHEME_ANDROID_RESOURCE} instead.")
        const val RAW_RESOURCE_SCHEME: String = "rawresource"

        @Suppress("deprecation") // Accepting deprecated scheme
        @Throws(RawResourceDataSourceException::class)
        private fun openAssetFileDescriptor(
            applicationContext: Context, dataSpec: DataSpec
        ): AssetFileDescriptor {
            val normalizedUri = dataSpec.uri.normalizeScheme()
            val resources: Resources
            val resourceId: Int
            if (TextUtils.equals(RAW_RESOURCE_SCHEME, normalizedUri.getScheme())) {
                resources = applicationContext.getResources()
                val pathSegments = normalizedUri.getPathSegments()
                if (pathSegments.size == 1) {
                    resourceId = Companion.parseResourceId(pathSegments[0]!!)
                } else {
                    throw RawResourceDataSourceException(
                        (RAW_RESOURCE_SCHEME
                                + ":// URI must have exactly one path element, found "
                                + pathSegments.size)
                    )
                }
            } else if (TextUtils.equals(
                    ContentResolver.SCHEME_ANDROID_RESOURCE, normalizedUri.getScheme()
                )
            ) {
                var path = normalizedUri.path!!
                if (path.startsWith("/")) {
                    path = path.substring(1)
                }
                val packageName =
                    if (TextUtils.isEmpty(normalizedUri.getHost()))
                        applicationContext.getPackageName()
                    else
                        normalizedUri.getHost()
                if (packageName == applicationContext.getPackageName()) {
                    resources = applicationContext.getResources()
                } else {
                    try {
                        resources =
                            applicationContext.getPackageManager()
                                .getResourcesForApplication(packageName!!)
                    } catch (e: PackageManager.NameNotFoundException) {
                        throw RawResourceDataSourceException(
                            ("Package in "
                                    + ContentResolver.SCHEME_ANDROID_RESOURCE
                                    + ":// URI not found. Check http://g.co/dev/packagevisibility."),
                            e,
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }
                }
                if (path.matches("\\d+".toRegex())) {
                    resourceId = parseResourceId(path)
                } else {
                    // The javadoc of this class already discourages the URI form that requires this API call.
                    @SuppressLint("DiscouragedApi") val resourceIdFromName =
                        resources.getIdentifier(
                            packageName + ":" + path,  "raw",  null
                        )
                    if (resourceIdFromName != 0) {
                        resourceId = resourceIdFromName
                    } else {
                        throw RawResourceDataSourceException(
                            "Resource not found.",  
                            null,
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }
                }
            } else {
                throw RawResourceDataSourceException(
                    ("Unsupported URI scheme ("
                            + normalizedUri.getScheme()
                            + "). Only "
                            + ContentResolver.SCHEME_ANDROID_RESOURCE
                            + " is supported."),  
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
            }

            val assetFileDescriptor: AssetFileDescriptor
            try {
                assetFileDescriptor = resources.openRawResourceFd(resourceId)
            } catch (e: Resources.NotFoundException) {
                throw RawResourceDataSourceException( 
                    null, e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                )
            }

            if (assetFileDescriptor == null) {
                throw RawResourceDataSourceException(
                    "Resource is compressed: " + normalizedUri,  
                    null,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                )
            }
            return assetFileDescriptor
        }

        @Throws(RawResourceDataSourceException::class)
        private fun parseResourceId(resourceId: String): Int {
            try {
                return resourceId.toInt()
            } catch (e: NumberFormatException) {
                throw RawResourceDataSourceException(
                    "Resource identifier must be an integer.",  
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
            }
        }
    }
}
