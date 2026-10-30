package androidx.media3.exoplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w

import androidx.media3.common.util.Util.createReadOnlyByteBuffer
import androidx.media3.common.util.Util.formatInvariant
import androidx.media3.common.util.Util.getPcmFrameSize
import androidx.media3.common.util.WavUtil
import androidx.media3.common.util.WavUtil.getTypeForPcmEncoding
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class TeeAudioProcessor(private val audioBufferSink: AudioBufferSink) : BaseAudioProcessor() {
    interface AudioBufferSink {
        fun flush(sampleRateHz: Int, channelCount: Int, encoding: @C.PcmEncoding Int)
        fun handleBuffer(buffer: ByteBuffer)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // This processor is always active (if passed to the sink) and outputs its input.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) {
            return
        }
        audioBufferSink.handleBuffer(createReadOnlyByteBuffer(inputBuffer))
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        flushSinkIfActive()
    }

    override fun onQueueEndOfStream() {
        flushSinkIfActive()
    }

    override fun onReset() {
        flushSinkIfActive()
    }

    private fun flushSinkIfActive() {
        if (isActive) {
            audioBufferSink.flush(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                inputAudioFormat.encoding
            )
        }
    }

    class WavFileAudioBufferSink(private val outputFileNamePrefix: String?) : AudioBufferSink {
        private val scratchBuffer = ByteArray(1024)
        private val scratchByteBuffer =
            ByteBuffer.wrap(scratchBuffer).order(ByteOrder.LITTLE_ENDIAN)
        private var sampleRateHz = 0
        private var channelCount = 0
        private var encoding: @C.PcmEncoding Int = 0
        private var randomAccessFile: RandomAccessFile? = null
        private var counter = 0
        private var bytesWritten = 0

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: @C.PcmEncoding Int) {
            try {
                reset()
            } catch (e: IOException) {
                e(TAG, "Error resetting", e)
            }
            this.sampleRateHz = sampleRateHz
            this.channelCount = channelCount
            this.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            try {
                maybePrepareFile()
                writeBuffer(buffer)
            } catch (e: IOException) {
                e(TAG, "Error writing data", e)
            }
        }

        @Throws(IOException::class)
        private fun maybePrepareFile() {
            if (randomAccessFile != null) {
                return
            }
            val randomAccessFile = RandomAccessFile(this.nextOutputFileName, "rw")
            writeFileHeader(randomAccessFile)
            this.randomAccessFile = randomAccessFile
            bytesWritten = HEADER_LENGTH
        }

        @Throws(IOException::class)
        private fun writeFileHeader(randomAccessFile: RandomAccessFile) {
            // Write the start of the header as big endian data.
            randomAccessFile.writeInt(WavUtil.RIFF_FOURCC)
            randomAccessFile.writeInt(-1)
            randomAccessFile.writeInt(WavUtil.WAVE_FOURCC)
            randomAccessFile.writeInt(WavUtil.FMT_FOURCC)

            // Write the rest of the header as little endian data.
            scratchByteBuffer.clear()
            scratchByteBuffer.putInt(16)
            scratchByteBuffer.putShort(getTypeForPcmEncoding(encoding).toShort())
            scratchByteBuffer.putShort(channelCount.toShort())
            scratchByteBuffer.putInt(sampleRateHz)
            val bytesPerSample = getPcmFrameSize(encoding, channelCount)
            scratchByteBuffer.putInt(bytesPerSample * sampleRateHz)
            scratchByteBuffer.putShort(bytesPerSample.toShort())
            scratchByteBuffer.putShort((8 * bytesPerSample / channelCount).toShort())
            randomAccessFile.write(scratchBuffer, 0, scratchByteBuffer.position())

            // Write the start of the data chunk as big endian data.
            randomAccessFile.writeInt(WavUtil.DATA_FOURCC)
            randomAccessFile.writeInt(-1)
        }

        @Throws(IOException::class)
        private fun writeBuffer(buffer: ByteBuffer) {
            val randomAccessFile = this.randomAccessFile!!
            while (buffer.hasRemaining()) {
                val bytesToWrite = min(buffer.remaining(), scratchBuffer.size)
                buffer.get(scratchBuffer, 0, bytesToWrite)
                randomAccessFile.write(scratchBuffer, 0, bytesToWrite)
                bytesWritten += bytesToWrite
            }
        }

        @Throws(IOException::class)
        private fun reset() {
            val randomAccessFile = randomAccessFile ?: return

            try {
                scratchByteBuffer.clear()
                scratchByteBuffer.putInt(bytesWritten - 8)
                randomAccessFile.seek(FILE_SIZE_MINUS_8_OFFSET.toLong())
                randomAccessFile.write(scratchBuffer, 0, 4)

                scratchByteBuffer.clear()
                scratchByteBuffer.putInt(bytesWritten - 44)
                randomAccessFile.seek(FILE_SIZE_MINUS_44_OFFSET.toLong())
                randomAccessFile.write(scratchBuffer, 0, 4)
            } catch (e: IOException) {
                // The file may still be playable, so just log a warning.
                w(TAG, "Error updating file size", e)
            }

            try {
                randomAccessFile.close()
            } finally {
                this.randomAccessFile = null
            }
        }

        private val nextOutputFileName: String
            get() = formatInvariant(
                "%s-%04d.wav",
                outputFileNamePrefix,
                counter++
            )

        companion object {
            private const val TAG = "WaveFileAudioBufferSink"
            private const val FILE_SIZE_MINUS_8_OFFSET = 4
            private const val FILE_SIZE_MINUS_44_OFFSET = 40
            private const val HEADER_LENGTH = 44
        }
    }
}
