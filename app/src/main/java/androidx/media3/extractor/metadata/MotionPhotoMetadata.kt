/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.extractor.metadata

import androidx.media3.common.Metadata

import com.google.common.primitives.Longs

class MotionPhotoMetadata
(
    
    val photoStartPosition: Long,
    
    val photoSize: Long,
    
    val photoPresentationTimestampUs: Long,
    
    val videoStartPosition: Long,
    
    val videoSize: Long
) : Metadata.Entry {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as MotionPhotoMetadata
        return photoStartPosition == other.photoStartPosition && photoSize == other.photoSize && photoPresentationTimestampUs == other.photoPresentationTimestampUs && videoStartPosition == other.videoStartPosition && videoSize == other.videoSize
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + Longs.hashCode(photoStartPosition)
        result = 31 * result + Longs.hashCode(photoSize)
        result = 31 * result + Longs.hashCode(photoPresentationTimestampUs)
        result = 31 * result + Longs.hashCode(videoStartPosition)
        result = 31 * result + Longs.hashCode(videoSize)
        return result
    }

    override fun toString(): String {
        return ("Motion photo metadata: photoStartPosition="
                + photoStartPosition
                + ", photoSize="
                + photoSize
                + ", photoPresentationTimestampUs="
                + photoPresentationTimestampUs
                + ", videoStartPosition="
                + videoStartPosition
                + ", videoSize="
                + videoSize)
    }
}
