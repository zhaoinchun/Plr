package androidx.media3.exoplayer.offline

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes.normalizeMimeType
import androidx.media3.common.StreamKey
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.Collections
import androidx.core.net.toUri

class DownloadRequest : Parcelable {
    class UnsupportedRequestException : IOException()

    class Builder(private val id: String, private val uri: Uri) {
        private var mimeType: String? = null
        private var streamKeys: List<StreamKey>? = null
        private var keySetId: ByteArray? = null
        private var customCacheKey: String? = null
        private var data: ByteArray? = null
        private var byteRange: ByteRange? = null
        private var timeRange: TimeRange? = null

        fun setMimeType(mimeType: String?): Builder {
            this.mimeType = normalizeMimeType(mimeType)
            return this
        }

        fun setStreamKeys(streamKeys: List<StreamKey>?): Builder {
            this.streamKeys = streamKeys
            return this
        }

        fun setKeySetId(keySetId: ByteArray?): Builder {
            this.keySetId = keySetId
            return this
        }

        fun setCustomCacheKey(customCacheKey: String?): Builder {
            this.customCacheKey = customCacheKey
            return this
        }

        fun setData(data: ByteArray?): Builder {
            this.data = data
            return this
        }

        fun setByteRange(offset: Long, length: Long): Builder {
            this.byteRange = ByteRange(offset, length)
            return this
        }

        fun setTimeRange(startPositionUs: Long, durationUs: Long): Builder {
            this.timeRange = TimeRange(startPositionUs, durationUs)
            return this
        }

        fun build(): DownloadRequest {
            return DownloadRequest(
                id,
                uri,
                mimeType,
                streamKeys ?: listOf(),
                keySetId,
                customCacheKey,
                data,
                byteRange,
                timeRange
            )
        }
    }

    val id: String
    val uri: Uri
    val mimeType: String?
    val streamKeys: MutableList<StreamKey>
    val keySetId: ByteArray?
    val customCacheKey: String?
    val data: ByteArray?
    val byteRange: ByteRange?
    val timeRange: TimeRange?

    private constructor(
        id: String,
        uri: Uri,
        mimeType: String?,
        streamKeys: List<StreamKey>,
        keySetId: ByteArray?,
        customCacheKey: String?,
        data: ByteArray?,
        byteRange: ByteRange?,
        timeRange: TimeRange?
    ) {
        @C.ContentType val contentType: @C.ContentType Int =
            inferContentTypeForUriAndMimeType(uri, mimeType)
        if (contentType == C.CONTENT_TYPE_DASH || contentType == C.CONTENT_TYPE_HLS || contentType == C.CONTENT_TYPE_SS) {
            Preconditions.checkArgument(
                customCacheKey == null, "customCacheKey must be null for type: %s", contentType
            )
            this.byteRange = null
            this.timeRange = timeRange
        } else {
            this.byteRange = byteRange
            this.timeRange = null
        }
        this.id = id
        this.uri = uri
        this.mimeType = mimeType
        val mutableKeys = ArrayList<StreamKey>(streamKeys)
        Collections.sort(mutableKeys)
        this.streamKeys = Collections.unmodifiableList(mutableKeys)
        this.keySetId = keySetId?.copyOf(keySetId.size)
        this.customCacheKey = customCacheKey
        this.data = data?.copyOf(data.size) ?: Util.EMPTY_BYTE_ARRAY
    }

    internal constructor(`in`: Parcel) {
        id = `in`.readString()!!
        uri = (`in`.readString()!!).toUri()
        mimeType = `in`.readString()
        val streamKeyCount = `in`.readInt()
        val mutableStreamKeys = ArrayList<StreamKey?>(streamKeyCount)
        for (i in 0..<streamKeyCount) {
            mutableStreamKeys.add(`in`.readParcelable<StreamKey?>(StreamKey::class.java.getClassLoader()))
        }
        streamKeys = Collections.unmodifiableList<StreamKey?>(mutableStreamKeys)
        keySetId = `in`.createByteArray()
        customCacheKey = `in`.readString()
        data = `in`.createByteArray()!!
        byteRange = `in`.readParcelable<ByteRange?>(ByteRange::class.java.getClassLoader())
        timeRange = `in`.readParcelable<TimeRange?>(TimeRange::class.java.getClassLoader())
    }

    fun copyWithId(id: String): DownloadRequest {
        return DownloadRequest(
            id, uri, mimeType, streamKeys, keySetId, customCacheKey, data, byteRange, timeRange
        )
    }

    fun copyWithKeySetId(keySetId: ByteArray?): DownloadRequest {
        return DownloadRequest(
            id, uri, mimeType, streamKeys, keySetId, customCacheKey, data, byteRange, timeRange
        )
    }

    fun copyWithMergedRequest(newRequest: DownloadRequest): DownloadRequest {
        Preconditions.checkArgument(id == newRequest.id)
        val mergedKeys: MutableList<StreamKey>?
        if (streamKeys.isEmpty() || newRequest.streamKeys.isEmpty()) {
            // If either streamKeys is empty then all streams should be downloaded.
            mergedKeys = mutableListOf<StreamKey>()
        } else {
            mergedKeys = ArrayList<StreamKey>(streamKeys)
            for (i in newRequest.streamKeys.indices) {
                val newKey = newRequest.streamKeys[i]
                if (!mergedKeys.contains(newKey)) {
                    mergedKeys.add(newKey)
                }
            }
        }
        return DownloadRequest(
            id,
            newRequest.uri,
            newRequest.mimeType,
            mergedKeys,
            newRequest.keySetId,
            newRequest.customCacheKey,
            newRequest.data,
            newRequest.byteRange,
            newRequest.timeRange
        )
    }

    fun toMediaItem(builder: MediaItem.Builder = MediaItem.Builder()): MediaItem {
        return builder
            .setMediaId(id)
            .setUri(uri)
            .setCustomCacheKey(customCacheKey)
            .setMimeType(mimeType)
            .setStreamKeys(streamKeys)
            .build()
    }

    override fun toString(): String {
        return mimeType + ":" + id
    }

    override fun equals(o: Any?): Boolean {
        if (o !is DownloadRequest) {
            return false
        }
        val that = o
        return id == that.id
                && uri == that.uri
                && mimeType == that.mimeType
                && streamKeys == that.streamKeys
                && keySetId.contentEquals(that.keySetId) && customCacheKey == that.customCacheKey
                && data.contentEquals(that.data) && byteRange == that.byteRange
                && timeRange == that.timeRange
    }

    override fun hashCode(): Int {
        var result = 31 * id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + (if (mimeType != null) mimeType.hashCode() else 0)
        result = 31 * result + streamKeys.hashCode()
        result = 31 * result + keySetId.contentHashCode()
        result = 31 * result + (if (customCacheKey != null) customCacheKey.hashCode() else 0)
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (if (byteRange != null) byteRange.hashCode() else 0)
        result = 31 * result + (if (timeRange != null) timeRange.hashCode() else 0)
        return result
    }

    // Parcelable implementation.
    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(uri.toString())
        dest.writeString(mimeType)
        dest.writeInt(streamKeys.size)
        for (i in streamKeys.indices) {
            dest.writeParcelable(streamKeys[i], 0)
        }
        dest.writeByteArray(keySetId)
        dest.writeString(customCacheKey)
        dest.writeByteArray(data)
        dest.writeParcelable(byteRange, 0)
        dest.writeParcelable(timeRange, 0)
    }

    class ByteRange internal constructor(offset: Long, length: Long) : Parcelable {

        val offset: Long

        val length: Long

        internal constructor(`in`: Parcel) : this(`in`.readLong(), `in`.readLong())

        override fun equals(o: Any?): Boolean {
            if (o !is ByteRange) {
                return false
            }
            val that = o
            return offset == that.offset && length == that.length
        }

        override fun hashCode(): Int {
            var result = 31 * offset.toInt()
            result = 31 * result + length.toInt()
            return result
        }

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeLong(offset)
            dest.writeLong(length)
        }

        init {
            Preconditions.checkArgument(offset >= 0)
            Preconditions.checkArgument(length >= 0 || length == C.LENGTH_UNSET.toLong())
            this.offset = offset
            this.length = length
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<ByteRange?> = object : Parcelable.Creator<ByteRange?> {
                override fun createFromParcel(`in`: Parcel): ByteRange {
                    return ByteRange(`in`)
                }

                override fun newArray(size: Int): Array<ByteRange?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    class TimeRange internal constructor(startPositionUs: Long, durationUs: Long) : Parcelable {
        val startPositionUs: Long
        val durationUs: Long

        internal constructor(`in`: Parcel) : this(`in`.readLong(), `in`.readLong())

        override fun equals(o: Any?): Boolean {
            if (o !is TimeRange) {
                return false
            }
            val that = o
            return startPositionUs == that.startPositionUs && durationUs == that.durationUs
        }

        override fun hashCode(): Int {
            var result = 31 * startPositionUs.toInt()
            result = 31 * result + durationUs.toInt()
            return result
        }

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeLong(startPositionUs)
            dest.writeLong(durationUs)
        }

        init {
            Preconditions.checkArgument(durationUs >= 0 || durationUs == C.TIME_UNSET)
            this.startPositionUs = startPositionUs
            this.durationUs = durationUs
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<TimeRange?> = object : Parcelable.Creator<TimeRange?> {
                override fun createFromParcel(`in`: Parcel): TimeRange {
                    return TimeRange(`in`)
                }

                override fun newArray(size: Int): Array<TimeRange?> {
                    return arrayOfNulls<TimeRange>(size)
                }
            }
        }
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<DownloadRequest?> =
            object : Parcelable.Creator<DownloadRequest?> {
                override fun createFromParcel(`in`: Parcel): DownloadRequest {
                    return DownloadRequest(`in`)
                }

                override fun newArray(size: Int): Array<DownloadRequest?> {
                    return arrayOfNulls<DownloadRequest>(size)
                }
            }
    }
}
