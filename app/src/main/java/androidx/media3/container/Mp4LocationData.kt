/*
 * Copyright (C) 2023 The Android Open Source Project
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
package androidx.media3.container

import androidx.annotation.FloatRange
import androidx.media3.common.Metadata
import com.google.common.base.Preconditions
import com.google.common.primitives.Floats

class Mp4LocationData(
    @FloatRange(from = -90.0, to = 90.0) latitude: Float,
    @FloatRange(from = -180.0, to = 180.0) longitude: Float
) : Metadata.Entry {
    val latitude: Float
    val longitude: Float

    init {
        Preconditions.checkArgument(
            latitude >= -90.0f && latitude <= 90.0f && longitude >= -180.0f && longitude <= 180.0f,
            "Invalid latitude or longitude"
        )
        this.latitude = latitude
        this.longitude = longitude
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as Mp4LocationData
        return latitude == other.latitude && longitude == other.longitude
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + Floats.hashCode(latitude)
        result = 31 * result + Floats.hashCode(longitude)
        return result
    }

    override fun toString(): String {
        return "xyz: latitude=" + latitude + ", longitude=" + longitude
    }
}
