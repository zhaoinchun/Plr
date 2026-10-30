/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.text

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.MimeTypes

import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

class SubtitleTranscodingExtractorOutput(
    private val delegate: ExtractorOutput,
    private val subtitleParserFactory: SubtitleParser.Factory
) : ExtractorOutput {
    private val textTrackOutputs: SparseArray<SubtitleTranscodingTrackOutput?>

    private var hasTracksOtherThanTextAndMetadata = false

    init {
        textTrackOutputs = SparseArray<SubtitleTranscodingTrackOutput?>()
    }

    fun resetSubtitleParsers() {
        for (i in 0..<textTrackOutputs.size()) {
            textTrackOutputs.valueAt(i)!!.resetSubtitleParser()
        }
    }

    // ExtractorOutput implementation
    override fun track(id: Int, type: @C.TrackType Int): TrackOutput {
        if (type != C.TRACK_TYPE_TEXT && type != C.TRACK_TYPE_METADATA) {
            hasTracksOtherThanTextAndMetadata = true
        }
        if (type != C.TRACK_TYPE_TEXT) {
            return delegate.track(id, type)
        }
        val existingTrackOutput = textTrackOutputs.get(id)
        if (existingTrackOutput != null) {
            return existingTrackOutput
        }
        val trackOutput =
            SubtitleTranscodingTrackOutput(delegate.track(id, type), subtitleParserFactory)
        textTrackOutputs.put(id, trackOutput)
        return trackOutput
    }

    override fun endTracks() {
        delegate.endTracks()
        if (hasTracksOtherThanTextAndMetadata) {
            for (i in 0..<textTrackOutputs.size()) {
                textTrackOutputs.valueAt(i)!!.shouldSuppressParsingErrors(true)
            }
        }
    }

    override fun seekMap(seekMap: SeekMap) {
        delegate.seekMap(seekMap)
    }
}
