package androidx.media3.common

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.media3.common.util.Util.intToStringMaxRadix

class StreamKey(val periodIndex: Int, val groupIndex: Int, val streamIndex: Int) :
    Comparable<StreamKey>, Parcelable {

    constructor(`in`: Parcel) :
            this(`in`.readInt(), `in`.readInt(), `in`.readInt())

    override fun compareTo(o: StreamKey): Int {
        var result = periodIndex - o.periodIndex
        if (result == 0) {
            result = groupIndex - o.groupIndex
            if (result == 0) {
                result = streamIndex - o.streamIndex
            }
        }
        return result
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(periodIndex)
        dest.writeInt(groupIndex)
        dest.writeInt(streamIndex)
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        if (periodIndex != 0) {
            bundle.putInt(FIELD_PERIOD_INDEX, periodIndex)
        }
        if (groupIndex != 0) {
            bundle.putInt(FIELD_GROUP_INDEX, groupIndex)
        }
        if (streamIndex != 0) {
            bundle.putInt(FIELD_STREAM_INDEX, streamIndex)
        }
        return bundle
    }

    companion object {
        val CREATOR: Parcelable.Creator<StreamKey> = object : Parcelable.Creator<StreamKey> {
            override fun createFromParcel(`in`: Parcel): StreamKey {
                return StreamKey(`in`)
            }

            override fun newArray(size: Int): Array<StreamKey?> {
                return arrayOfNulls(size)
            }
        }

        private val FIELD_PERIOD_INDEX = intToStringMaxRadix(0)
        private val FIELD_GROUP_INDEX = intToStringMaxRadix(1)
        private val FIELD_STREAM_INDEX = intToStringMaxRadix(2)

        fun fromBundle(bundle: Bundle): StreamKey {
            return StreamKey(
                bundle.getInt(FIELD_PERIOD_INDEX, 0),
                bundle.getInt(FIELD_GROUP_INDEX, 0),
                bundle.getInt(FIELD_STREAM_INDEX, 0)
            )
        }
    }
}
