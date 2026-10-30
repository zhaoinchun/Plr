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

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder

import java.io.IOException

@Deprecated(
    """Use {@link SubtitleTranscodingExtractorOutput} directly from within an existing
      {@link Extractor} implementation instead."""
)
class SubtitleTranscodingExtractor(
    override val underlyingImplementation: Extractor,
    private val subtitleParserFactory: SubtitleParser.Factory
) : Extractor {
    private var transcodingExtractorOutput:SubtitleTranscodingExtractorOutput? =
        null

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return underlyingImplementation.sniff(input)
    }

    override fun init(output: ExtractorOutput) {
        transcodingExtractorOutput =
            SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
        underlyingImplementation.init(transcodingExtractorOutput!!)
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        return underlyingImplementation.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (transcodingExtractorOutput != null) {
            transcodingExtractorOutput!!.resetSubtitleParsers()
        }
        underlyingImplementation.seek(position, timeUs)
    }

    override fun release() {
        underlyingImplementation.release()
    }
}
