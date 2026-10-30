package androidx.media3.extractor.mkv

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.extractor.ExtractorInput
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import java.util.ArrayDeque
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Throws
import kotlin.text.String

internal class DefaultEbmlReader : EbmlReader {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [ELEMENT_STATE_READ_ID, ELEMENT_STATE_READ_CONTENT_SIZE, ELEMENT_STATE_READ_CONTENT])
    private annotation class ElementState

    private val scratch: ByteArray
    private val masterElementsStack: ArrayDeque<MasterElement>
    private val varintReader: VarintReader

    private var processor:EbmlProcessor? = null
    private var elementState: @ElementState Int = 0
    private var elementId = 0
    private var elementContentSize: Long = 0

    init {
        scratch = ByteArray(8)
        masterElementsStack = ArrayDeque<MasterElement>()
        varintReader = VarintReader()
    }

    override fun init(processor: EbmlProcessor) {
        this.processor = processor
    }

    override fun reset() {
        elementState = ELEMENT_STATE_READ_ID
        masterElementsStack.clear()
        varintReader.reset()
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput): Boolean {
        processor!!
        while (true) {
            val head = masterElementsStack.peek()
            if (head != null && input.position >= head.elementEndPosition) {
                processor!!.endMasterElement(masterElementsStack.pop().elementId)
                return true
            }

            if (elementState == ELEMENT_STATE_READ_ID) {
                var result = varintReader.readUnsignedVarint(input, true, false, MAX_ID_BYTES)
                if (result == C.RESULT_MAX_LENGTH_EXCEEDED.toLong()) {
                    result = maybeResyncToNextLevel1Element(input)
                }
                if (result == C.RESULT_END_OF_INPUT.toLong()) {
                    return false
                }
                // Element IDs are at most 4 bytes, so we can cast to integers.
                elementId = result.toInt()
                elementState = ELEMENT_STATE_READ_CONTENT_SIZE
            }

            if (elementState == ELEMENT_STATE_READ_CONTENT_SIZE) {
                elementContentSize =
                    varintReader.readUnsignedVarint(input, false, true, MAX_LENGTH_BYTES)
                elementState = ELEMENT_STATE_READ_CONTENT
            }

            val type: @EbmlProcessor.ElementType Int = processor!!.getElementType(elementId)
            when (type) {
                EbmlProcessor.ELEMENT_TYPE_MASTER -> {
                    val elementContentPosition = input.position
                    val elementEndPosition = elementContentPosition + elementContentSize
                    masterElementsStack.push(MasterElement(elementId, elementEndPosition))
                    processor!!.startMasterElement(
                        elementId,
                        elementContentPosition,
                        elementContentSize
                    )
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }

                EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT -> {
                    if (elementContentSize > MAX_INTEGER_ELEMENT_SIZE_BYTES) {
                        throw ParserException.createForMalformedContainer(
                            "Invalid integer size: " + elementContentSize,  null
                        )
                    }
                    processor!!.integerElement(
                        elementId,
                        readInteger(input, elementContentSize.toInt())
                    )
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }

                EbmlProcessor.ELEMENT_TYPE_FLOAT -> {
                    if (elementContentSize != VALID_FLOAT32_ELEMENT_SIZE_BYTES.toLong()
                        && elementContentSize != VALID_FLOAT64_ELEMENT_SIZE_BYTES.toLong()
                    ) {
                        throw ParserException.createForMalformedContainer(
                            "Invalid float size: " + elementContentSize,  null
                        )
                    }
                    processor!!.floatElement(
                        elementId,
                        readFloat(input, elementContentSize.toInt()).toDouble()
                    )
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }

                EbmlProcessor.ELEMENT_TYPE_STRING -> {
                    if (elementContentSize > Int.MAX_VALUE) {
                        throw ParserException.createForMalformedContainer(
                            "String element size: " + elementContentSize,  null
                        )
                    }
                    processor!!.stringElement(
                        elementId,
                        readString(input, elementContentSize.toInt())
                    )
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }

                EbmlProcessor.ELEMENT_TYPE_BINARY -> {
                    processor!!.binaryElement(elementId, elementContentSize.toInt(), input)
                    elementState = ELEMENT_STATE_READ_ID
                    return true
                }

                EbmlProcessor.ELEMENT_TYPE_UNKNOWN -> {
                    input.skipFully(elementContentSize.toInt())
                    elementState = ELEMENT_STATE_READ_ID
                }

                else -> throw ParserException.createForMalformedContainer(
                    "Invalid element type " + type,  null
                )
            }
        }
    }

    @RequiresNonNull("processor")
    @Throws(IOException::class)
    private fun maybeResyncToNextLevel1Element(input: ExtractorInput): Long {
        input.resetPeekPosition()
        while (true) {
            input.peekFully(scratch, 0, MAX_ID_BYTES)
            val varintLength: Int =
                VarintReader.parseUnsignedVarintLength(scratch[0].toInt())
            if (varintLength != C.LENGTH_UNSET && varintLength <= MAX_ID_BYTES) {
                val potentialId =
                    VarintReader.assembleVarint(scratch, varintLength, false).toInt()
                if (processor!!.isLevel1Element(potentialId)) {
                    input.skipFully(varintLength)
                    return potentialId.toLong()
                }
            }
            input.skipFully(1)
        }
    }

    @Throws(IOException::class)
    private fun readInteger(input: ExtractorInput, byteLength: Int): Long {
        input.readFully(scratch, 0, byteLength)
        var value: Long = 0
        for (i in 0..<byteLength) {
            value = (value shl 8) or (scratch[i].toInt() and 0xFF).toLong()
        }
        return value
    }

    @Throws(IOException::class)
    private fun readFloat(input: ExtractorInput, byteLength: Int): Double {
        val integerValue = readInteger(input, byteLength)
        val floatValue: Double
        if (byteLength == VALID_FLOAT32_ELEMENT_SIZE_BYTES) {
            floatValue = java.lang.Float.intBitsToFloat(integerValue.toInt()).toDouble()
        } else {
            floatValue = java.lang.Double.longBitsToDouble(integerValue)
        }
        return floatValue
    }

    private class MasterElement(val elementId: Int, val elementEndPosition: Long)
    companion object {
        private const val ELEMENT_STATE_READ_ID = 0
        private const val ELEMENT_STATE_READ_CONTENT_SIZE = 1
        private const val ELEMENT_STATE_READ_CONTENT = 2

        private const val MAX_ID_BYTES = 4
        private const val MAX_LENGTH_BYTES = 8

        private const val MAX_INTEGER_ELEMENT_SIZE_BYTES = 8
        private const val VALID_FLOAT32_ELEMENT_SIZE_BYTES = 4
        private const val VALID_FLOAT64_ELEMENT_SIZE_BYTES = 8

        @Throws(IOException::class)
        private fun readString(input: ExtractorInput, byteLength: Int): String {
            if (byteLength == 0) {
                return ""
            }
            val stringBytes = ByteArray(byteLength)
            input.readFully(stringBytes, 0, byteLength)
            // Remove zero padding.
            var trimmedLength = byteLength
            while (trimmedLength > 0 && stringBytes[trimmedLength - 1].toInt() == 0) {
                trimmedLength--
            }
            return String(stringBytes, 0, trimmedLength)
        }
    }
}
