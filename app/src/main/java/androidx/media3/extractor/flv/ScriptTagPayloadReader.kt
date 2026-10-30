
package androidx.media3.extractor.flv

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DiscardingTrackOutput
import java.util.Date
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.LongArray
import kotlin.String
import kotlin.text.String

internal class ScriptTagPayloadReader : TagPayloadReader(DiscardingTrackOutput()) {
    var durationUs: Long
        private set
    var keyFrameTimesUs: LongArray
        private set
    var keyFrameTagPositions: LongArray
        private set

    init {
        durationUs = C.TIME_UNSET
        keyFrameTimesUs = LongArray(0)
        keyFrameTagPositions = LongArray(0)
    }

    override fun seek() {
        // Do nothing.
    }

    override fun parseHeader(data: ParsableByteArray): Boolean {
        return true
    }

    override fun parsePayload(data: ParsableByteArray, timeUs: Long): Boolean {
        val nameType: Int = readAmfType(data)
        if (nameType != AMF_TYPE_STRING) {
            // Ignore segments with unexpected name type.
            return false
        }
        val name: String = readAmfString(data)
        if (NAME_METADATA != name) {
            // We're only interested in metadata.
            return false
        }
        if (data.bytesLeft() == 0) {
            // The metadata script tag has no value.
            return false
        }
        val type: Int = readAmfType(data)
        if (type != AMF_TYPE_ECMA_ARRAY) {
            // We're not interested in this metadata.
            return false
        }
        val metadata: MutableMap<String?, Any?> = readAmfEcmaArray(data)
        // Set the duration to the value contained in the metadata, if present.
        val durationSecondsObj = metadata.get(KEY_DURATION)
        if (durationSecondsObj is Double) {
            val durationSeconds = durationSecondsObj
            if (durationSeconds > 0.0) {
                durationUs = (durationSeconds * C.MICROS_PER_SECOND).toLong()
            }
        }
        // Set the key frame times and positions to the value contained in the metadata, if present.
        val keyFramesObj = metadata.get(KEY_KEY_FRAMES)
        if (keyFramesObj is MutableMap<*, *>) {
            val keyFrames = keyFramesObj
            val positionsObj: Any? = keyFrames.get(KEY_FILE_POSITIONS)
            val timesSecondsObj: Any? = keyFrames.get(KEY_TIMES)
            if (positionsObj is MutableList<*> && timesSecondsObj is MutableList<*>) {
                val positions = positionsObj
                val timesSeconds = timesSecondsObj
                val keyFrameCount = timesSeconds.size
                keyFrameTimesUs = LongArray(keyFrameCount)
                keyFrameTagPositions = LongArray(keyFrameCount)
                for (i in 0..<keyFrameCount) {
                    val positionObj: Any? = positions[i]
                    val timeSecondsObj: Any? = timesSeconds[i]
                    if (timeSecondsObj is Double && positionObj is Double) {
                        keyFrameTimesUs[i] = (timeSecondsObj * C.MICROS_PER_SECOND).toLong()
                        keyFrameTagPositions[i] = positionObj.toLong()
                    } else {
                        keyFrameTimesUs = LongArray(0)
                        keyFrameTagPositions = LongArray(0)
                        break
                    }
                }
            }
        }
        return false
    }

    companion object {
        private const val NAME_METADATA = "onMetaData"
        private const val KEY_DURATION = "duration"
        private const val KEY_KEY_FRAMES = "keyframes"
        private const val KEY_FILE_POSITIONS = "filepositions"
        private const val KEY_TIMES = "times"

        // AMF object types
        private const val AMF_TYPE_NUMBER = 0
        private const val AMF_TYPE_BOOLEAN = 1
        private const val AMF_TYPE_STRING = 2
        private const val AMF_TYPE_OBJECT = 3
        private const val AMF_TYPE_ECMA_ARRAY = 8
        private const val AMF_TYPE_END_MARKER = 9
        private const val AMF_TYPE_STRICT_ARRAY = 10
        private const val AMF_TYPE_DATE = 11

        private fun readAmfType(data: ParsableByteArray): Int {
            return data.readUnsignedByte()
        }

        private fun readAmfBoolean(data: ParsableByteArray): Boolean {
            return data.readUnsignedByte() == 1
        }

        private fun readAmfDouble(data: ParsableByteArray): Double {
            return data.readLong().toDouble()
        }

        private fun readAmfString(data: ParsableByteArray): String {
            val size = data.readUnsignedShort()
            val position = data.getPosition()
            data.skipBytes(size)
            return String(data.data, position, size)
        }

        private fun readAmfStrictArray(data: ParsableByteArray): ArrayList<Any?> {
            val count = data.readUnsignedIntToInt()
            val list = ArrayList<Any?>(count)
            for (i in 0..<count) {
                val type: Int = readAmfType(data)
                val value: Any? = readAmfData(data, type)
                if (value != null) {
                    list.add(value)
                }
            }
            return list
        }

        private fun readAmfObject(data: ParsableByteArray): HashMap<String?, Any?> {
            val array = HashMap<String?, Any?>()
            while (true) {
                val key: String = readAmfString(data)
                val type: Int = readAmfType(data)
                if (type == AMF_TYPE_END_MARKER) {
                    break
                }
                val value: Any? = readAmfData(data, type)
                if (value != null) {
                    array.put(key, value)
                }
            }
            return array
        }

        private fun readAmfEcmaArray(data: ParsableByteArray): HashMap<String?, Any?> {
            val count = data.readUnsignedIntToInt()
            val array = HashMap<String?, Any?>(count)
            for (i in 0..<count) {
                val key: String = readAmfString(data)
                val type: Int = readAmfType(data)
                val value: Any? = readAmfData(data, type)
                if (value != null) {
                    array.put(key, value)
                }
            }
            return array
        }

        private fun readAmfDate(data: ParsableByteArray): Date {
            val date = Date(readAmfDouble(data).toLong())
            data.skipBytes(2) // Skip reserved bytes.
            return date
        }

        private fun readAmfData(data: ParsableByteArray, type: Int): Any? {
            when (type) {
                AMF_TYPE_NUMBER -> return readAmfDouble(data)
                AMF_TYPE_BOOLEAN -> return readAmfBoolean(data)
                AMF_TYPE_STRING -> return readAmfString(data)
                AMF_TYPE_OBJECT -> return readAmfObject(data)
                AMF_TYPE_ECMA_ARRAY -> return readAmfEcmaArray(data)
                AMF_TYPE_STRICT_ARRAY -> return readAmfStrictArray(data)
                AMF_TYPE_DATE -> return readAmfDate(data)
                else ->         // We don't log a warning because there are types that we knowingly don't support.
                    return null
            }
        }
    }
}
