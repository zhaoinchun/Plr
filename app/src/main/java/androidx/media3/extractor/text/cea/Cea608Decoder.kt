
package androidx.media3.extractor.text.cea

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.Cue.AnchorType
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

class Cea608Decoder(mimeType: String?, accessibilityChannel: Int, validDataChannelTimeoutMs: Long) :
    CeaDecoder() {
    private val ccData: ParsableByteArray
    private val packetLength: Int
    private val selectedField: Int
    private val selectedChannel: Int
    private val validDataChannelTimeoutUs: Long
    private val cueBuilders: ArrayList<CueBuilder?>

    private var currentCueBuilder: CueBuilder
    private var cues: MutableList<Cue>? = null
    private var lastCues: MutableList<Cue>? = null

    private var captionMode = 0
    private var captionRowCount = 0

    private var isCaptionValid = false
    private var repeatableControlSet = false
    private var repeatableControlCc1: Byte = 0
    private var repeatableControlCc2: Byte = 0
    private var currentChannel: Int

    // The incoming characters may belong to 3 different services based on the last received control
    // codes. The 3 services are Captioning, Text and XDS. The decoder only processes Captioning
    // service bytes and drops the rest.
    private var isInCaptionService: Boolean

    private var lastCueUpdateUs: Long

    init {
        ccData = ParsableByteArray()
        cueBuilders = ArrayList<CueBuilder?>()
        currentCueBuilder = CueBuilder(CC_MODE_UNKNOWN, DEFAULT_CAPTIONS_ROW_COUNT)
        currentChannel = NTSC_CC_CHANNEL_1
        if (validDataChannelTimeoutMs != C.TIME_UNSET) {
            Preconditions.checkArgument(validDataChannelTimeoutMs >= MIN_DATA_CHANNEL_TIMEOUT_MS)
            this.validDataChannelTimeoutUs = validDataChannelTimeoutMs * 1000
        } else {
            this.validDataChannelTimeoutUs = C.TIME_UNSET
        }
        packetLength = if (MimeTypes.APPLICATION_MP4CEA608 == mimeType) 2 else 3
        when (accessibilityChannel) {
            1 -> {
                selectedChannel = NTSC_CC_CHANNEL_1
                selectedField = NTSC_CC_FIELD_1
            }

            2 -> {
                selectedChannel = NTSC_CC_CHANNEL_2
                selectedField = NTSC_CC_FIELD_1
            }

            3 -> {
                selectedChannel = NTSC_CC_CHANNEL_1
                selectedField = NTSC_CC_FIELD_2
            }

            4 -> {
                selectedChannel = NTSC_CC_CHANNEL_2
                selectedField = NTSC_CC_FIELD_2
            }

            else -> {
                w(TAG, "Invalid channel. Defaulting to CC1.")
                selectedChannel = NTSC_CC_CHANNEL_1
                selectedField = NTSC_CC_FIELD_1
            }
        }

        setCaptionMode(CC_MODE_UNKNOWN)
        resetCueBuilders()
        isInCaptionService = true
        lastCueUpdateUs = C.TIME_UNSET
    }

    override val name: String
        get() = "Cea608Decoder"

    override fun flush() {
        super.flush()
        cues = null
        lastCues = null
        setCaptionMode(CC_MODE_UNKNOWN)
        setCaptionRowCount(DEFAULT_CAPTIONS_ROW_COUNT)
        resetCueBuilders()
        isCaptionValid = false
        repeatableControlSet = false
        repeatableControlCc1 = 0
        repeatableControlCc2 = 0
        currentChannel = NTSC_CC_CHANNEL_1
        isInCaptionService = true
        lastCueUpdateUs = C.TIME_UNSET
    }

    override fun release() {
        // Do nothing
    }

    @Throws(SubtitleDecoderException::class)
    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? {
        var outputBuffer = super.dequeueOutputBuffer()
        if (outputBuffer != null) {
            return outputBuffer
        }
        if (shouldClearStuckCaptions()) {
            outputBuffer = availableOutputBuffer
            if (outputBuffer != null) {
                cues = mutableListOf<Cue>()
                lastCueUpdateUs = C.TIME_UNSET
                val subtitle = createSubtitle()
                outputBuffer.setContent(getPositionUs(), subtitle, Format.OFFSET_SAMPLE_RELATIVE)
                return outputBuffer
            }
        }
        return null
    }

    override val isNewSubtitleDataAvailable: Boolean
        get() = cues !== lastCues

    override fun createSubtitle(): Subtitle {
        lastCues = cues
        return CeaSubtitle(cues!!)
    }

    override fun decode(inputBuffer: SubtitleInputBuffer) {
        val subtitleData = inputBuffer.data!!
        ccData.reset(subtitleData.array(), subtitleData.limit())
        var captionDataProcessed = false
        while (ccData.bytesLeft() >= packetLength) {
            val ccHeader =
                if (packetLength == 2) CC_IMPLICIT_DATA_HEADER.toInt() else ccData.readUnsignedByte()

            val ccByte1 = ccData.readUnsignedByte()
            val ccByte2 = ccData.readUnsignedByte()

            // TODO: We're currently ignoring the top 5 marker bits, which should all be 1s according
            // to the CEA-608 specification. We need to determine if the data should be handled
            // differently when that is not the case.
            if ((ccHeader and CC_TYPE_FLAG) != 0) {
                // Do not process anything that is not part of the 608 byte stream.
                continue
            }

            if ((ccHeader and CC_FIELD_FLAG) != selectedField) {
                // Do not process packets not within the selected field.
                continue
            }

            // Strip the parity bit from each byte to get CC data.
            val ccData1 = (ccByte1 and 0x7F).toByte()
            val ccData2 = (ccByte2 and 0x7F).toByte()

            if (ccData1.toInt() == 0 && ccData2.toInt() == 0) {
                // Ignore empty captions.
                continue
            }

            val previousIsCaptionValid = isCaptionValid
            isCaptionValid =
                (ccHeader and CC_VALID_FLAG) == CC_VALID_FLAG && ODD_PARITY_BYTE_TABLE[ccByte1]
                        && ODD_PARITY_BYTE_TABLE[ccByte2]

            if (isRepeatedCommand(isCaptionValid, ccData1, ccData2)) {
                // Ignore repeated valid commands.
                continue
            }

            if (!isCaptionValid) {
                if (previousIsCaptionValid) {
                    // The encoder has flipped the validity bit to indicate captions are being turned off.
                    resetCueBuilders()
                    captionDataProcessed = true
                }
                continue
            }

            maybeUpdateIsInCaptionService(ccData1, ccData2)
            if (!isInCaptionService) {
                // Only the Captioning service is supported. Drop all other bytes.
                continue
            }

            if (!updateAndVerifyCurrentChannel(ccData1)) {
                // Wrong channel.
                continue
            }

            if (isCtrlCode(ccData1)) {
                if (isSpecialNorthAmericanChar(ccData1, ccData2)) {
                    currentCueBuilder.append(getSpecialNorthAmericanChar(ccData2))
                } else if (isExtendedWestEuropeanChar(ccData1, ccData2)) {
                    // Remove standard equivalent of the special extended char before appending new one.
                    currentCueBuilder.backspace()
                    currentCueBuilder.append(getExtendedWestEuropeanChar(ccData1, ccData2))
                } else if (isMidrowCtrlCode(ccData1, ccData2)) {
                    handleMidrowCtrl(ccData2)
                } else if (isPreambleAddressCode(ccData1, ccData2)) {
                    handlePreambleAddressCode(ccData1, ccData2)
                } else if (isTabCtrlCode(ccData1, ccData2)) {
                    currentCueBuilder.tabOffset = ccData2 - 0x20
                } else if (isMiscCode(ccData1, ccData2)) {
                    handleMiscCode(ccData2)
                }
            } else {
                // Basic North American character set.
                currentCueBuilder.append(getBasicChar(ccData1))
                if ((ccData2.toInt() and 0xE0) != 0x00) {
                    currentCueBuilder.append(getBasicChar(ccData2))
                }
            }
            captionDataProcessed = true
        }

        if (captionDataProcessed) {
            if (captionMode == CC_MODE_ROLL_UP || captionMode == CC_MODE_PAINT_ON) {
                cues = this.displayCues
                lastCueUpdateUs = getPositionUs()
            }
        }
    }

    private fun updateAndVerifyCurrentChannel(cc1: Byte): Boolean {
        if (isCtrlCode(cc1)) {
            currentChannel = getChannel(cc1)
        }
        return currentChannel == selectedChannel
    }

    private fun isRepeatedCommand(captionValid: Boolean, cc1: Byte, cc2: Byte): Boolean {
        // Most control commands are sent twice in succession to ensure they are received properly. We
        // don't want to process duplicate commands, so if we see the same repeatable command twice in a
        // row then we ignore the second one.
        if (captionValid && isRepeatable(cc1)) {
            if (repeatableControlSet && repeatableControlCc1 == cc1 && repeatableControlCc2 == cc2) {
                // This is a repeated command, so we ignore it.
                repeatableControlSet = false
                return true
            } else {
                // This is the first occurrence of a repeatable command. Set the repeatable control
                // variables so that we can recognize and ignore a duplicate (if there is one), and then
                // continue to process the command below.
                repeatableControlSet = true
                repeatableControlCc1 = cc1
                repeatableControlCc2 = cc2
            }
        } else {
            // This command is not repeatable.
            repeatableControlSet = false
        }
        return false
    }

    private fun handleMidrowCtrl(cc2: Byte) {
        // TODO: support the extended styles (i.e. backgrounds and transparencies)

        // A midrow control code advances the cursor.

        currentCueBuilder.append(' ')

        // cc2 - 0|0|1|0|STYLE|U
        val underline = (cc2.toInt() and 0x01) == 0x01
        val style = (cc2.toInt() shr 1) and 0x07
        currentCueBuilder.setStyle(style, underline)
    }

    private fun handlePreambleAddressCode(cc1: Byte, cc2: Byte) {
        // cc1 - 0|0|0|1|C|E|ROW
        // C is the channel toggle, E is the extended flag, and ROW is the encoded row
        var row: Int = ROW_INDICES[cc1.toInt() and 0x07]

        // TODO: support the extended address and style

        // cc2 - 0|1|N|ATTRBTE|U
        // N is the next row down toggle, ATTRBTE is the 4-byte encoded attribute, and U is the
        // underline toggle.
        val nextRowDown = (cc2.toInt() and 0x20) != 0
        if (nextRowDown) {
            row++
        }

        if (row != currentCueBuilder.row) {
            if (captionMode != CC_MODE_ROLL_UP && !currentCueBuilder.isEmpty) {
                currentCueBuilder = CueBuilder(captionMode, captionRowCount)
                cueBuilders.add(currentCueBuilder)
            }
            currentCueBuilder.row = row
        }

        // cc2 - 0|1|N|0|STYLE|U
        // cc2 - 0|1|N|1|CURSR|U
        val isCursor = (cc2.toInt() and 0x10) == 0x10
        val underline = (cc2.toInt() and 0x01) == 0x01
        val cursorOrStyle = (cc2.toInt() shr 1) and 0x07

        // We need to call setStyle even for the isCursor case, to update the underline bit.
        // STYLE_UNCHANGED is used for this case.
        currentCueBuilder.setStyle(if (isCursor) STYLE_UNCHANGED else cursorOrStyle, underline)

        if (isCursor) {
            currentCueBuilder.indent = COLUMN_INDICES[cursorOrStyle]
        }
    }

    private fun handleMiscCode(cc2: Byte) {
        when (cc2) {
            CTRL_ROLL_UP_CAPTIONS_2_ROWS -> {
                setCaptionMode(CC_MODE_ROLL_UP)
                setCaptionRowCount(2)
                return
            }

            CTRL_ROLL_UP_CAPTIONS_3_ROWS -> {
                setCaptionMode(CC_MODE_ROLL_UP)
                setCaptionRowCount(3)
                return
            }

            CTRL_ROLL_UP_CAPTIONS_4_ROWS -> {
                setCaptionMode(CC_MODE_ROLL_UP)
                setCaptionRowCount(4)
                return
            }

            CTRL_RESUME_CAPTION_LOADING -> {
                setCaptionMode(CC_MODE_POP_ON)
                return
            }

            CTRL_RESUME_DIRECT_CAPTIONING -> {
                setCaptionMode(CC_MODE_PAINT_ON)
                return
            }

            else -> {}
        }

        if (captionMode == CC_MODE_UNKNOWN) {
            return
        }

        when (cc2) {
            CTRL_ERASE_DISPLAYED_MEMORY -> {
                cues = mutableListOf<Cue>()
                if (captionMode == CC_MODE_ROLL_UP || captionMode == CC_MODE_PAINT_ON) {
                    resetCueBuilders()
                }
            }

            CTRL_ERASE_NON_DISPLAYED_MEMORY -> resetCueBuilders()
            CTRL_END_OF_CAPTION -> {
                cues = this.displayCues
                resetCueBuilders()
            }

            CTRL_CARRIAGE_RETURN ->         // carriage returns only apply to rollup captions; don't bother if we don't have anything
                // to add a carriage return to
                if (captionMode == CC_MODE_ROLL_UP && !currentCueBuilder.isEmpty) {
                    currentCueBuilder.rollUp()
                }

            CTRL_BACKSPACE -> currentCueBuilder.backspace()
            CTRL_DELETE_TO_END_OF_ROW -> {}
            else -> {}
        }
    }

    private val displayCues: MutableList<Cue>
        get() {
            // CEA-608 does not define middle and end alignment, however content providers artificially
            // introduce them using whitespace. When each cue is built, we try and infer the alignment based
            // on the amount of whitespace either side of the text. To avoid consecutive cues being aligned
            // differently, we force all cues to have the same alignment, with start alignment given
            // preference, then middle alignment, then end alignment.
            @AnchorType var positionAnchor: @AnchorType Int = Cue.ANCHOR_TYPE_END
            val cueBuilderCount = cueBuilders.size
            val cueBuilderCues: MutableList<Cue?> =
                ArrayList<Cue?>(cueBuilderCount)
            for (i in 0..<cueBuilderCount) {
                val cue =
                    cueBuilders[i]!!.build( Cue.TYPE_UNSET)
                cueBuilderCues.add(cue)
                if (cue != null) {
                    positionAnchor = min(positionAnchor, cue.positionAnchor)
                }
            }

            // Skip null cues and rebuild any that don't have the preferred alignment.
            val displayCues: MutableList<Cue> =
                ArrayList<Cue>(cueBuilderCount)
            for (i in 0..<cueBuilderCount) {
                var cue = cueBuilderCues[i]
                if (cue != null) {
                    if (cue.positionAnchor != positionAnchor) {
                        // The last time we built this cue it was non-null, it will be non-null this time too.
                        cue = cueBuilders[i]!!.build(positionAnchor)!!
                    }
                    displayCues.add(cue)
                }
            }

            return displayCues
        }

    private fun setCaptionMode(captionMode: Int) {
        if (this.captionMode == captionMode) {
            return
        }

        val oldCaptionMode = this.captionMode
        this.captionMode = captionMode

        if (captionMode == CC_MODE_PAINT_ON) {
            // Switching to paint-on mode should have no effect except to select the mode.
            for (i in cueBuilders.indices) {
                cueBuilders[i]!!.setCaptionMode(captionMode)
            }
            return
        }

        // Clear the working memory.
        resetCueBuilders()
        if (oldCaptionMode == CC_MODE_PAINT_ON || captionMode == CC_MODE_ROLL_UP || captionMode == CC_MODE_UNKNOWN) {
            // When switching from paint-on or to roll-up or unknown, we also need to clear the caption.
            cues = mutableListOf<Cue>()
        }
    }

    private fun setCaptionRowCount(captionRowCount: Int) {
        this.captionRowCount = captionRowCount
        currentCueBuilder.setCaptionRowCount(captionRowCount)
    }

    private fun resetCueBuilders() {
        currentCueBuilder.reset(captionMode)
        cueBuilders.clear()
        cueBuilders.add(currentCueBuilder)
    }

    private fun maybeUpdateIsInCaptionService(cc1: Byte, cc2: Byte) {
        if (isXdsControlCode(cc1)) {
            isInCaptionService = false
        } else if (isServiceSwitchCommand(cc1)) {
            when (cc2) {
                CTRL_TEXT_RESTART, CTRL_RESUME_TEXT_DISPLAY -> isInCaptionService = false
                CTRL_END_OF_CAPTION, CTRL_RESUME_CAPTION_LOADING, CTRL_RESUME_DIRECT_CAPTIONING, CTRL_ROLL_UP_CAPTIONS_2_ROWS, CTRL_ROLL_UP_CAPTIONS_3_ROWS, CTRL_ROLL_UP_CAPTIONS_4_ROWS -> isInCaptionService =
                    true

                else -> {}
            }
        }
    }

    private class CueBuilder(captionMode: Int, captionRowCount: Int) {
        private val cueStyles: MutableList<CueStyle>
        private val rolledUpCaptions: MutableList<SpannableString?>
        private val captionStringBuilder: StringBuilder

         var row = 0
         var indent = 0
         var tabOffset = 0
        private var captionMode = 0
        private var captionRowCount: Int

        init {
            cueStyles = ArrayList<CueStyle>()
            rolledUpCaptions = ArrayList<SpannableString?>()
            captionStringBuilder = StringBuilder()
            reset(captionMode)
            this.captionRowCount = captionRowCount
        }

        fun reset(captionMode: Int) {
            this.captionMode = captionMode
            cueStyles.clear()
            rolledUpCaptions.clear()
            captionStringBuilder.setLength(0)
            row = BASE_ROW
            indent = 0
            tabOffset = 0
        }

        val isEmpty: Boolean
            get() = cueStyles.isEmpty()
                    && rolledUpCaptions.isEmpty()
                    && captionStringBuilder.length == 0

        fun setCaptionMode(captionMode: Int) {
            this.captionMode = captionMode
        }

        fun setCaptionRowCount(captionRowCount: Int) {
            this.captionRowCount = captionRowCount
        }

        fun setStyle(style: Int, underline: Boolean) {
            cueStyles.add(CueStyle(style, underline, captionStringBuilder.length))
        }

        fun backspace() {
            val length = captionStringBuilder.length
            if (length > 0) {
                captionStringBuilder.delete(length - 1, length)
                // Decrement style start positions if necessary.
                for (i in cueStyles.indices.reversed()) {
                    val style = cueStyles[i]
                    if (style.start == length) {
                        style.start--
                    } else {
                        // All earlier cues must have style.start < length.
                        break
                    }
                }
            }
        }

        fun append(text: Char) {
            // Don't accept more than 32 chars.
            if (captionStringBuilder.length < SCREEN_CHARWIDTH) {
                captionStringBuilder.append(text)
            }
        }

        fun rollUp() {
            rolledUpCaptions.add(buildCurrentLine())
            captionStringBuilder.setLength(0)
            cueStyles.clear()
            val numRows = min(captionRowCount, row)
            while (rolledUpCaptions.size >= numRows) {
                rolledUpCaptions.removeAt(0)
            }
        }

        fun build(@AnchorType forcedPositionAnchor: @AnchorType Int): Cue? {
            val cueString = SpannableStringBuilder()
            // Add any rolled up captions, separated by new lines.
            for (i in rolledUpCaptions.indices) {
                cueString.append(rolledUpCaptions[i])
                cueString.append('\n')
            }
            // Add the current line.
            cueString.append(buildCurrentLine())

            if (cueString.length == 0) {
                // The cue is empty.
                return null
            }

            val positionAnchor: Int
            // The number of empty columns before the start of the text, in the range [0-31].
            val startPadding = indent + tabOffset
            // The number of empty columns after the end of the text, in the same range.
            val endPadding: Int = SCREEN_CHARWIDTH - startPadding - cueString.length
            val startEndPaddingDelta = startPadding - endPadding
            if (forcedPositionAnchor != Cue.TYPE_UNSET) {
                positionAnchor = forcedPositionAnchor
            } else if (captionMode == CC_MODE_POP_ON
                && (abs(startEndPaddingDelta) < 3 || endPadding < 0)
            ) {
                // Treat approximately centered pop-on captions as middle aligned. We also treat captions
                // that are wider than they should be in this way. See
                // https://github.com/google/ExoPlayer/issues/3534.
                positionAnchor = Cue.ANCHOR_TYPE_MIDDLE
            } else if (captionMode == CC_MODE_POP_ON && startEndPaddingDelta > 0) {
                // Treat pop-on captions with less padding at the end than the start as end aligned.
                positionAnchor = Cue.ANCHOR_TYPE_END
            } else {
                // For all other cases assume start aligned.
                positionAnchor = Cue.ANCHOR_TYPE_START
            }

            var position: Float
            when (positionAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> position = 0.5f
                Cue.ANCHOR_TYPE_END -> {
                    position = (SCREEN_CHARWIDTH - endPadding).toFloat() / SCREEN_CHARWIDTH
                    // Adjust the position to fit within the safe area.
                    position = position * 0.8f + 0.1f
                }

                Cue.ANCHOR_TYPE_START -> {
                    position = startPadding.toFloat() / SCREEN_CHARWIDTH
                    // Adjust the position to fit within the safe area.
                    position = position * 0.8f + 0.1f
                }

                else -> {
                    position = startPadding.toFloat() / SCREEN_CHARWIDTH
                    position = position * 0.8f + 0.1f
                }
            }

            var line: Int
            // Note: Row indices are in the range [1-15], Cue.line counts from 0 (top) and -1 (bottom).
            if (row > (BASE_ROW / 2)) {
                line = row - BASE_ROW
                // Two line adjustments. The first is because line indices from the bottom of the window
                // start from -1 rather than 0. The second is a blank row to act as the safe area.
                line -= 2
            } else {
                // The `row` of roll-up cues positions the bottom line (even for cues shown in the top
                // half of the screen), so we need to consider the number of rows in this cue. In
                // non-roll-up, we don't need any further adjustments because we leave the first line
                // (cue.line=0) blank to act as the safe area, so positioning row=1 at Cue.line=1 is
                // correct.
                line = if (captionMode == CC_MODE_ROLL_UP) row - (captionRowCount - 1) else row
            }

            return Cue.Builder()
                .setText(cueString)
                .setTextAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLine(line.toFloat(), Cue.LINE_TYPE_NUMBER)
                .setPosition(position)
                .setPositionAnchor(positionAnchor)
                .build()
        }

        fun buildCurrentLine(): SpannableString {
            val builder = SpannableStringBuilder(captionStringBuilder)
            val length = builder.length

            var underlineStartPosition = C.INDEX_UNSET
            var italicStartPosition = C.INDEX_UNSET
            var colorStartPosition = 0
            var color = Color.WHITE

            var nextItalic = false
            var nextColor = Color.WHITE

            for (i in cueStyles.indices) {
                val cueStyle = cueStyles[i]
                val underline = cueStyle.underline
                val style = cueStyle.style
                if (style != STYLE_UNCHANGED) {
                    // If the style is a color then italic is cleared.
                    nextItalic = style == STYLE_ITALICS
                    // If the style is italic then the color is left unchanged.
                    nextColor = if (style == STYLE_ITALICS) nextColor else STYLE_COLORS[style]
                }

                val position = cueStyle.start
                val nextPosition =
                    if ((i + 1) < cueStyles.size) cueStyles.get(i + 1).start else length
                if (position == nextPosition) {
                    // There are more cueStyles to process at the current position.
                    continue
                }

                // Process changes to underline up to the current position.
                if (underlineStartPosition != C.INDEX_UNSET && !underline) {
                    setUnderlineSpan(builder, underlineStartPosition, position)
                    underlineStartPosition = C.INDEX_UNSET
                } else if (underlineStartPosition == C.INDEX_UNSET && underline) {
                    underlineStartPosition = position
                }
                // Process changes to italic up to the current position.
                if (italicStartPosition != C.INDEX_UNSET && !nextItalic) {
                    setItalicSpan(builder, italicStartPosition, position)
                    italicStartPosition = C.INDEX_UNSET
                } else if (italicStartPosition == C.INDEX_UNSET && nextItalic) {
                    italicStartPosition = position
                }
                // Process changes to color up to the current position.
                if (nextColor != color) {
                    setColorSpan(builder, colorStartPosition, position, color)
                    color = nextColor
                    colorStartPosition = position
                }
            }

            // Add any final spans.
            if (underlineStartPosition != C.INDEX_UNSET && underlineStartPosition != length) {
                setUnderlineSpan(builder, underlineStartPosition, length)
            }
            if (italicStartPosition != C.INDEX_UNSET && italicStartPosition != length) {
                setItalicSpan(builder, italicStartPosition, length)
            }
            if (colorStartPosition != length) {
                setColorSpan(builder, colorStartPosition, length, color)
            }

            return SpannableString(builder)
        }

        private class CueStyle(val style: Int, val underline: Boolean, var start: Int)
        companion object {
            // 608 captions define a 15 row by 32 column screen grid. These constants convert from 608
            // positions to normalized screen position.
            private const val SCREEN_CHARWIDTH = 32
            private const val BASE_ROW = 15

            private fun setUnderlineSpan(builder: SpannableStringBuilder, start: Int, end: Int) {
                builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            private fun setItalicSpan(builder: SpannableStringBuilder, start: Int, end: Int) {
                builder.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            private fun setColorSpan(
                builder: SpannableStringBuilder, start: Int, end: Int, color: Int
            ) {
                if (color == Color.WHITE) {
                    // White is treated as the default color (i.e. no span is attached).
                    return
                }
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun shouldClearStuckCaptions(): Boolean {
        if (validDataChannelTimeoutUs == C.TIME_UNSET || lastCueUpdateUs == C.TIME_UNSET) {
            return false
        }
        val elapsedUs = getPositionUs() - lastCueUpdateUs
        return elapsedUs >= validDataChannelTimeoutUs
    }

    companion object {
        
        const val MIN_DATA_CHANNEL_TIMEOUT_MS: Long = 16000

        private const val TAG = "Cea608Decoder"

        private const val CC_VALID_FLAG = 0x04
        private const val CC_TYPE_FLAG = 0x02
        private const val CC_FIELD_FLAG = 0x01

        private const val NTSC_CC_FIELD_1 = 0x00
        private const val NTSC_CC_FIELD_2 = 0x01
        private const val NTSC_CC_CHANNEL_1 = 0x00
        private const val NTSC_CC_CHANNEL_2 = 0x01

        private const val CC_MODE_UNKNOWN = 0
        private const val CC_MODE_ROLL_UP = 1
        private const val CC_MODE_POP_ON = 2
        private const val CC_MODE_PAINT_ON = 3

        private val ROW_INDICES = intArrayOf(11, 1, 3, 12, 14, 5, 7, 9)
        private val COLUMN_INDICES = intArrayOf(0, 4, 8, 12, 16, 20, 24, 28)

        private val STYLE_COLORS = intArrayOf(
            Color.WHITE, Color.GREEN, Color.BLUE, Color.CYAN, Color.RED, Color.YELLOW, Color.MAGENTA
        )
        private const val STYLE_ITALICS = 0x07
        private const val STYLE_UNCHANGED = 0x08

        // The default number of rows to display in roll-up captions mode.
        private const val DEFAULT_CAPTIONS_ROW_COUNT = 4

        // An implied first byte for packets that are only 2 bytes long, consisting of marker bits
        // (0b11111) + valid bit (0b1) + NTSC field 1 type bits (0b00).
        private val CC_IMPLICIT_DATA_HEADER = 0xFC.toByte()

        private const val CTRL_RESUME_CAPTION_LOADING: Byte = 0x20

        private const val CTRL_BACKSPACE: Byte = 0x21

        private const val CTRL_DELETE_TO_END_OF_ROW: Byte = 0x24

        private const val CTRL_ROLL_UP_CAPTIONS_2_ROWS: Byte = 0x25

        private const val CTRL_ROLL_UP_CAPTIONS_3_ROWS: Byte = 0x26

        private const val CTRL_ROLL_UP_CAPTIONS_4_ROWS: Byte = 0x27

        private const val CTRL_RESUME_DIRECT_CAPTIONING: Byte = 0x29

        private const val CTRL_TEXT_RESTART: Byte = 0x2A

        private const val CTRL_RESUME_TEXT_DISPLAY: Byte = 0x2B

        private const val CTRL_ERASE_DISPLAYED_MEMORY: Byte = 0x2C
        private const val CTRL_CARRIAGE_RETURN: Byte = 0x2D
        private const val CTRL_ERASE_NON_DISPLAYED_MEMORY: Byte = 0x2E

        private const val CTRL_END_OF_CAPTION: Byte = 0x2F

        // Basic North American 608 CC char set, mostly ASCII. Indexed by (char-0x20).
        private val BASIC_CHARACTER_SET = intArrayOf(
            0x20,
            0x21,
            0x22,
            0x23,
            0x24,
            0x25,
            0x26,
            0x27,  //   ! " # $ % & '
            0x28,
            0x29,  // ( )
            0xE1,  // 2A: 225 'á' "Latin small letter A with acute"
            0x2B,
            0x2C,
            0x2D,
            0x2E,
            0x2F,  //       + , - . /
            0x30,
            0x31,
            0x32,
            0x33,
            0x34,
            0x35,
            0x36,
            0x37,  // 0 1 2 3 4 5 6 7
            0x38,
            0x39,
            0x3A,
            0x3B,
            0x3C,
            0x3D,
            0x3E,
            0x3F,  // 8 9 : ; < = > ?
            0x40,
            0x41,
            0x42,
            0x43,
            0x44,
            0x45,
            0x46,
            0x47,  // @ A B C D E F G
            0x48,
            0x49,
            0x4A,
            0x4B,
            0x4C,
            0x4D,
            0x4E,
            0x4F,  // H I J K L M N O
            0x50,
            0x51,
            0x52,
            0x53,
            0x54,
            0x55,
            0x56,
            0x57,  // P Q R S T U V W
            0x58,
            0x59,
            0x5A,
            0x5B,  // X Y Z [
            0xE9,  // 5C: 233 'é' "Latin small letter E with acute"
            0x5D,  //           ]
            0xED,  // 5E: 237 'í' "Latin small letter I with acute"
            0xF3,  // 5F: 243 'ó' "Latin small letter O with acute"
            0xFA,  // 60: 250 'ú' "Latin small letter U with acute"
            0x61,
            0x62,
            0x63,
            0x64,
            0x65,
            0x66,
            0x67,  //   a b c d e f g
            0x68,
            0x69,
            0x6A,
            0x6B,
            0x6C,
            0x6D,
            0x6E,
            0x6F,  // h i j k l m n o
            0x70,
            0x71,
            0x72,
            0x73,
            0x74,
            0x75,
            0x76,
            0x77,  // p q r s t u v w
            0x78,
            0x79,
            0x7A,  // x y z
            0xE7,  // 7B: 231 'ç' "Latin small letter C with cedilla"
            0xF7,  // 7C: 247 '÷' "Division sign"
            0xD1,  // 7D: 209 'Ñ' "Latin capital letter N with tilde"
            0xF1,  // 7E: 241 'ñ' "Latin small letter N with tilde"
            0x25A0 // 7F:         "Black Square" (NB: 2588 = Full Block)
        )

        // Special North American 608 CC char set.
        private val SPECIAL_CHARACTER_SET = intArrayOf(
            0xAE,  // 30: 174 '®' "Registered Sign" - registered trademark symbol
            0xB0,  // 31: 176 '°' "Degree Sign"
            0xBD,  // 32: 189 '½' "Vulgar Fraction One Half" (1/2 symbol)
            0xBF,  // 33: 191 '¿' "Inverted Question Mark"
            0x2122,  // 34:         "Trade Mark Sign" (tm superscript)
            0xA2,  // 35: 162 '¢' "Cent Sign"
            0xA3,  // 36: 163 '£' "Pound Sign" - pounds sterling
            0x266A,  // 37:         "Eighth Note" - music note
            0xE0,  // 38: 224 'à' "Latin small letter A with grave"
            0x20,  // 39:         TRANSPARENT SPACE - for now use ordinary space
            0xE8,  // 3A: 232 'è' "Latin small letter E with grave"
            0xE2,  // 3B: 226 'â' "Latin small letter A with circumflex"
            0xEA,  // 3C: 234 'ê' "Latin small letter E with circumflex"
            0xEE,  // 3D: 238 'î' "Latin small letter I with circumflex"
            0xF4,  // 3E: 244 'ô' "Latin small letter O with circumflex"
            0xFB // 3F: 251 'û' "Latin small letter U with circumflex"
        )

        // Extended Spanish/Miscellaneous and French char set.
        private val SPECIAL_ES_FR_CHARACTER_SET = intArrayOf( // Spanish and misc.
            0xC1, 0xC9, 0xD3, 0xDA, 0xDC, 0xFC, 0x2018, 0xA1,
            0x2A, 0x27, 0x2014, 0xA9, 0x2120, 0x2022, 0x201C, 0x201D,  // French.
            0xC0, 0xC2, 0xC7, 0xC8, 0xCA, 0xCB, 0xEB, 0xCE,
            0xCF, 0xEF, 0xD4, 0xD9, 0xF9, 0xDB, 0xAB, 0xBB
        )

        // Extended Portuguese and German/Danish char set.
        private val SPECIAL_PT_DE_CHARACTER_SET = intArrayOf( // Portuguese.
            0xC3, 0xE3, 0xCD, 0xCC, 0xEC, 0xD2, 0xF2, 0xD5,
            0xF5, 0x7B, 0x7D, 0x5C, 0x5E, 0x5F, 0x7C, 0x7E,  // German/Danish.
            0xC4, 0xE4, 0xD6, 0xF6, 0xDF, 0xA5, 0xA4, 0x2502,
            0xC5, 0xE5, 0xD8, 0xF8, 0x250C, 0x2510, 0x2514, 0x2518
        )

        private val ODD_PARITY_BYTE_TABLE = booleanArrayOf(
            false, true, true, false, true, false, false, true,  // 0
            true, false, false, true, false, true, true, false,  // 8
            true, false, false, true, false, true, true, false,  // 16
            false, true, true, false, true, false, false, true,  // 24
            true, false, false, true, false, true, true, false,  // 32
            false, true, true, false, true, false, false, true,  // 40
            false, true, true, false, true, false, false, true,  // 48
            true, false, false, true, false, true, true, false,  // 56
            true, false, false, true, false, true, true, false,  // 64
            false, true, true, false, true, false, false, true,  // 72
            false, true, true, false, true, false, false, true,  // 80
            true, false, false, true, false, true, true, false,  // 88
            false, true, true, false, true, false, false, true,  // 96
            true, false, false, true, false, true, true, false,  // 104
            true, false, false, true, false, true, true, false,  // 112
            false, true, true, false, true, false, false, true,  // 120
            true, false, false, true, false, true, true, false,  // 128
            false, true, true, false, true, false, false, true,  // 136
            false, true, true, false, true, false, false, true,  // 144
            true, false, false, true, false, true, true, false,  // 152
            false, true, true, false, true, false, false, true,  // 160
            true, false, false, true, false, true, true, false,  // 168
            true, false, false, true, false, true, true, false,  // 176
            false, true, true, false, true, false, false, true,  // 184
            false, true, true, false, true, false, false, true,  // 192
            true, false, false, true, false, true, true, false,  // 200
            true, false, false, true, false, true, true, false,  // 208
            false, true, true, false, true, false, false, true,  // 216
            true, false, false, true, false, true, true, false,  // 224
            false, true, true, false, true, false, false, true,  // 232
            false, true, true, false, true, false, false, true,  // 240
            true, false, false, true, false, true, true, false,  // 248
        )

        private fun getBasicChar(ccData: Byte): Char {
            val index = (ccData.toInt() and 0x7F) - 0x20
            return BASIC_CHARACTER_SET[index].toChar()
        }

        private fun isSpecialNorthAmericanChar(cc1: Byte, cc2: Byte): Boolean {
            // cc1 - 0|0|0|1|C|0|0|1
            // cc2 - 0|0|1|1|X|X|X|X
            return ((cc1.toInt() and 0xF7) == 0x11) && ((cc2.toInt() and 0xF0) == 0x30)
        }

        private fun getSpecialNorthAmericanChar(ccData: Byte): Char {
            val index = ccData.toInt() and 0x0F
            return SPECIAL_CHARACTER_SET[index].toChar()
        }

        private fun isExtendedWestEuropeanChar(cc1: Byte, cc2: Byte): Boolean {
            // cc1 - 0|0|0|1|C|0|1|S
            // cc2 - 0|0|1|X|X|X|X|X
            return ((cc1.toInt() and 0xF6) == 0x12) && ((cc2.toInt() and 0xE0) == 0x20)
        }

        private fun getExtendedWestEuropeanChar(cc1: Byte, cc2: Byte): Char {
            if ((cc1.toInt() and 0x01) == 0x00) {
                // Extended Spanish/Miscellaneous and French character set (S = 0).
                return getExtendedEsFrChar(cc2)
            } else {
                // Extended Portuguese and German/Danish character set (S = 1).
                return getExtendedPtDeChar(cc2)
            }
        }

        private fun getExtendedEsFrChar(ccData: Byte): Char {
            val index = ccData.toInt() and 0x1F
            return SPECIAL_ES_FR_CHARACTER_SET[index].toChar()
        }

        private fun getExtendedPtDeChar(ccData: Byte): Char {
            val index = ccData.toInt() and 0x1F
            return SPECIAL_PT_DE_CHARACTER_SET[index].toChar()
        }

        private fun isCtrlCode(cc1: Byte): Boolean {
            // cc1 - 0|0|0|X|X|X|X|X
            return (cc1.toInt() and 0xE0) == 0x00
        }

        private fun getChannel(cc1: Byte): Int {
            // cc1 - X|X|X|X|C|X|X|X
            return (cc1.toInt() shr 3) and 0x1
        }

        private fun isMidrowCtrlCode(cc1: Byte, cc2: Byte): Boolean {
            // cc1 - 0|0|0|1|C|0|0|1
            // cc2 - 0|0|1|0|X|X|X|X
            return ((cc1.toInt() and 0xF7) == 0x11) && ((cc2.toInt() and 0xF0) == 0x20)
        }

        private fun isPreambleAddressCode(cc1: Byte, cc2: Byte): Boolean {
            // cc1 - 0|0|0|1|C|X|X|X
            // cc2 - 0|1|X|X|X|X|X|X
            return ((cc1.toInt() and 0xF0) == 0x10) && ((cc2.toInt() and 0xC0) == 0x40)
        }

        private fun isTabCtrlCode(cc1: Byte, cc2: Byte): Boolean {
            // cc1 - 0|0|0|1|C|1|1|1
            // cc2 - 0|0|1|0|0|0|0|1 to 0|0|1|0|0|0|1|1
            return ((cc1.toInt() and 0xF7) == 0x17) && (cc2 >= 0x21 && cc2 <= 0x23)
        }

        private fun isMiscCode(cc1: Byte, cc2: Byte): Boolean {
            // cc1 - 0|0|0|1|C|1|0|F
            // cc2 - 0|0|1|0|X|X|X|X
            return ((cc1.toInt() and 0xF6) == 0x14) && ((cc2.toInt() and 0xF0) == 0x20)
        }

        private fun isRepeatable(cc1: Byte): Boolean {
            // cc1 - 0|0|0|1|X|X|X|X
            return (cc1.toInt() and 0xF0) == 0x10
        }

        private fun isXdsControlCode(cc1: Byte): Boolean {
            return 0x01 <= cc1 && cc1 <= 0x0F
        }

        private fun isServiceSwitchCommand(cc1: Byte): Boolean {
            // cc1 - 0|0|0|1|C|1|0|F
            return (cc1.toInt() and 0xF6) == 0x14
        }
    }
}
