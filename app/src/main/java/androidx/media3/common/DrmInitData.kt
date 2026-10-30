package androidx.media3.common

import android.os.Parcel
import android.os.Parcelable
import android.text.TextUtils
import androidx.media3.common.DrmInitData.SchemeData
import androidx.media3.common.MimeTypes.normalizeMimeType
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.util.Arrays
import java.util.UUID

class DrmInitData : Comparator<SchemeData>, Parcelable {
    private var schemeDatas: Array<SchemeData>? = null

    // Lazily initialized hashcode.
    private var hashCode = 0

    var schemeType: String? = null

    var schemeDataCount: Int = 0

    constructor(schemeDatas: List<SchemeData>) : this(
        null,
        false,
        schemeDatas.toTypedArray<SchemeData>()
    )

    constructor(schemeType: String?, schemeDatas: List<SchemeData>) : this(
        schemeType,
        false,
        schemeDatas.toTypedArray<SchemeData>()
    )

    constructor(schemeDatas: Array<SchemeData>) : this(null, schemeDatas)

    constructor(schemeType: String?, schemeDatas: Array<SchemeData>) : this(
        schemeType,
        true,
        schemeDatas
    )

     constructor(
        schemeType: String?,
        cloneSchemeDatas: Boolean,
         schemeDatas: Array<SchemeData>
    ) {
        var schemeDatas = schemeDatas
        this.schemeType = schemeType
        if (cloneSchemeDatas) {
            schemeDatas = schemeDatas.clone()
        }
        this.schemeDatas = schemeDatas
        schemeDataCount = schemeDatas.size
        // Sorting ensures that universal scheme data (i.e. data that applies to all schemes) is matched
        // last. It's also required by the equals and hashcode implementations.
        Arrays.sort<SchemeData?>(this.schemeDatas, this)
    }

    internal constructor(`in`: Parcel) {
        schemeType = `in`.readString()
        schemeDatas = `in`.createTypedArray<SchemeData?>(SchemeData.CREATOR)
        schemeDataCount = schemeDatas!!.size
    }

    fun get(index: Int): SchemeData? {
        return schemeDatas!![index]
    }

    fun copyWithSchemeType(schemeType: String?): DrmInitData {
        if (this.schemeType == schemeType) {
            return this
        }
        return DrmInitData(schemeType, false, schemeDatas!!)
    }

    fun merge(drmInitData: DrmInitData): DrmInitData {
        Preconditions.checkState(
            schemeType == null || drmInitData.schemeType == null || TextUtils.equals(
                schemeType,
                drmInitData.schemeType
            )
        )
        val mergedSchemeType = if (schemeType != null) this.schemeType else drmInitData.schemeType
        val mergedSchemeDatas: Array<SchemeData> = schemeDatas?.toMutableList()?.apply {
            drmInitData.schemeDatas?.also {
                addAll(it)
            }
        }?.toTypedArray()?: emptyArray<SchemeData>()
        return DrmInitData(mergedSchemeType, mergedSchemeDatas)
    }

    override fun hashCode(): Int {
        if (hashCode == 0) {
            var result = schemeType?.hashCode() ?: 0
            result = 31 * result + schemeDatas.contentHashCode()
            hashCode = result
        }
        return hashCode
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as DrmInitData
        return schemeType == other.schemeType
                && schemeDatas.contentEquals(other.schemeDatas)
    }

    override fun compare(first: SchemeData, second: SchemeData): Int {
        return if (C.UUID_NIL == first.uuid)
            (if (C.UUID_NIL == second.uuid) 0 else 1)
        else
            first.uuid.compareTo(second.uuid)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(schemeType)
        dest.writeTypedArray<SchemeData?>(schemeDatas, 0)
    }

    class SchemeData : Parcelable {
        // Lazily initialized hashcode.
        private var hashCode = 0
        val uuid: UUID
        val licenseServerUrl: String?
        val mimeType: String?
        val data: ByteArray?

        constructor(
            uuid: UUID?,
            mimeType: String?,
            data: ByteArray?
        ) : this(uuid,  null, mimeType, data)

        constructor(uuid: UUID?, licenseServerUrl: String?, mimeType: String?, data: ByteArray?) {
            this.uuid = uuid!!
            this.licenseServerUrl = licenseServerUrl
            this.mimeType = normalizeMimeType(mimeType)
            this.data = data
        }

        internal constructor(`in`: Parcel) {
            uuid = UUID(`in`.readLong(), `in`.readLong())
            licenseServerUrl = `in`.readString()
            mimeType = `in`.readString()
            data = `in`.createByteArray()
        }

        fun matches(schemeUuid: UUID): Boolean {
            return C.UUID_NIL == uuid || schemeUuid == uuid
        }

        fun canReplace(other: SchemeData): Boolean {
            return hasData() && !other.hasData() && matches(other.uuid)
        }

        fun hasData(): Boolean {
            return data != null
        }

        fun copyWithData(data: ByteArray?): SchemeData {
            return SchemeData(uuid, licenseServerUrl, mimeType, data)
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is SchemeData) {
                return false
            }
            if (obj === this) {
                return true
            }
            val other = obj
            return licenseServerUrl == other.licenseServerUrl
                    && mimeType == other.mimeType
                    && uuid == other.uuid
                    && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            if (hashCode == 0) {
                var result = uuid.hashCode()
                result =
                    31 * result + (if (licenseServerUrl == null) 0 else licenseServerUrl.hashCode())
                result = 31 * result + mimeType.hashCode()
                result = 31 * result + data.contentHashCode()
                hashCode = result
            }
            return hashCode
        }

        // Parcelable implementation.
        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeLong(uuid.getMostSignificantBits())
            dest.writeLong(uuid.getLeastSignificantBits())
            dest.writeString(licenseServerUrl)
            dest.writeString(mimeType)
            dest.writeByteArray(data)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SchemeData?> =
                object : Parcelable.Creator<SchemeData?> {
                    override fun createFromParcel(`in`: Parcel): SchemeData {
                        return SchemeData(`in`)
                    }

                    override fun newArray(size: Int): Array<SchemeData?> {
                        return arrayOfNulls<SchemeData>(size)
                    }
                }
        }
    }

    companion object {
        
        fun createSessionCreationData(
            manifestData: DrmInitData?, mediaData: DrmInitData?
        ): DrmInitData? {
            val result = mutableListOf<SchemeData>()
            var schemeType: String? = null
            if (manifestData != null) {
                schemeType = manifestData.schemeType
                for (data in manifestData.schemeDatas!!) {
                    if (data.hasData()) {
                        result.add(data)
                    }
                }
            }

            if (mediaData != null) {
                if (schemeType == null) {
                    schemeType = mediaData.schemeType
                }
                val manifestDatasCount = result.size
                for (data in mediaData.schemeDatas!!) {
                    if (data.hasData() && !containsSchemeDataWithUuid(
                            result,
                            manifestDatasCount,
                            data.uuid
                        )
                    ) {
                        result.add(data)
                    }
                }
            }

            return if (result.isEmpty()) null else DrmInitData(schemeType, result)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<DrmInitData?> = object : Parcelable.Creator<DrmInitData?> {
            override fun createFromParcel(`in`: Parcel): DrmInitData {
                return DrmInitData(`in`)
            }

            override fun newArray(size: Int): Array<DrmInitData?> {
                return arrayOfNulls<DrmInitData>(size)
            }
        }

        private fun containsSchemeDataWithUuid(
            datas: List<SchemeData>, limit: Int, uuid: UUID?
        ): Boolean {
            for (i in 0..<limit) {
                if (datas[i].uuid == uuid) {
                    return true
                }
            }
            return false
        }
    }
}
