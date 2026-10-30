/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.text

import android.os.Bundle
import android.os.Parcel
import androidx.media3.common.text.Cue
import androidx.media3.common.util.BundleCollectionUtil

import com.google.common.base.Preconditions

class CueDecoder {
    
    fun decode(startTimeUs: Long, bytes: ByteArray, offset: Int, length: Int): CuesWithTiming {
        val parcel = Parcel.obtain()
        parcel.unmarshall(bytes, offset, length)
        parcel.setDataPosition(0)
        val bundle = parcel.readBundle(Bundle::class.java.getClassLoader())
        parcel.recycle()
        val bundledCues = bundle!!.getParcelableArrayList<Bundle>(
            BUNDLE_FIELD_CUES
        )!!
        return CuesWithTiming(
            BundleCollectionUtil.fromBundleList<Cue>(Cue::fromBundle, bundledCues),
            startTimeUs,
            bundle.getLong(BUNDLE_FIELD_DURATION_US)
        )
    }

    companion object {
         
        const val BUNDLE_FIELD_CUES: String = "c"

         
        const val BUNDLE_FIELD_DURATION_US: String = "d"
    }
}
