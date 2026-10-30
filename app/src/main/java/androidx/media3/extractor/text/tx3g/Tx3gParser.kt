
package androidx.media3.extractor.text.tx3g

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Preconditions
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class Tx3gParser(initializationData: List<ByteArray>) : SubtitleParser {
    private val parsableByteArray: ParsableByteArray

    private val customVerticalPlacement: Boolean
    private val defaultFontFace: Int
    private val defaultColorRgba: Int
    private val defaultFontFamily: String
    private val defaultVerticalPlacement: Float
    private val calculatedVideoTrackHeight: Int

    init {
        parsableByteArray = ParsableByteArray()

        if (initializationData.size == 1
            && (initializationData[0].size == 48 || initializationData[0].size == 53)
        ) {
            val initializationBytes = initializationData[0]
            defaultFontFace = initializationBytes[24].toInt()
            defaultColorRgba =
                (((initializationBytes[26].toInt() and 0xFF) shl 24)
                        or ((initializationBytes[27].toInt() and 0xFF) shl 16)
                        or ((initializationBytes[28].toInt() and 0xFF) shl 8)
                        or (initializationBytes[29].toInt() and 0xFF))
            val fontFamily =
                Util.fromUtf8Bytes(initializationBytes, 43, initializationBytes.size - 43)
            defaultFontFamily = if (TX3G_SERIF == fontFamily) C.SERIF_NAME else C.SANS_SERIF_NAME
            // font size (initializationBytes[25]) is 5% of video height
            calculatedVideoTrackHeight = 20 * initializationBytes[25]
            customVerticalPlacement = (initializationBytes[0].toInt() and 0x20) != 0
            if (customVerticalPlacement) {
                val requestedVerticalPlacement =
                    ((initializationBytes[10].toInt() and 0xFF) shl 8) or (initializationBytes[11].toInt() and 0xFF)
                defaultVerticalPlacement =
                    Util.constrainValue(
                        requestedVerticalPlacement.toFloat() / calculatedVideoTrackHeight,
                        0.0f,
                        0.95f
                    )
            } else {
                defaultVerticalPlacement = DEFAULT_VERTICAL_PLACEMENT
            }
        } else {
            defaultFontFace = DEFAULT_FONT_FACE
            defaultColorRgba = DEFAULT_COLOR
            defaultFontFamily = DEFAULT_FONT_FAMILY
            customVerticalPlacement = false
            defaultVerticalPlacement = DEFAULT_VERTICAL_PLACEMENT
            calculatedVideoTrackHeight = C.LENGTH_UNSET
        }
    }

    override fun parse(
      data: ByteArray,
      offset: Int,
      length: Int,
      outputOptions: SubtitleParser.OutputOptions,
      output: ((CuesWithTiming)-> Unit)
    ) {
        parsableByteArray.reset(data,  offset + length)
        parsableByteArray.setPosition(offset)
        val cueTextString: String = readSubtitleText(parsableByteArray)
        if (cueTextString.isEmpty()) {
            output.invoke(
                CuesWithTiming( 
                    listOf(),  
                    C.TIME_UNSET,  
                    C.TIME_UNSET
                )
            )
            return
        }
        // Attach default styles.
        val cueText: SpannableStringBuilder = SpannableStringBuilder(cueTextString)
        attachFontFace(
            cueText, defaultFontFace, DEFAULT_FONT_FACE, 0, cueText.length, SPAN_PRIORITY_LOW
        )
        attachColor(cueText, defaultColorRgba, DEFAULT_COLOR, 0, cueText.length, SPAN_PRIORITY_LOW)
        attachFontFamily(cueText, defaultFontFamily, 0, cueText.length)
        var verticalPlacement = defaultVerticalPlacement
        // Find and attach additional styles.
        while (parsableByteArray.bytesLeft() >= SIZE_ATOM_HEADER) {
            val position = parsableByteArray.getPosition()
            val atomSize = parsableByteArray.readInt()
            val atomType = parsableByteArray.readInt()
            if (atomType == TYPE_STYL) {
                Preconditions.checkArgument(parsableByteArray.bytesLeft() >= SIZE_SHORT)
                val styleRecordCount = parsableByteArray.readUnsignedShort()
                for (i in 0..<styleRecordCount) {
                    applyStyleRecord(parsableByteArray, cueText)
                }
            } else if (atomType == TYPE_TBOX && customVerticalPlacement) {
                Preconditions.checkArgument(parsableByteArray.bytesLeft() >= SIZE_SHORT)
                val requestedVerticalPlacement = parsableByteArray.readUnsignedShort()
                verticalPlacement =
                    requestedVerticalPlacement.toFloat() / calculatedVideoTrackHeight
                verticalPlacement = Util.constrainValue(verticalPlacement, 0.0f, 0.95f)
            }
            parsableByteArray.setPosition(position + atomSize)
        }
        val cue: Cue =
            Cue.Builder()
                .setText(cueText)
                .setLine(verticalPlacement, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_START)
                .build()
        output(
            CuesWithTiming(
               listOf(cue),  
                C.TIME_UNSET,  
                C.TIME_UNSET
            )
        )
    }

    private fun applyStyleRecord(
        parsableByteArray: ParsableByteArray, cueText: SpannableStringBuilder
    ) {
        Preconditions.checkArgument(parsableByteArray.bytesLeft() >= SIZE_STYLE_RECORD)
        val start: Int = parsableByteArray.readUnsignedShort()
        var end: Int = parsableByteArray.readUnsignedShort()
        parsableByteArray.skipBytes(2) // font identifier
        val fontFace: Int = parsableByteArray.readUnsignedByte()
        parsableByteArray.skipBytes(1) // font size
        val colorRgba: Int = parsableByteArray.readInt()

        if (end > cueText.length) {
            Log.w(
                TAG,
                "Truncating styl end (" + end + ") to cueText.length() (" + cueText.length + ")."
            )
            end = cueText.length
        }
        if (start >= end) {
            Log.w(TAG, "Ignoring styl with start (" + start + ") >= end (" + end + ").")
            return
        }
        attachFontFace(cueText, fontFace, defaultFontFace, start, end, SPAN_PRIORITY_HIGH)
        attachColor(cueText, colorRgba, defaultColorRgba, start, end, SPAN_PRIORITY_HIGH)
    }

    override val cueReplacementBehavior: Int
        get() = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    companion object {
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

        private const val TAG = "Tx3gParser"

        private const val TYPE_STYL = 0x7374796c
        private const val TYPE_TBOX = 0x74626f78
        private const val TX3G_SERIF = "Serif"

        private const val SIZE_ATOM_HEADER = 8
        private const val SIZE_SHORT = 2
        private const val SIZE_STYLE_RECORD = 12

        private const val FONT_FACE_BOLD = 0x0001
        private const val FONT_FACE_ITALIC = 0x0002
        private const val FONT_FACE_UNDERLINE = 0x0004

        private val SPAN_PRIORITY_LOW = 0xFF shl Spanned.SPAN_PRIORITY_SHIFT
        private const val SPAN_PRIORITY_HIGH = 0

        private const val DEFAULT_FONT_FACE = 0
        private val DEFAULT_COLOR = Color.WHITE
        private val DEFAULT_FONT_FAMILY = C.SANS_SERIF_NAME
        private const val DEFAULT_VERTICAL_PLACEMENT = 0.85f

        private fun readSubtitleText(parsableByteArray: ParsableByteArray): String {
            Preconditions.checkArgument(parsableByteArray.bytesLeft() >= SIZE_SHORT)
            val textLength: Int = parsableByteArray.readUnsignedShort()
            if (textLength == 0) {
                return ""
            }
            val textStartPosition: Int = parsableByteArray.getPosition()
            val charset: Charset? = parsableByteArray.readUtfCharsetFromBom()
            val bomSize: Int = parsableByteArray.getPosition() - textStartPosition
            return parsableByteArray.readString(
                textLength - bomSize, if (charset != null) charset else StandardCharsets.UTF_8
            )
        }

        private fun attachFontFace(
            cueText: SpannableStringBuilder,
            fontFace: Int,
            defaultFontFace: Int,
            start: Int,
            end: Int,
            spanPriority: Int
        ) {
            if (fontFace != defaultFontFace) {
                val flags: Int = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or spanPriority
                val isBold = (fontFace and FONT_FACE_BOLD) != 0
                val isItalic = (fontFace and FONT_FACE_ITALIC) != 0
                if (isBold) {
                    if (isItalic) {
                        cueText.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, flags)
                    } else {
                        cueText.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
                    }
                } else if (isItalic) {
                    cueText.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
                }
                val isUnderlined = (fontFace and FONT_FACE_UNDERLINE) != 0
                if (isUnderlined) {
                    cueText.setSpan(UnderlineSpan(), start, end, flags)
                }
                if (!isUnderlined && !isBold && !isItalic) {
                    cueText.setSpan(StyleSpan(Typeface.NORMAL), start, end, flags)
                }
            }
        }

        private fun attachColor(
            cueText: SpannableStringBuilder,
            colorRgba: Int,
            defaultColorRgba: Int,
            start: Int,
            end: Int,
            spanPriority: Int
        ) {
            if (colorRgba != defaultColorRgba) {
                val colorArgb = ((colorRgba and 0xFF) shl 24) or (colorRgba ushr 8)
                cueText.setSpan(
                  ForegroundColorSpan(colorArgb),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or spanPriority
                )
            }
        }

        private fun attachFontFamily(
            cueText: SpannableStringBuilder, fontFamily: String?, start: Int, end: Int
        ) {
            if (fontFamily !== DEFAULT_FONT_FAMILY) {
                cueText.setSpan(
                  TypefaceSpan(fontFamily),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or SPAN_PRIORITY_LOW
                )
            }
        }
    }
}
