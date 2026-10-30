package androidx.media3.common.util

import android.app.ActivityManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.Util.fromUtf8Bytes
import androidx.media3.common.util.Util.isLinebreak
import com.google.common.base.Ascii
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.primitives.Chars
import com.google.common.primitives.Ints
import com.google.common.primitives.UnsignedBytes
import com.google.common.primitives.UnsignedInts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.Boolean
import kotlin.Byte
import kotlin.ByteArray
import kotlin.Char
import kotlin.CharArray
import kotlin.Deprecated
import kotlin.IllegalArgumentException
import kotlin.IndexOutOfBoundsException
import kotlin.Int
import kotlin.Long
import kotlin.NumberFormatException
import kotlin.RuntimeException
import kotlin.Short
import kotlin.String
import kotlin.charArrayOf
import kotlin.check
import kotlin.code
import kotlin.math.max

class ParsableByteArray {
    private object IsRunningInTestHolder {
        val IS_RUNNING_IN_TEST: Boolean = isRunningInTest
    }

    var data: ByteArray
        private set
    private var position = 0

    // TODO(internal b/147657250): Enforce this limit on all read methods.
    private var limit = 0

    constructor() {
        getPosition()
        data = Util.EMPTY_BYTE_ARRAY
    }

    constructor(limit: Int) {
        this.data = ByteArray(limit)
        this.limit = limit
    }

    constructor(data: ByteArray) {
        this.data = data
        limit = data.size
    }

    constructor(data: ByteArray, limit: Int) {
        this.data = data
        this.limit = limit
    }

    fun reset(limit: Int) {
        reset(if (capacity() < limit) ByteArray(limit) else data, limit)
    }

    fun reset(data: ByteArray, limit: Int = data.size) {
        this.data = data
        this.limit = limit
        position = 0
    }

    fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity > capacity()) {
            data = data.copyOf(requiredCapacity)
        }
    }

    fun bytesLeft(): Int {
        return max(limit - position, 0)
    }

    fun limit(): Int {
        return limit
    }

    fun setLimit(limit: Int) {
        Preconditions.checkArgument(limit >= 0 && limit <= data.size)
        this.limit = limit
    }

    fun getPosition(): Int {
        return position
    }

    fun setPosition(position: Int) {
        // It is fine for position to be at the end of the array.
        Preconditions.checkArgument(position >= 0 && position <= limit)
        this.position = position
    }

    fun capacity(): Int {
        return data.size
    }

    fun skipBytes(bytes: Int) {
        setPosition(position + bytes)
    }

    fun readBytes(bitArray: ParsableBitArray, length: Int) {
        readBytes(bitArray.data, 0, length)
        bitArray.position=(0)
    }

    fun readBytes(buffer: ByteArray, offset: Int, length: Int) {
        maybeAssertAtLeastBytesLeftForLegacyMethod(length)
        System.arraycopy(data, position, buffer, offset, length)
        position += length
    }

    fun readBytes(buffer: ByteBuffer, length: Int) {
        maybeAssertAtLeastBytesLeftForLegacyMethod(length)
        buffer.put(data, position, length)
        position += length
    }

    fun peekUnsignedByte(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(1)
        return (data[position].toInt() and 0xFF)
    }

    fun peekChar(): Char {
        return peekChar(ByteOrder.BIG_ENDIAN,  0)
    }

    @Deprecated("")
    fun peekChar(charset: Charset): Char {
        Preconditions.checkArgument(
            SUPPORTED_CHARSETS_FOR_READLINE.contains(charset), "Unsupported charset: %s", charset
        )
        if (bytesLeft() == 0) {
            return 0.toChar()
        }
        if (charset == StandardCharsets.US_ASCII) {
            return peekUnsignedByte().toChar()
        } else if (charset == StandardCharsets.UTF_8) {
            return if ((data[position].toInt() and 0x80) == 0) peekUnsignedByte().toChar() else 0.toChar()
        } else {
            // UTF-16
            if (bytesLeft() < 2) {
                return 0.toChar()
            }
            val byteOrder =
                if (charset == StandardCharsets.UTF_16LE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            return peekChar(byteOrder,  0)
        }
    }

    private fun peekChar(byteOrder: ByteOrder?, offset: Int): Char {
        maybeAssertAtLeastBytesLeftForLegacyMethod(2)
        return if (byteOrder == ByteOrder.BIG_ENDIAN)
            Chars.fromBytes(data[position + offset], data[position + offset + 1])
        else
            Chars.fromBytes(data[position + offset + 1], data[position + offset])
    }

    fun peekCodePoint(charset: Charset): Int {
        val codePointAndSize = peekCodePointAndSize(charset)
        return if (codePointAndSize != 0) Ints.checkedCast((codePointAndSize ushr 8).toLong()) else INVALID_CODE_POINT
    }

    fun peekUnsignedInt24(): Int {
        if (bytesLeft() < 3) {
            throw java.lang.IndexOutOfBoundsException("position=" + position + ", limit=" + limit)
        }
        val result = readUnsignedInt24()
        position -= 3
        return result
    }

    fun peekInt(): Int {
        if (bytesLeft() < 4) {
            throw java.lang.IndexOutOfBoundsException("position=" + position + ", limit=" + limit)
        }
        val result = readInt()
        position -= 4
        return result
    }

    fun readUnsignedByte(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(1)
        return (data[position++].toInt() and 0xFF)
    }

    fun readUnsignedShort(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(2)
        return (data[position++].toInt() and 0xFF) shl 8 or (data[position++].toInt() and 0xFF)
    }

    fun readLittleEndianUnsignedShort(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(2)
        return (data[position++].toInt() and 0xFF) or ((data[position++].toInt() and 0xFF) shl 8)
    }

    fun readShort(): Short {
        maybeAssertAtLeastBytesLeftForLegacyMethod(2)
        return ((data[position++].toInt() and 0xFF) shl 8 or (data[position++].toInt() and 0xFF)).toShort()
    }

    fun readLittleEndianShort(): Short {
        maybeAssertAtLeastBytesLeftForLegacyMethod(2)
        return ((data[position++].toInt() and 0xFF) or ((data[position++].toInt() and 0xFF) shl 8)).toShort()
    }

    fun readUnsignedInt24(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(3)
        return ((data[position++].toInt() and 0xFF) shl 16 or ((data[position++].toInt() and 0xFF) shl 8
                ) or (data[position++].toInt() and 0xFF))
    }

    fun readInt24(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(3)
        return (((data[position++].toInt() and 0xFF) shl 24) shr 8 or ((data[position++].toInt() and 0xFF) shl 8
                ) or (data[position++].toInt() and 0xFF))
    }

    fun readLittleEndianInt24(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(3)
        return ((data[position++].toInt() and 0xFF)
                or ((data[position++].toInt() and 0xFF) shl 8
                ) or ((data[position++].toInt() and 0xFF) shl 16))
    }

    fun readLittleEndianUnsignedInt24(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(3)
        return ((data[position++].toInt() and 0xFF)
                or ((data[position++].toInt() and 0xFF) shl 8
                ) or ((data[position++].toInt() and 0xFF) shl 16))
    }

    fun readUnsignedInt(): Long {
        maybeAssertAtLeastBytesLeftForLegacyMethod(4)
        return ((data[position++].toLong() and 0xFFL) shl 24 or ((data[position++].toLong() and 0xFFL) shl 16
                ) or ((data[position++].toLong() and 0xFFL) shl 8
                ) or (data[position++].toLong() and 0xFFL))
    }

    fun readLittleEndianUnsignedInt(): Long {
        maybeAssertAtLeastBytesLeftForLegacyMethod(4)
        return ((data[position++].toLong() and 0xFFL)
                or ((data[position++].toLong() and 0xFFL) shl 8
                ) or ((data[position++].toLong() and 0xFFL) shl 16
                ) or ((data[position++].toLong() and 0xFFL) shl 24))
    }

    fun readInt(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(4)
        return ((data[position++].toInt() and 0xFF) shl 24 or ((data[position++].toInt() and 0xFF) shl 16
                ) or ((data[position++].toInt() and 0xFF) shl 8
                ) or (data[position++].toInt() and 0xFF))
    }

    fun readLittleEndianInt(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(4)
        return ((data[position++].toInt() and 0xFF)
                or ((data[position++].toInt() and 0xFF) shl 8
                ) or ((data[position++].toInt() and 0xFF) shl 16
                ) or ((data[position++].toInt() and 0xFF) shl 24))
    }

    fun readLong(): Long {
        maybeAssertAtLeastBytesLeftForLegacyMethod(8)
        return ((data[position++].toLong() and 0xFFL) shl 56 or ((data[position++].toLong() and 0xFFL) shl 48
                ) or ((data[position++].toLong() and 0xFFL) shl 40
                ) or ((data[position++].toLong() and 0xFFL) shl 32
                ) or ((data[position++].toLong() and 0xFFL) shl 24
                ) or ((data[position++].toLong() and 0xFFL) shl 16
                ) or ((data[position++].toLong() and 0xFFL) shl 8
                ) or (data[position++].toLong() and 0xFFL))
    }

    fun readLittleEndianLong(): Long {
        maybeAssertAtLeastBytesLeftForLegacyMethod(8)
        return ((data[position++].toLong() and 0xFFL)
                or ((data[position++].toLong() and 0xFFL) shl 8
                ) or ((data[position++].toLong() and 0xFFL) shl 16
                ) or ((data[position++].toLong() and 0xFFL) shl 24
                ) or ((data[position++].toLong() and 0xFFL) shl 32
                ) or ((data[position++].toLong() and 0xFFL) shl 40
                ) or ((data[position++].toLong() and 0xFFL) shl 48
                ) or ((data[position++].toLong() and 0xFFL) shl 56))
    }

    fun readUnsignedFixedPoint1616(): Int {
        maybeAssertAtLeastBytesLeftForLegacyMethod(4)
        val result =
            (data[position++].toInt() and 0xFF) shl 8 or (data[position++].toInt() and 0xFF)
        position += 2 // Skip the non-integer portion.
        return result
    }

    fun readSynchSafeInt(): Int {
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        val b4 = readUnsignedByte()
        return (b1 shl 21) or (b2 shl 14) or (b3 shl 7) or b4
    }

    fun readUnsignedIntToInt(): Int {
        val result = readInt()
        check(result >= 0) { "Top bit not zero: " + result }
        return result
    }

    fun readLittleEndianUnsignedIntToInt(): Int {
        val result = readLittleEndianInt()
        check(result >= 0) { "Top bit not zero: " + result }
        return result
    }

    fun readUnsignedLongToLong(): Long {
        val result = readLong()
        check(result >= 0) { "Top bit not zero: " + result }
        return result
    }

    fun readFloat(): Float {
        return readInt().toFloat()
    }

    fun readDouble(): Double {
        return readLong().toDouble()
    }

    fun readString(length: Int, charset: Charset = StandardCharsets.UTF_8): String {
        maybeAssertAtLeastBytesLeftForLegacyMethod(
            length,
            Function { s: String? -> StringIndexOutOfBoundsException(s) })
        val result = String(data, position, length, charset)
        position += length
        return result
    }

    fun readNullTerminatedString(length: Int): String {
        maybeAssertAtLeastBytesLeftForLegacyMethod(
            length,
            Function { s: String? -> StringIndexOutOfBoundsException(s) })
        if (length == 0) {
            return ""
        }
        var stringLength = length
        val lastIndex = position + length - 1
        if (lastIndex < limit && data[lastIndex].toInt() == 0) {
            stringLength--
        }
        val result = fromUtf8Bytes(data, position, stringLength)
        position += length
        return result
    }

    fun readNullTerminatedString(): String? {
        return readDelimiterTerminatedString('\u0000')
    }

    fun readDelimiterTerminatedString(delimiter: Char): String? {
        if (bytesLeft() == 0) {
            return null
        }
        var stringLimit = position
        while (stringLimit < limit && data[stringLimit] != delimiter.code.toByte()) {
            stringLimit++
        }
        val string = fromUtf8Bytes(data, position, stringLimit - position)
        position = stringLimit
        if (position < limit) {
            position++
        }
        return string
    }

    fun readLine(charset: Charset = StandardCharsets.UTF_8): String? {
        Preconditions.checkArgument(
            SUPPORTED_CHARSETS_FOR_READLINE.contains(charset), "Unsupported charset: %s", charset
        )
        if (bytesLeft() == 0) {
            return null
        }
        if (charset != StandardCharsets.US_ASCII) {
            val unused = readUtfCharsetFromBom() // Skip BOM if present
        }
        val lineLimit = findNextLineTerminator(charset)
        val line = readString(lineLimit - position, charset)
        if (position == limit) {
            return line
        }
        skipLineTerminator(charset)
        return line
    }

    fun readUtf8EncodedLong(): Long {
        maybeAssertAtLeastBytesLeftForLegacyMethod(1)
        var length = 0
        var value = data[position].toLong()
        // find the high most 0 bit
        for (j in 7 downTo 0) {
            if ((value and (1 shl j).toLong()) == 0L) {
                if (j < 6) {
                    value = value and ((1 shl j) - 1).toLong()
                    length = 7 - j
                } else if (j == 7) {
                    length = 1
                }
                break
            }
        }
        if (length == 0) {
            throw NumberFormatException("Invalid UTF-8 sequence first byte: " + value)
        }
        maybeAssertAtLeastBytesLeftForLegacyMethod(length)
        for (i in 1..<length) {
            val x = data[position + i].toInt()
            if ((x and 0xC0) != 0x80) { // if the high most 0 bit not 7th
                throw NumberFormatException("Invalid UTF-8 sequence continuation byte: " + value)
            }
            value = (value shl 6) or (x and 0x3F).toLong()
        }
        position += length
        return value
    }

    fun readUnsignedLeb128ToLong(): Long {
        var value: Long = 0
        // At most, 63 bits of unsigned data can be stored in a long, which corresponds to 63/7=9 bytes
        // in LEB128.
        for (i in 0..8) {
            check(this.position != limit) { "Attempting to read a byte over the limit." }
            val currentByte = this.readUnsignedByte().toLong()
            value = value or ((currentByte and 0x7FL) shl (i * 7))
            if ((currentByte and 0x80L) == 0L) {
                break
            }
        }
        return value
    }

    fun readUnsignedLeb128ToInt(): Int {
        return Ints.checkedCast(readUnsignedLeb128ToLong())
    }

    fun skipLeb128() {
        while ((readUnsignedByte() and 0x80) != 0) {
        }
    }

    fun readUtfCharsetFromBom(): Charset? {
        if (bytesLeft() >= 3 && data[position] == 0xEF.toByte() && data[position + 1] == 0xBB.toByte() && data[position + 2] == 0xBF.toByte()) {
            position += 3
            return StandardCharsets.UTF_8
        } else if (bytesLeft() >= 2) {
            if (data[position] == 0xFE.toByte() && data[position + 1] == 0xFF.toByte()) {
                position += 2
                return StandardCharsets.UTF_16BE
            } else if (data[position] == 0xFF.toByte() && data[position + 1] == 0xFE.toByte()) {
                position += 2
                return StandardCharsets.UTF_16LE
            }
        }
        return null
    }

    private fun findNextLineTerminator(charset: Charset): Int {
        val stride: Int
        if (charset == StandardCharsets.UTF_8 || charset == StandardCharsets.US_ASCII) {
            stride = 1
        } else if (charset == StandardCharsets.UTF_16
            || charset == StandardCharsets.UTF_16LE
            || charset == StandardCharsets.UTF_16BE
        ) {
            stride = 2
        } else {
            throw IllegalArgumentException("Unsupported charset: " + charset)
        }
        var i = position
        while (i < limit - (stride - 1)) {
            if ((charset == StandardCharsets.UTF_8 || charset == StandardCharsets.US_ASCII)
                && isLinebreak(data[i].toInt())
            ) {
                return i
            } else if ((charset == StandardCharsets.UTF_16
                        || charset == StandardCharsets.UTF_16BE)
                && data[i].toInt() == 0x00 && isLinebreak(data[i + 1].toInt())
            ) {
                return i
            } else if (charset == StandardCharsets.UTF_16LE
                && data[i + 1].toInt() == 0x00 && isLinebreak(data[i].toInt())
            ) {
                return i
            }
            i += stride
        }
        return limit
    }

    private fun skipLineTerminator(charset: Charset) {
        if (readCharacterIfInList(charset, CR_AND_LF) == '\r') {
            val unused = readCharacterIfInList(charset, LF)
        }
    }

    private fun readCharacterIfInList(charset: Charset, chars: CharArray): Char {
        if (bytesLeft() < getSmallestCodeUnitSize(charset)) {
            return 0.toChar()
        }
        val codePointAndSize = peekCodePointAndSize(charset)
        if (codePointAndSize == 0) {
            return 0.toChar()
        }

        val codePoint = UnsignedInts.checkedCast((codePointAndSize ushr 8).toLong())
        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 0.toChar()
        }
        val c = Chars.checkedCast(codePoint.toLong())
        if (Chars.contains(chars, c)) {
            position += Ints.checkedCast((codePointAndSize and 0xFF).toLong())
            return c
        } else {
            return 0.toChar()
        }
    }

    private fun peekCodePointAndSize(charset: Charset): Int {
        Preconditions.checkArgument(
            SUPPORTED_CHARSETS_FOR_READLINE.contains(charset), "Unsupported charset: %s", charset
        )
        if (bytesLeft() < getSmallestCodeUnitSize(charset)) {
            throw java.lang.IndexOutOfBoundsException("position=" + position + ", limit=" + limit)
        }
        val codePoint: Int
        val codePointSize: Byte
        if (charset == StandardCharsets.US_ASCII) {
            if ((data[position].toInt() and 0x80) != 0) {
                return 0
            }
            codePoint = UnsignedBytes.toInt(data[position])
            codePointSize = 1
        } else if (charset == StandardCharsets.UTF_8) {
            codePointSize = peekUtf8CodeUnitSize()
            when (codePointSize.toInt()) {
                1 -> codePoint = UnsignedBytes.toInt(data[position])
                2 -> codePoint =
                    decodeUtf8CodeUnit(0, 0, data[position].toInt(), data[position + 1].toInt())

                3 -> {
                    val firstByteWithoutStartCode = data[position].toInt() and 0xF
                    codePoint =
                        decodeUtf8CodeUnit(
                            0,
                            firstByteWithoutStartCode,
                            data[position + 1].toInt(),
                            data[position + 2].toInt()
                        )
                }

                4 -> codePoint =
                    decodeUtf8CodeUnit(
                        data[position].toInt(),
                        data[position + 1].toInt(),
                        data[position + 2].toInt(),
                        data[position + 3].toInt()
                    )

                0 -> return 0
                else -> return 0
            }
        } else {
            // UTF-16
            val byteOrder =
                if (charset == StandardCharsets.UTF_16LE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val c = peekChar(byteOrder,  0)
            if (Character.isHighSurrogate(c) && bytesLeft() >= 4) {
                val lowSurrogate = peekChar(byteOrder,  2)
                codePoint = Character.toCodePoint(c, lowSurrogate)
                codePointSize = 4
            } else {
                // This is either a BMP code point, an unpaired surrogate, or position is in the middle of
                // a matching surrogate pair.
                codePoint = c.code
                codePointSize = 2
            }
        }
        return (codePoint shl 8) or codePointSize.toInt()
    }

    private fun peekUtf8CodeUnitSize(): Byte {
        if ((data[position].toInt() and 0x80) == 0) {
            return 1
        } else if ((data[position].toInt() and 0xE0) == 0xC0 && bytesLeft() >= 2 && isUtf8ContinuationByte(
                data[position + 1]
            )
        ) {
            return 2
        } else if ((data[position].toInt() and 0xF0) == 0xE0 && bytesLeft() >= 3 && isUtf8ContinuationByte(
                data[position + 1]
            )
            && isUtf8ContinuationByte(data[position + 2])
        ) {
            return 3
        } else if ((data[position].toInt() and 0xF8) == 0xF0 && bytesLeft() >= 4 && isUtf8ContinuationByte(
                data[position + 1]
            )
            && isUtf8ContinuationByte(data[position + 2])
            && isUtf8ContinuationByte(data[position + 3])
        ) {
            return 4
        } else {
            // We found a pattern that doesn't seem to be valid UTF-8.
            return 0
        }
    }

    private fun maybeAssertAtLeastBytesLeftForLegacyMethod(
        bytesNeeded: Int,
        exceptionFactory: Function<String, out RuntimeException> = Function { s: String ->
            IndexOutOfBoundsException(s)
        }
    ) {
        val override: Boolean? = shouldEnforceLimitOnLegacyMethods.get()
        val enforceLimit =
            if (override != null) override else IsRunningInTestHolder.IS_RUNNING_IN_TEST
        if (enforceLimit) {
            if (bytesLeft() < bytesNeeded) {
                throw exceptionFactory.apply("bytesNeeded= " + bytesNeeded + ", bytesLeft=" + bytesLeft())
            }
        }
    }

    companion object {
        const val INVALID_CODE_POINT: Int = 0x110000

        private val CR_AND_LF = charArrayOf('\r', '\n')
        private val LF = charArrayOf('\n')
        private val SUPPORTED_CHARSETS_FOR_READLINE=setOf<Charset>(
                StandardCharsets.US_ASCII,
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_16,
                StandardCharsets.UTF_16BE,
                StandardCharsets.UTF_16LE
            )

        private val shouldEnforceLimitOnLegacyMethods = AtomicReference<Boolean?>()

        @VisibleForTesting
        fun setShouldEnforceLimitOnLegacyMethods(enforceLimit: Boolean?) {
            shouldEnforceLimitOnLegacyMethods.set(enforceLimit)
        }

        private fun getSmallestCodeUnitSize(charset: Charset): Int {
            Preconditions.checkArgument(
                SUPPORTED_CHARSETS_FOR_READLINE.contains(charset),
                "Unsupported charset: %s",
                charset
            )
            return if (charset == StandardCharsets.UTF_8 || charset == StandardCharsets.US_ASCII)
                1
            else
                2
        }

        private fun isUtf8ContinuationByte(b: Byte): Boolean {
            return (b.toInt() and 0xC0) == 0x80
        }

        private fun decodeUtf8CodeUnit(b1: Int, b2: Int, b3: Int, b4: Int): Int {
            return Ints.fromBytes(
                0.toByte(),
                UnsignedBytes.checkedCast((((b1 and 0x7) shl 2) or ((b2 and 48) shr 4)).toLong()),
                UnsignedBytes.checkedCast(
                    ((b2.toByte().toInt() and 0xF) shl 4 or ((b3.toByte()
                        .toInt() and 60) shr 2)).toLong()
                ),
                UnsignedBytes.checkedCast(
                    ((b3.toByte().toInt() and 0x3) shl 6 or (b4.toByte().toInt() and 0x3F)).toLong()
                )
            )
        }

        private val isRunningInTest: Boolean
            get() {
                if (Ascii.equalsIgnoreCase(
                        "robolectric",
                        Build.FINGERPRINT
                    )
                ) {
                    return true
                }
                val loader = ParsableByteArray::class.java.getClassLoader()
                return (loader != null
                        && (loader.getResource("androidx/test/espresso/Espresso.class") != null || loader.getResource(
                    "org/junit/runner/Runner.class"
                ) != null || (loader.getResource("androidx/test/platform/app/InstrumentationRegistry.class")
                        != null)))
                        || ActivityManager.isRunningInTestHarness()
            }
    }
}
