/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.extractor.mp4

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isAudio
import androidx.media3.common.MimeTypes.isImage
import androidx.media3.common.MimeTypes.isVideo

internal object MimeTypeResolver {
    
    fun getContainerMimeType(format: Format): String {
        val sampleMimeType = format.sampleMimeType

        if (isVideo(sampleMimeType)) {
            return MimeTypes.VIDEO_MP4
        }

        if (isAudio(sampleMimeType)) {
            return MimeTypes.AUDIO_MP4
        }

        if (isImage(sampleMimeType)) {
            if (sampleMimeType == MimeTypes.IMAGE_HEIC) {
                return MimeTypes.IMAGE_HEIF
            } else if (sampleMimeType == MimeTypes.IMAGE_AVIF) {
                return MimeTypes.IMAGE_AVIF
            }
        }

        return MimeTypes.APPLICATION_MP4
    }

    fun getContainerMimeType(trackSampleTables: MutableList<TrackSampleTable>): String {
        var hasAudio = false
        var imageMimeType: String? = null

        for (trackSampleTable in trackSampleTables) {
            val sampleMimeType = trackSampleTable.track!!.format!!.sampleMimeType

            if (isVideo(sampleMimeType)) {
                return MimeTypes.VIDEO_MP4
            }

            if (isAudio(sampleMimeType)) {
                hasAudio = true
            } else if (isImage(sampleMimeType)) {
                if (sampleMimeType == MimeTypes.IMAGE_HEIC) {
                    imageMimeType = MimeTypes.IMAGE_HEIF
                } else if (sampleMimeType == MimeTypes.IMAGE_AVIF) {
                    imageMimeType = MimeTypes.IMAGE_AVIF
                }
            }
        }

        if (hasAudio) {
            return MimeTypes.AUDIO_MP4
        } else if (imageMimeType != null) {
            return imageMimeType
        }

        return MimeTypes.APPLICATION_MP4
    }
}
