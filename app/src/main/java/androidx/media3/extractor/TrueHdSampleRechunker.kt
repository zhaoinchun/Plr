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
package androidx.media3.extractor

import androidx.media3.common.C.BufferFlags

import androidx.media3.extractor.TrackOutput.CryptoData
import com.google.common.base.Preconditions
import java.io.IOException

class TrueHdSampleRechunker {
    private val syncframePrefix: ByteArray

    private var foundSyncframe = false
    private var chunkSampleCount = 0
    private var chunkTimeUs: Long = 0
    private var chunkFlags: @BufferFlags Int = 0
    private var chunkSize = 0
    private var chunkOffset = 0

    init {
        syncframePrefix = ByteArray(Ac3Util.TRUEHD_SYNCFRAME_PREFIX_LENGTH)
    }

    fun reset() {
        foundSyncframe = false
        chunkSampleCount = 0
    }

    @Throws(IOException::class)
    fun startSample(input: ExtractorInput) {
        if (foundSyncframe) {
            return
        }
        input.peekFully(syncframePrefix, 0, Ac3Util.TRUEHD_SYNCFRAME_PREFIX_LENGTH)
        input.resetPeekPosition()
        if (Ac3Util.parseTrueHdSyncframeAudioSampleCount(syncframePrefix) == 0) {
            return
        }
        foundSyncframe = true
    }

    fun sampleMetadata(
        trackOutput: TrackOutput,
        timeUs: Long,
        flags: @BufferFlags Int,
        size: Int,
        offset: Int,
        cryptoData: CryptoData?
    ) {
        Preconditions.checkState(
            chunkOffset <= size + offset,
            "TrueHD chunk samples must be contiguous in the sample queue."
        )
        if (!foundSyncframe) {
            return
        }
        if (chunkSampleCount++ == 0) {
            // This is the first sample in the chunk.
            chunkTimeUs = timeUs
            chunkFlags = flags
            chunkSize = 0
        }
        chunkSize += size
        chunkOffset = offset // The offset is to the end of the sample.
        if (chunkSampleCount >= Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT) {
            outputPendingSampleMetadata(trackOutput, cryptoData)
        }
    }

    fun outputPendingSampleMetadata(
        trackOutput: TrackOutput, cryptoData: CryptoData?
    ) {
        if (chunkSampleCount > 0) {
            trackOutput.sampleMetadata(chunkTimeUs, chunkFlags, chunkSize, chunkOffset, cryptoData)
            chunkSampleCount = 0
        }
    }
}
