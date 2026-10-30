
package androidx.media3.extractor.text.cea

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.text.Cue.AnchorType
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.CodecSpecificDataUtil.parseCea708InitializationData
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleInputBuffer
import com.google.common.base.Preconditions
import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.nio.ByteBuffer
import java.util.Collections

class Cea708Decoder(accessibilityChannel: Int, initializationData: List<ByteArray>?) :
    CeaDecoder() {
    private val ccData: ParsableByteArray
    private val captionChannelPacketData: ParsableBitArray
    private var previousSequenceNumber: Int

    // TODO: Use isWideAspectRatio in decoding.
    @Suppress("unused")
    private val isWideAspectRatio: Boolean

    private val selectedServiceNumber: Int
    private val cueInfoBuilders: Array<CueInfoBuilder>

    private var currentCueInfoBuilder: CueInfoBuilder
    private var cues: MutableList<Cue>? = null
    private var lastCues: MutableList<Cue>? = null

    private var currentDtvCcPacket: DtvCcPacket? = null
    private var currentWindow = 0

    init {
        ccData = ParsableByteArray()
        captionChannelPacketData = ParsableBitArray()
        previousSequenceNumber = C.INDEX_UNSET
        selectedServiceNumber =
            if (accessibilityChannel == Format.NO_VALUE) 1 else accessibilityChannel
        isWideAspectRatio =
            initializationData != null
                    && parseCea708InitializationData(initializationData)

        val list = mutableListOf<CueInfoBuilder>()
        for (i in 0..<NUM_WINDOWS) {
            list.add(CueInfoBuilder())
        }
        cueInfoBuilders = list.toTypedArray()
        currentCueInfoBuilder = cueInfoBuilders[0]
    }

    override val isNewSubtitleDataAvailable: Boolean
        get() = cues !== lastCues
    override val name: String
        get() = "Cea708Decoder"

    override fun flush() {
        super.flush()
        cues = null
        lastCues = null
        currentWindow = 0
        currentCueInfoBuilder = cueInfoBuilders[currentWindow]
        resetCueBuilders()
        currentDtvCcPacket = null
    }

    override fun createSubtitle(): Subtitle {
        lastCues = cues
        return CeaSubtitle(cues)
    }

    override fun decode(inputBuffer: SubtitleInputBuffer) {
        // Subtitle input buffers are non-direct and the position is zero, so calling array() is safe.
        val subtitleData = inputBuffer.data!!
        val inputBufferData = subtitleData.array()
        ccData.reset(inputBufferData, subtitleData.limit())
        while (ccData.bytesLeft() >= 3) {
            val ccTypeAndValid = (ccData.readUnsignedByte() and 0x07)

            val ccType = ccTypeAndValid and (DTVCC_PACKET_DATA or DTVCC_PACKET_START)
            val ccValid = (ccTypeAndValid and CC_VALID_FLAG) == CC_VALID_FLAG
            val ccData1 = ccData.readUnsignedByte().toByte()
            val ccData2 = ccData.readUnsignedByte().toByte()

            // Ignore any non-CEA-708 data
            if (ccType != DTVCC_PACKET_DATA && ccType != DTVCC_PACKET_START) {
                continue
            }

            if (!ccValid) {
                // This byte-pair isn't valid, ignore it and continue.
                continue
            }

            if (ccType == DTVCC_PACKET_START) {
                finalizeCurrentPacket()

                val sequenceNumber = (ccData1.toInt() and 0xC0) shr 6 // first 2 bits
                if (previousSequenceNumber != C.INDEX_UNSET
                    && sequenceNumber != (previousSequenceNumber + 1) % 4
                ) {
                    resetCueBuilders()
                    w(
                        TAG,
                        ("Sequence number discontinuity. previous="
                                + previousSequenceNumber
                                + " current="
                                + sequenceNumber)
                    )
                }
                previousSequenceNumber = sequenceNumber

                var packetSize = ccData1.toInt() and 0x3F // last 6 bits
                if (packetSize == 0) {
                    packetSize = 64
                }

                currentDtvCcPacket = DtvCcPacket(sequenceNumber, packetSize)
                currentDtvCcPacket!!.packetData[currentDtvCcPacket!!.currentIndex++] = ccData2
            } else {
                // The only remaining valid packet type is DTVCC_PACKET_DATA
                Preconditions.checkArgument(ccType == DTVCC_PACKET_DATA)

                if (currentDtvCcPacket == null) {
                    e(TAG, "Encountered DTVCC_PACKET_DATA before DTVCC_PACKET_START")
                    continue
                }

                currentDtvCcPacket!!.packetData[currentDtvCcPacket!!.currentIndex++] = ccData1
                currentDtvCcPacket!!.packetData[currentDtvCcPacket!!.currentIndex++] = ccData2
            }

            if (currentDtvCcPacket!!.currentIndex == (currentDtvCcPacket!!.packetSize * 2 - 1)) {
                finalizeCurrentPacket()
            }
        }
    }

    private fun finalizeCurrentPacket() {
        if (currentDtvCcPacket == null) {
            // No packet to finalize;
            return
        }

        processCurrentPacket()
        currentDtvCcPacket = null
    }

    @RequiresNonNull("currentDtvCcPacket")
    private fun processCurrentPacket() {
        if (currentDtvCcPacket!!.currentIndex != (currentDtvCcPacket!!.packetSize * 2 - 1)) {
            d(
                TAG,
                ("DtvCcPacket ended prematurely; size is "
                        + (currentDtvCcPacket!!.packetSize * 2 - 1)
                        + ", but current index is "
                        + currentDtvCcPacket!!.currentIndex
                        + " (sequence number "
                        + currentDtvCcPacket!!.sequenceNumber
                        + ");")
            )
            // We've received cc_type=0x03 (packet start) before receiving packetSize byte pairs of data.
            // This might indicate a byte pair has been lost, but we'll still attempt to process the data
            // we have received.
        }

        // The cues should be updated if we receive a C0 ETX command, any C1 command, or if after
        // processing the service block any text has been added to the buffer. See CEA-708-B Section
        // 8.10.4 for more details.
        var cuesNeedUpdate = false

        // Streams with multiple embedded CC tracks (different language tracks) can be delivered
        // in the same frame packet, so captionChannelPacketData can contain service blocks with
        // different service numbers.
        //
        // We iterate over the full buffer until we find a null service block or until the buffer is
        // exhausted. On each iteration we process a single service block. If the block has a service
        // number different to the currently selected service, then we skip it and continue with the
        // next service block.
        captionChannelPacketData.reset(
            currentDtvCcPacket!!.packetData,
            currentDtvCcPacket!!.currentIndex
        )
        while (captionChannelPacketData.bitsLeft() > 0) {
            // Parse the Standard Service Block Header (see CEA-708B 6.2.1)
            var serviceNumber = captionChannelPacketData.readBits(3)
            val blockSize = captionChannelPacketData.readBits(5)
            if (serviceNumber == 7) {
                // Parse the Extended Service Block Header (see CEA-708B 6.2.2)
                captionChannelPacketData.skipBits(2)
                serviceNumber = captionChannelPacketData.readBits(6)
                if (serviceNumber < 7) {
                    w(TAG, "Invalid extended service number: " + serviceNumber)
                }
            }

            // Ignore packets with the Null Service Block Header (see CEA-708B 6.2.3)
            if (blockSize == 0) {
                if (serviceNumber != 0) {
                    w(TAG, "serviceNumber is non-zero (" + serviceNumber + ") when blockSize is 0")
                }
                break
            }

            if (serviceNumber != selectedServiceNumber) {
                captionChannelPacketData.skipBytes(blockSize)
                continue
            }

            // Process only the information for the current service block (there could be
            // more data in the buffer, but it is not part of the current service block).
            val endBlockPosition = captionChannelPacketData.position + (blockSize * 8)
            while (captionChannelPacketData.position < endBlockPosition) {
                var command = captionChannelPacketData.readBits(8)
                if (command != COMMAND_EXT1) {
                    if (command <= GROUP_C0_END) {
                        handleC0Command(command)
                        // If the C0 command was an ETX command, the cues are updated in handleC0Command.
                    } else if (command <= GROUP_G0_END) {
                        handleG0Character(command)
                        cuesNeedUpdate = true
                    } else if (command <= GROUP_C1_END) {
                        handleC1Command(command)
                        cuesNeedUpdate = true
                    } else if (command <= GROUP_G1_END) {
                        handleG1Character(command)
                        cuesNeedUpdate = true
                    } else {
                        w(TAG, "Invalid base command: " + command)
                    }
                } else {
                    // Read the extended command
                    command = captionChannelPacketData.readBits(8)
                    if (command <= GROUP_C2_END) {
                        handleC2Command(command)
                    } else if (command <= GROUP_G2_END) {
                        handleG2Character(command)
                        cuesNeedUpdate = true
                    } else if (command <= GROUP_C3_END) {
                        handleC3Command(command)
                    } else if (command <= GROUP_G3_END) {
                        handleG3Character(command)
                        cuesNeedUpdate = true
                    } else {
                        w(TAG, "Invalid extended command: " + command)
                    }
                }
            }
        }

        if (cuesNeedUpdate) {
            cues = this.displayCues
        }
    }

    private fun handleC0Command(command: Int) {
        when (command) {
            COMMAND_NUL -> {}
            COMMAND_ETX -> cues = this.displayCues
            COMMAND_BS -> currentCueInfoBuilder.backspace()
            COMMAND_FF -> resetCueBuilders()
            COMMAND_CR -> currentCueInfoBuilder.append('\n')
            COMMAND_HCR -> {}
            else -> if (command >= COMMAND_EXT1_START && command <= COMMAND_EXT1_END) {
                w(TAG, "Currently unsupported COMMAND_EXT1 Command: " + command)
                captionChannelPacketData.skipBits(8)
            } else if (command >= COMMAND_P16_START && command <= COMMAND_P16_END) {
                w(TAG, "Currently unsupported COMMAND_P16 Command: " + command)
                captionChannelPacketData.skipBits(16)
            } else {
                w(TAG, "Invalid C0 command: " + command)
            }
        }
    }

    private fun handleC1Command(command: Int) {
        val window: Int
        when (command) {
            COMMAND_CW0, COMMAND_CW1, COMMAND_CW2, COMMAND_CW3, COMMAND_CW4, COMMAND_CW5, COMMAND_CW6, COMMAND_CW7 -> {
                window = (command - COMMAND_CW0)
                if (currentWindow != window) {
                    currentWindow = window
                    currentCueInfoBuilder = cueInfoBuilders[window]
                }
            }

            COMMAND_CLW -> {
                var i = 1
                while (i <= NUM_WINDOWS) {
                    if (captionChannelPacketData.readBit()) {
                        cueInfoBuilders[NUM_WINDOWS - i].clear()
                    }
                    i++
                }
            }

            COMMAND_DSW -> {
                var i = 1
                while (i <= NUM_WINDOWS) {
                    if (captionChannelPacketData.readBit()) {
                        cueInfoBuilders[NUM_WINDOWS - i].setVisibility(true)
                    }
                    i++
                }
            }

            COMMAND_HDW -> {
                var i = 1
                while (i <= NUM_WINDOWS) {
                    if (captionChannelPacketData.readBit()) {
                        cueInfoBuilders[NUM_WINDOWS - i].setVisibility(false)
                    }
                    i++
                }
            }

            COMMAND_TGW -> {
                var i = 1
                while (i <= NUM_WINDOWS) {
                    if (captionChannelPacketData.readBit()) {
                        val cueInfoBuilder = cueInfoBuilders[NUM_WINDOWS - i]
                        cueInfoBuilder.setVisibility(!cueInfoBuilder.isVisible)
                    }
                    i++
                }
            }

            COMMAND_DLW -> {
                var i = 1
                while (i <= NUM_WINDOWS) {
                    if (captionChannelPacketData.readBit()) {
                        cueInfoBuilders[NUM_WINDOWS - i].reset()
                    }
                    i++
                }
            }

            COMMAND_DLY ->         // TODO: Add support for delay commands.
                captionChannelPacketData.skipBits(8)

            COMMAND_DLC -> {}
            COMMAND_RST -> resetCueBuilders()
            COMMAND_SPA -> if (!currentCueInfoBuilder.isDefined) {
                // ignore this command if the current window/cue isn't defined
                captionChannelPacketData.skipBits(16)
            } else {
                handleSetPenAttributes()
            }

            COMMAND_SPC -> if (!currentCueInfoBuilder.isDefined) {
                // ignore this command if the current window/cue isn't defined
                captionChannelPacketData.skipBits(24)
            } else {
                handleSetPenColor()
            }

            COMMAND_SPL -> if (!currentCueInfoBuilder.isDefined) {
                // ignore this command if the current window/cue isn't defined
                captionChannelPacketData.skipBits(16)
            } else {
                handleSetPenLocation()
            }

            COMMAND_SWA -> if (!currentCueInfoBuilder.isDefined) {
                // ignore this command if the current window/cue isn't defined
                captionChannelPacketData.skipBits(32)
            } else {
                handleSetWindowAttributes()
            }

            COMMAND_DF0, COMMAND_DF1, COMMAND_DF2, COMMAND_DF3, COMMAND_DF4, COMMAND_DF5, COMMAND_DF6, COMMAND_DF7 -> {
                window = (command - COMMAND_DF0)
                handleDefineWindow(window)
                // We also set the current window to the newly defined window.
                if (currentWindow != window) {
                    currentWindow = window
                    currentCueInfoBuilder = cueInfoBuilders[window]
                }
            }

            else -> w(TAG, "Invalid C1 command: " + command)
        }
    }

    private fun handleC2Command(command: Int) {
        // C2 Table doesn't contain any commands in CEA-708-B, but we do need to skip bytes
        if (command <= 0x07) {
            // Do nothing.
        } else if (command <= 0x0F) {
            captionChannelPacketData.skipBits(8)
        } else if (command <= 0x17) {
            captionChannelPacketData.skipBits(16)
        } else if (command <= 0x1F) {
            captionChannelPacketData.skipBits(24)
        }
    }

    private fun handleC3Command(command: Int) {
        // C3 Table doesn't contain any commands in CEA-708-B, but we do need to skip bytes
        if (command <= 0x87) {
            captionChannelPacketData.skipBits(32)
        } else if (command <= 0x8F) {
            captionChannelPacketData.skipBits(40)
        } else if (command <= 0x9F) {
            // 90-9F are variable length codes; the first byte defines the header with the first
            // 2 bits specifying the type and the last 6 bits specifying the remaining length of the
            // command in bytes
            captionChannelPacketData.skipBits(2)
            val length = captionChannelPacketData.readBits(6)
            captionChannelPacketData.skipBits(8 * length)
        }
    }

    private fun handleG0Character(characterCode: Int) {
        if (characterCode == CHARACTER_MN) {
            currentCueInfoBuilder.append('\u266B')
        } else {
            currentCueInfoBuilder.append((characterCode and 0xFF).toChar())
        }
    }

    private fun handleG1Character(characterCode: Int) {
        currentCueInfoBuilder.append((characterCode and 0xFF).toChar())
    }

    private fun handleG2Character(characterCode: Int) {
        when (characterCode) {
            CHARACTER_TSP -> currentCueInfoBuilder.append('\u0020')
            CHARACTER_NBTSP -> currentCueInfoBuilder.append('\u00A0')
            CHARACTER_ELLIPSIS -> currentCueInfoBuilder.append('\u2026')
            CHARACTER_BIG_CARONS -> currentCueInfoBuilder.append('\u0160')
            CHARACTER_BIG_OE -> currentCueInfoBuilder.append('\u0152')
            CHARACTER_SOLID_BLOCK -> currentCueInfoBuilder.append('\u2588')
            CHARACTER_OPEN_SINGLE_QUOTE -> currentCueInfoBuilder.append('\u2018')
            CHARACTER_CLOSE_SINGLE_QUOTE -> currentCueInfoBuilder.append('\u2019')
            CHARACTER_OPEN_DOUBLE_QUOTE -> currentCueInfoBuilder.append('\u201C')
            CHARACTER_CLOSE_DOUBLE_QUOTE -> currentCueInfoBuilder.append('\u201D')
            CHARACTER_BOLD_BULLET -> currentCueInfoBuilder.append('\u2022')
            CHARACTER_TM -> currentCueInfoBuilder.append('\u2122')
            CHARACTER_SMALL_CARONS -> currentCueInfoBuilder.append('\u0161')
            CHARACTER_SMALL_OE -> currentCueInfoBuilder.append('\u0153')
            CHARACTER_SM -> currentCueInfoBuilder.append('\u2120')
            CHARACTER_DIAERESIS_Y -> currentCueInfoBuilder.append('\u0178')
            CHARACTER_ONE_EIGHTH -> currentCueInfoBuilder.append('\u215B')
            CHARACTER_THREE_EIGHTHS -> currentCueInfoBuilder.append('\u215C')
            CHARACTER_FIVE_EIGHTHS -> currentCueInfoBuilder.append('\u215D')
            CHARACTER_SEVEN_EIGHTHS -> currentCueInfoBuilder.append('\u215E')
            CHARACTER_VERTICAL_BORDER -> currentCueInfoBuilder.append('\u2502')
            CHARACTER_UPPER_RIGHT_BORDER -> currentCueInfoBuilder.append('\u2510')
            CHARACTER_LOWER_LEFT_BORDER -> currentCueInfoBuilder.append('\u2514')
            CHARACTER_HORIZONTAL_BORDER -> currentCueInfoBuilder.append('\u2500')
            CHARACTER_LOWER_RIGHT_BORDER -> currentCueInfoBuilder.append('\u2518')
            CHARACTER_UPPER_LEFT_BORDER -> currentCueInfoBuilder.append('\u250C')
            else -> w(TAG, "Invalid G2 character: " + characterCode)
        }
    }

    private fun handleG3Character(characterCode: Int) {
        if (characterCode == 0xA0) {
            currentCueInfoBuilder.append('\u33C4')
        } else {
            w(TAG, "Invalid G3 character: " + characterCode)
            // Substitute any unsupported G3 character with an underscore as per CEA-708 specification.
            currentCueInfoBuilder.append('_')
        }
    }

    private fun handleSetPenAttributes() {
        // the SetPenAttributes command contains 2 bytes of data
        // first byte
        val textTag = captionChannelPacketData.readBits(4)
        val offset = captionChannelPacketData.readBits(2)
        val penSize = captionChannelPacketData.readBits(2)
        // second byte
        val italicsToggle = captionChannelPacketData.readBit()
        val underlineToggle = captionChannelPacketData.readBit()
        val edgeType = captionChannelPacketData.readBits(3)
        val fontStyle = captionChannelPacketData.readBits(3)

        currentCueInfoBuilder.setPenAttributes(
            textTag, offset, penSize, italicsToggle, underlineToggle, edgeType, fontStyle
        )
    }

    private fun handleSetPenColor() {
        // the SetPenColor command contains 3 bytes of data
        // first byte
        val foregroundO = captionChannelPacketData.readBits(2)
        val foregroundR = captionChannelPacketData.readBits(2)
        val foregroundG = captionChannelPacketData.readBits(2)
        val foregroundB = captionChannelPacketData.readBits(2)
        val foregroundColor =
            CueInfoBuilder.getArgbColorFromCeaColor(
                foregroundR,
                foregroundG,
                foregroundB,
                foregroundO
            )
        // second byte
        val backgroundO = captionChannelPacketData.readBits(2)
        val backgroundR = captionChannelPacketData.readBits(2)
        val backgroundG = captionChannelPacketData.readBits(2)
        val backgroundB = captionChannelPacketData.readBits(2)
        val backgroundColor =
            CueInfoBuilder.getArgbColorFromCeaColor(
                backgroundR,
                backgroundG,
                backgroundB,
                backgroundO
            )
        // third byte
        captionChannelPacketData.skipBits(2) // null padding
        val edgeR = captionChannelPacketData.readBits(2)
        val edgeG = captionChannelPacketData.readBits(2)
        val edgeB = captionChannelPacketData.readBits(2)
        val edgeColor = CueInfoBuilder.getArgbColorFromCeaColor(edgeR, edgeG, edgeB)

        currentCueInfoBuilder.setPenColor(foregroundColor, backgroundColor, edgeColor)
    }

    private fun handleSetPenLocation() {
        // the SetPenLocation command contains 2 bytes of data
        // first byte
        captionChannelPacketData.skipBits(4)
        val row = captionChannelPacketData.readBits(4)
        // second byte
        captionChannelPacketData.skipBits(2)
        val column = captionChannelPacketData.readBits(6)

        currentCueInfoBuilder.setPenLocation(row, column)
    }

    private fun handleSetWindowAttributes() {
        // the SetWindowAttributes command contains 4 bytes of data
        // first byte
        val fillO = captionChannelPacketData.readBits(2)
        val fillR = captionChannelPacketData.readBits(2)
        val fillG = captionChannelPacketData.readBits(2)
        val fillB = captionChannelPacketData.readBits(2)
        val fillColor = CueInfoBuilder.getArgbColorFromCeaColor(fillR, fillG, fillB, fillO)
        // second byte
        var borderType = captionChannelPacketData.readBits(2) // only the lower 2 bits of borderType
        val borderR = captionChannelPacketData.readBits(2)
        val borderG = captionChannelPacketData.readBits(2)
        val borderB = captionChannelPacketData.readBits(2)
        val borderColor = CueInfoBuilder.getArgbColorFromCeaColor(borderR, borderG, borderB)
        // third byte
        if (captionChannelPacketData.readBit()) {
            borderType = borderType or 0x04 // set the top bit of the 3-bit borderType
        }
        val wordWrapToggle = captionChannelPacketData.readBit()
        val printDirection = captionChannelPacketData.readBits(2)
        val scrollDirection = captionChannelPacketData.readBits(2)
        val justification = captionChannelPacketData.readBits(2)
        // fourth byte
        // Note that we don't intend to support display effects
        captionChannelPacketData.skipBits(8) // effectSpeed(4), effectDirection(2), displayEffect(2)

        currentCueInfoBuilder.setWindowAttributes(
            fillColor,
            borderColor,
            wordWrapToggle,
            borderType,
            printDirection,
            scrollDirection,
            justification
        )
    }

    private fun handleDefineWindow(window: Int) {
        val cueInfoBuilder = cueInfoBuilders[window]

        // the DefineWindow command contains 6 bytes of data
        // first byte
        captionChannelPacketData.skipBits(2) // null padding
        val visible = captionChannelPacketData.readBit()
        // ANSI/CTA-708-E S-2023 spec (Section 8.4.7) indicates that rowLock and columnLock values in
        // the media should be ignored and assumed to be true.
        captionChannelPacketData.skipBits(2)
        val priority = captionChannelPacketData.readBits(3)
        // second byte
        val relativePositioning = captionChannelPacketData.readBit()
        val verticalAnchor = captionChannelPacketData.readBits(7)
        // third byte
        val horizontalAnchor = captionChannelPacketData.readBits(8)
        // fourth byte
        val anchorId = captionChannelPacketData.readBits(4)
        val rowCount = captionChannelPacketData.readBits(4)
        // fifth byte
        captionChannelPacketData.skipBits(2) // null padding
        // TODO: Add support for column count.
        captionChannelPacketData.skipBits(6) // column count
        // sixth byte
        captionChannelPacketData.skipBits(2) // null padding
        val windowStyle = captionChannelPacketData.readBits(3)
        val penStyle = captionChannelPacketData.readBits(3)

        cueInfoBuilder.defineWindow(
            visible,
            priority,
            relativePositioning,
            verticalAnchor,
            horizontalAnchor,
            rowCount,
            anchorId,
            windowStyle,
            penStyle
        )
    }

    private val displayCues: MutableList<Cue>
        get() {
            val displayCueInfos: MutableList<Cea708CueInfo?> =
                ArrayList<Cea708CueInfo?>()
            for (i in 0..<NUM_WINDOWS) {
                if (!cueInfoBuilders[i].isEmpty && cueInfoBuilders[i].isVisible) {
                    val cueInfo = cueInfoBuilders[i].build()
                    if (cueInfo != null) {
                        displayCueInfos.add(cueInfo)
                    }
                }
            }
            Collections.sort<Cea708CueInfo?>(
                displayCueInfos,
                Cea708CueInfo.LEAST_IMPORTANT_FIRST
            )
            val displayCues: MutableList<Cue?> =
                ArrayList<Cue?>(displayCueInfos.size)
            for (i in displayCueInfos.indices) {
                displayCues.add(displayCueInfos[i]!!.cue)
            }
            return Collections.unmodifiableList<Cue?>(displayCues)
        }

    private fun resetCueBuilders() {
        for (i in 0..<NUM_WINDOWS) {
            cueInfoBuilders[i].reset()
        }
    }

    private class DtvCcPacket(val sequenceNumber: Int, val packetSize: Int) {
        val packetData: ByteArray

        var currentIndex: Int = 0

        init {
            packetData = ByteArray(2 * packetSize - 1)
        }
    }

    // TODO: There is a lot of overlap between Cea708Decoder.CueInfoBuilder and
    // Cea608Decoder.CueBuilder which could be refactored into a separate class.
    private class CueInfoBuilder {
        private val rolledUpCaptions: MutableList<SpannableString?>
        private val captionStringBuilder: SpannableStringBuilder

        // Window/Cue properties
        var isDefined: Boolean = false
            private set
        var isVisible: Boolean = false
            private set
        private var priority = 0
        private var relativePositioning = false
        private var verticalAnchor = 0
        private var horizontalAnchor = 0
        private var anchorId = 0
        private var rowCount = 0
        private var justification = 0
        private var windowStyleId = 0
        private var penStyleId = 0
        private var windowFillColor = 0

        // Pen/Text properties
        private var italicsStartPosition = 0
        private var underlineStartPosition = 0
        private var foregroundColorStartPosition = 0
        private var foregroundColor = 0
        private var backgroundColorStartPosition = 0
        private var backgroundColor = 0
        private var row = 0

        init {
            rolledUpCaptions = ArrayList<SpannableString?>()
            captionStringBuilder = SpannableStringBuilder()
            reset()
        }

        val isEmpty: Boolean
            get() = !this.isDefined || (rolledUpCaptions.isEmpty() && captionStringBuilder.length == 0)

        fun reset() {
            clear()

            this.isDefined = false
            this.isVisible = false
            priority = DEFAULT_PRIORITY
            relativePositioning = false
            verticalAnchor = 0
            horizontalAnchor = 0
            anchorId = 0
            rowCount = MAXIMUM_ROW_COUNT
            justification = JUSTIFICATION_LEFT
            windowStyleId = 0
            penStyleId = 0
            windowFillColor = COLOR_SOLID_BLACK

            foregroundColor = COLOR_SOLID_WHITE
            backgroundColor = COLOR_SOLID_BLACK
        }

        fun clear() {
            rolledUpCaptions.clear()
            captionStringBuilder.clear()
            italicsStartPosition = C.INDEX_UNSET
            underlineStartPosition = C.INDEX_UNSET
            foregroundColorStartPosition = C.INDEX_UNSET
            backgroundColorStartPosition = C.INDEX_UNSET
            row = 0
        }

        fun setVisibility(visible: Boolean) {
            this.isVisible = visible
        }

        fun defineWindow(
            visible: Boolean,
            priority: Int,
            relativePositioning: Boolean,
            verticalAnchor: Int,
            horizontalAnchor: Int,
            rowCount: Int,
            anchorId: Int,
            windowStyleId: Int,
            penStyleId: Int
        ) {
            this.isDefined = true
            this.isVisible = visible
            this.priority = priority
            this.relativePositioning = relativePositioning
            this.verticalAnchor = verticalAnchor
            this.horizontalAnchor = horizontalAnchor
            this.anchorId = anchorId

            // Decoders must add one to rowCount to get the desired number of rows.
            if (this.rowCount != rowCount + 1) {
                this.rowCount = rowCount + 1

                // Trim any rolled up captions that are no longer valid, if applicable.
                while (rolledUpCaptions.size >= this.rowCount
                    || rolledUpCaptions.size >= MAXIMUM_ROW_COUNT
                ) {
                    rolledUpCaptions.removeAt(0)
                }
            }

            if (windowStyleId != 0 && this.windowStyleId != windowStyleId) {
                this.windowStyleId = windowStyleId
                // windowStyleId is 1-based.
                val windowStyleIdIndex = windowStyleId - 1
                // Note that Border type and border color are the same for all window styles.
                setWindowAttributes(
                    WINDOW_STYLE_FILL[windowStyleIdIndex],
                    COLOR_TRANSPARENT,
                    WINDOW_STYLE_WORD_WRAP[windowStyleIdIndex],
                    BORDER_AND_EDGE_TYPE_NONE,
                    WINDOW_STYLE_PRINT_DIRECTION[windowStyleIdIndex],
                    WINDOW_STYLE_SCROLL_DIRECTION[windowStyleIdIndex],
                    WINDOW_STYLE_JUSTIFICATION[windowStyleIdIndex]
                )
            }

            if (penStyleId != 0 && this.penStyleId != penStyleId) {
                this.penStyleId = penStyleId
                // penStyleId is 1-based.
                val penStyleIdIndex = penStyleId - 1
                // Note that pen size, offset, italics, underline, foreground color, and foreground
                // opacity are the same for all pen styles.
                setPenAttributes(
                    0,
                    PEN_OFFSET_NORMAL,
                    PEN_SIZE_STANDARD,
                    false,
                    false,
                    PEN_STYLE_EDGE_TYPE[penStyleIdIndex],
                    PEN_STYLE_FONT_STYLE[penStyleIdIndex]
                )
                setPenColor(
                    COLOR_SOLID_WHITE,
                    PEN_STYLE_BACKGROUND[penStyleIdIndex],
                    COLOR_SOLID_BLACK
                )
            }
        }

        fun setWindowAttributes(
            fillColor: Int,
            borderColor: Int,
            wordWrapToggle: Boolean,
            borderType: Int,
            printDirection: Int,
            scrollDirection: Int,
            justification: Int
        ) {
            this.windowFillColor = fillColor
            // TODO: Add support for border color and types.
            // TODO: Add support for word wrap.
            // TODO: Add support for other scroll directions.
            // TODO: Add support for other print directions.
            this.justification = justification
        }

        fun setPenAttributes(
            textTag: Int,
            offset: Int,
            penSize: Int,
            italicsToggle: Boolean,
            underlineToggle: Boolean,
            edgeType: Int,
            fontStyle: Int
        ) {
            // TODO: Add support for text tags.
            // TODO: Add support for other offsets.
            // TODO: Add support for other pen sizes.

            if (italicsStartPosition != C.INDEX_UNSET) {
                if (!italicsToggle) {
                    captionStringBuilder.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        italicsStartPosition,
                        captionStringBuilder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    italicsStartPosition = C.INDEX_UNSET
                }
            } else if (italicsToggle) {
                italicsStartPosition = captionStringBuilder.length
            }

            if (underlineStartPosition != C.INDEX_UNSET) {
                if (!underlineToggle) {
                    captionStringBuilder.setSpan(
                        UnderlineSpan(),
                        underlineStartPosition,
                        captionStringBuilder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    underlineStartPosition = C.INDEX_UNSET
                }
            } else if (underlineToggle) {
                underlineStartPosition = captionStringBuilder.length
            }

            // TODO: Add support for edge types.
            // TODO: Add support for other font styles.
        }

        fun setPenColor(foregroundColor: Int, backgroundColor: Int, edgeColor: Int) {
            if (foregroundColorStartPosition != C.INDEX_UNSET) {
                if (this.foregroundColor != foregroundColor) {
                    captionStringBuilder.setSpan(
                        ForegroundColorSpan(this.foregroundColor),
                        foregroundColorStartPosition,
                        captionStringBuilder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            if (foregroundColor != COLOR_SOLID_WHITE) {
                foregroundColorStartPosition = captionStringBuilder.length
                this.foregroundColor = foregroundColor
            }

            if (backgroundColorStartPosition != C.INDEX_UNSET) {
                if (this.backgroundColor != backgroundColor) {
                    captionStringBuilder.setSpan(
                        BackgroundColorSpan(this.backgroundColor),
                        backgroundColorStartPosition,
                        captionStringBuilder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            if (backgroundColor != COLOR_SOLID_BLACK) {
                backgroundColorStartPosition = captionStringBuilder.length
                this.backgroundColor = backgroundColor
            }

            // TODO: Add support for edge color.
        }

        fun setPenLocation(row: Int, column: Int) {
            // TODO: Support moving the pen location with a window properly.

            // Until we support proper pen locations, if we encounter a row that's different from the
            // previous one, we should append a new line. Otherwise, we'll see strings that should be
            // on new lines concatenated with the previous, resulting in 2 words being combined, as
            // well as potentially drawing beyond the width of the window/screen.

            if (this.row != row) {
                append('\n')
            }
            this.row = row
        }

        fun backspace() {
            val length = captionStringBuilder.length
            if (length > 0) {
                captionStringBuilder.delete(length - 1, length)
            }
        }

        fun append(text: Char) {
            if (text == '\n') {
                rolledUpCaptions.add(buildSpannableString())
                captionStringBuilder.clear()

                if (italicsStartPosition != C.INDEX_UNSET) {
                    italicsStartPosition = 0
                }
                if (underlineStartPosition != C.INDEX_UNSET) {
                    underlineStartPosition = 0
                }
                if (foregroundColorStartPosition != C.INDEX_UNSET) {
                    foregroundColorStartPosition = 0
                }
                if (backgroundColorStartPosition != C.INDEX_UNSET) {
                    backgroundColorStartPosition = 0
                }

                while (rolledUpCaptions.size >= rowCount
                    || rolledUpCaptions.size >= MAXIMUM_ROW_COUNT
                ) {
                    rolledUpCaptions.removeAt(0)
                }
                // update row value after newline
                row = rolledUpCaptions.size
            } else {
                captionStringBuilder.append(text)
            }
        }

        fun buildSpannableString(): SpannableString {
            val spannableStringBuilder =
                SpannableStringBuilder(captionStringBuilder)
            val length = spannableStringBuilder.length

            if (length > 0) {
                if (italicsStartPosition != C.INDEX_UNSET) {
                    spannableStringBuilder.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        italicsStartPosition,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                if (underlineStartPosition != C.INDEX_UNSET) {
                    spannableStringBuilder.setSpan(
                        UnderlineSpan(),
                        underlineStartPosition,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                if (foregroundColorStartPosition != C.INDEX_UNSET) {
                    spannableStringBuilder.setSpan(
                        ForegroundColorSpan(foregroundColor),
                        foregroundColorStartPosition,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                if (backgroundColorStartPosition != C.INDEX_UNSET) {
                    spannableStringBuilder.setSpan(
                        BackgroundColorSpan(backgroundColor),
                        backgroundColorStartPosition,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            return SpannableString(spannableStringBuilder)
        }

        fun build(): Cea708CueInfo? {
            if (this.isEmpty) {
                // The cue is empty.
                return null
            }

            val cueString = SpannableStringBuilder()

            // Add any rolled up captions, separated by new lines.
            for (i in rolledUpCaptions.indices) {
                cueString.append(rolledUpCaptions[i])
                cueString.append('\n')
            }
            // Add the current line.
            cueString.append(buildSpannableString())

            // TODO: Add support for right-to-left languages (i.e. where right would correspond to normal
            // alignment).
            val alignment: Layout.Alignment?
            when (justification) {
                JUSTIFICATION_FULL, JUSTIFICATION_LEFT -> alignment = Layout.Alignment.ALIGN_NORMAL
                JUSTIFICATION_RIGHT -> alignment = Layout.Alignment.ALIGN_OPPOSITE
                JUSTIFICATION_CENTER -> alignment = Layout.Alignment.ALIGN_CENTER
                else -> throw IllegalArgumentException("Unexpected justification value: " + justification)
            }

            var position: Float
            var line: Float
            if (relativePositioning) {
                position = horizontalAnchor.toFloat() / RELATIVE_CUE_SIZE
                line = verticalAnchor.toFloat() / RELATIVE_CUE_SIZE
            } else {
                position = horizontalAnchor.toFloat() / HORIZONTAL_SIZE
                line = verticalAnchor.toFloat() / VERTICAL_SIZE
            }
            // Apply screen-edge padding to the line and position.
            position = (position * 0.9f) + 0.05f
            line = (line * 0.9f) + 0.05f

            // anchorId specifies where the anchor should be placed on the caption cue/window. The 9
            // possible configurations are as follows:
            //   0-----1-----2
            //   |           |
            //   3     4     5
            //   |           |
            //   6-----7-----8
            @AnchorType val verticalAnchorType: @AnchorType Int
            if (anchorId / 3 == 0) {
                verticalAnchorType = Cue.ANCHOR_TYPE_START
            } else if (anchorId / 3 == 1) {
                verticalAnchorType = Cue.ANCHOR_TYPE_MIDDLE
            } else {
                verticalAnchorType = Cue.ANCHOR_TYPE_END
            }
            // TODO: Add support for right-to-left languages (i.e. where start is on the right).
            @AnchorType val horizontalAnchorType: @AnchorType Int
            if (anchorId % 3 == 0) {
                horizontalAnchorType = Cue.ANCHOR_TYPE_START
            } else if (anchorId % 3 == 1) {
                horizontalAnchorType = Cue.ANCHOR_TYPE_MIDDLE
            } else {
                horizontalAnchorType = Cue.ANCHOR_TYPE_END
            }

            val windowColorSet = (windowFillColor != COLOR_SOLID_BLACK)

            return Cea708CueInfo(
                cueString,
                alignment,
                line,
                Cue.LINE_TYPE_FRACTION,
                verticalAnchorType,
                position,
                horizontalAnchorType,
                Cue.DIMEN_UNSET,
                windowColorSet,
                windowFillColor,
                priority
            )
        }

        companion object {
            private const val RELATIVE_CUE_SIZE = 99
            private const val VERTICAL_SIZE = 74
            private const val HORIZONTAL_SIZE = 209

            private const val DEFAULT_PRIORITY = 4

            private const val MAXIMUM_ROW_COUNT = 15

            private const val JUSTIFICATION_LEFT = 0
            private const val JUSTIFICATION_RIGHT = 1
            private const val JUSTIFICATION_CENTER = 2
            private const val JUSTIFICATION_FULL = 3

            private const val DIRECTION_LEFT_TO_RIGHT = 0
            private const val DIRECTION_RIGHT_TO_LEFT = 1
            private const val DIRECTION_TOP_TO_BOTTOM = 2
            private const val DIRECTION_BOTTOM_TO_TOP = 3

            // TODO: Add other border/edge types when utilized.
            private const val BORDER_AND_EDGE_TYPE_NONE = 0
            private const val BORDER_AND_EDGE_TYPE_UNIFORM = 3

            val COLOR_SOLID_WHITE: Int = getArgbColorFromCeaColor(2, 2, 2, 0)
            val COLOR_SOLID_BLACK: Int = getArgbColorFromCeaColor(0, 0, 0, 0)
            val COLOR_TRANSPARENT: Int = getArgbColorFromCeaColor(0, 0, 0, 3)

            // TODO: Add other sizes when utilized.
            private const val PEN_SIZE_STANDARD = 1

            // TODO: Add other pen font styles when utilized.
            private const val PEN_FONT_STYLE_DEFAULT = 0
            private const val PEN_FONT_STYLE_MONOSPACED_WITH_SERIFS = 1
            private const val PEN_FONT_STYLE_PROPORTIONALLY_SPACED_WITH_SERIFS = 2
            private const val PEN_FONT_STYLE_MONOSPACED_WITHOUT_SERIFS = 3
            private const val PEN_FONT_STYLE_PROPORTIONALLY_SPACED_WITHOUT_SERIFS = 4

            // TODO: Add other pen offsets when utilized.
            private const val PEN_OFFSET_NORMAL = 1

            // The window style properties are specified in the CEA-708 specification.
            private val WINDOW_STYLE_JUSTIFICATION = intArrayOf(
                JUSTIFICATION_LEFT, JUSTIFICATION_LEFT, JUSTIFICATION_LEFT,
                JUSTIFICATION_LEFT, JUSTIFICATION_LEFT, JUSTIFICATION_CENTER,
                JUSTIFICATION_LEFT
            )
            private val WINDOW_STYLE_PRINT_DIRECTION = intArrayOf(
                DIRECTION_LEFT_TO_RIGHT, DIRECTION_LEFT_TO_RIGHT, DIRECTION_LEFT_TO_RIGHT,
                DIRECTION_LEFT_TO_RIGHT, DIRECTION_LEFT_TO_RIGHT, DIRECTION_LEFT_TO_RIGHT,
                DIRECTION_TOP_TO_BOTTOM
            )
            private val WINDOW_STYLE_SCROLL_DIRECTION = intArrayOf(
                DIRECTION_BOTTOM_TO_TOP, DIRECTION_BOTTOM_TO_TOP, DIRECTION_BOTTOM_TO_TOP,
                DIRECTION_BOTTOM_TO_TOP, DIRECTION_BOTTOM_TO_TOP, DIRECTION_BOTTOM_TO_TOP,
                DIRECTION_RIGHT_TO_LEFT
            )
            private val WINDOW_STYLE_WORD_WRAP =
                booleanArrayOf(false, false, false, true, true, true, false)
            private val WINDOW_STYLE_FILL = intArrayOf(
                COLOR_SOLID_BLACK,
                COLOR_TRANSPARENT,
                COLOR_SOLID_BLACK,
                COLOR_SOLID_BLACK,
                COLOR_TRANSPARENT,
                COLOR_SOLID_BLACK,
                COLOR_SOLID_BLACK
            )

            // The pen style properties are specified in the CEA-708 specification.
            private val PEN_STYLE_FONT_STYLE = intArrayOf(
                PEN_FONT_STYLE_DEFAULT,
                PEN_FONT_STYLE_MONOSPACED_WITH_SERIFS,
                PEN_FONT_STYLE_PROPORTIONALLY_SPACED_WITH_SERIFS,
                PEN_FONT_STYLE_MONOSPACED_WITHOUT_SERIFS,
                PEN_FONT_STYLE_PROPORTIONALLY_SPACED_WITHOUT_SERIFS,
                PEN_FONT_STYLE_MONOSPACED_WITHOUT_SERIFS,
                PEN_FONT_STYLE_PROPORTIONALLY_SPACED_WITHOUT_SERIFS
            )
            private val PEN_STYLE_EDGE_TYPE = intArrayOf(
                BORDER_AND_EDGE_TYPE_NONE, BORDER_AND_EDGE_TYPE_NONE, BORDER_AND_EDGE_TYPE_NONE,
                BORDER_AND_EDGE_TYPE_NONE, BORDER_AND_EDGE_TYPE_NONE, BORDER_AND_EDGE_TYPE_UNIFORM,
                BORDER_AND_EDGE_TYPE_UNIFORM
            )
            private val PEN_STYLE_BACKGROUND = intArrayOf(
                COLOR_SOLID_BLACK,
                COLOR_SOLID_BLACK,
                COLOR_SOLID_BLACK,
                COLOR_SOLID_BLACK,
                COLOR_SOLID_BLACK,
                COLOR_TRANSPARENT,
                COLOR_TRANSPARENT
            )

            fun getArgbColorFromCeaColor(red: Int, green: Int, blue: Int): Int {
                return getArgbColorFromCeaColor(red, green, blue, 0)
            }

            fun getArgbColorFromCeaColor(red: Int, green: Int, blue: Int, opacity: Int): Int {
                Preconditions.checkElementIndex(red, 4)
                Preconditions.checkElementIndex(green, 4)
                Preconditions.checkElementIndex(blue, 4)
                Preconditions.checkElementIndex(opacity, 4)

                val alpha: Int
                when (opacity) {
                    0, 1 ->           // Note the value of '1' is actually FLASH, but we don't support that.
                        alpha = 255

                    2 -> alpha = 127
                    3 -> alpha = 0
                    else -> alpha = 255
                }

                // TODO: Add support for the Alternative Minimum Color List or the full 64 RGB combinations.

                // Return values based on the Minimum Color List
                return Color.argb(
                    alpha,
                    (if (red > 1) 255 else 0),
                    (if (green > 1) 255 else 0),
                    (if (blue > 1) 255 else 0)
                )
            }
        }
    }

    private class Cea708CueInfo(
        text: CharSequence?,
        textAlignment: Layout.Alignment?,
        line: Float,
        @Cue.LineType lineType: @Cue.LineType Int,
        @AnchorType lineAnchor: @AnchorType Int,
        position: Float,
        @AnchorType positionAnchor: @AnchorType Int,
        size: Float,
        windowColorSet: Boolean,
        windowColor: Int,
        priority: Int
    ) {
        val cue: Cue

        val priority: Int

        init {
            val cueBuilder =
                Cue.Builder()
                    .setText(text)
                    .setTextAlignment(textAlignment)
                    .setLine(line, lineType)
                    .setLineAnchor(lineAnchor)
                    .setPosition(position)
                    .setPositionAnchor(positionAnchor)
                    .setSize(size)
            if (windowColorSet) {
                cueBuilder.setWindowColor(windowColor)
            }
            this.cue = cueBuilder.build()
            this.priority = priority
        }

        companion object {
            
             val LEAST_IMPORTANT_FIRST: Comparator<Cea708CueInfo?> =
                Comparator { thisInfo: Cea708CueInfo?, thatInfo: Cea708CueInfo? ->
                    Integer.compare(
                        thatInfo!!.priority,
                        thisInfo!!.priority
                    )
                }
        }
    }

    companion object {
        private const val TAG = "Cea708Decoder"

        private const val NUM_WINDOWS = 8

        private const val DTVCC_PACKET_DATA = 0x02
        private const val DTVCC_PACKET_START = 0x03
        private const val CC_VALID_FLAG = 0x04

        // Base Commands
        private const val GROUP_C0_END = 0x1F // Miscellaneous Control Codes
        private const val GROUP_G0_END = 0x7F // ASCII Printable Characters
        private const val GROUP_C1_END = 0x9F // Captioning Command Control Codes
        private const val GROUP_G1_END = 0xFF // ISO 8859-1 LATIN-1 Character Set

        // Extended Commands
        private const val GROUP_C2_END = 0x1F // Extended Control Code Set 1
        private const val GROUP_G2_END = 0x7F // Extended Miscellaneous Characters
        private const val GROUP_C3_END = 0x9F // Extended Control Code Set 2
        private const val GROUP_G3_END = 0xFF // Future Expansion

        // Group C0 Commands
        private const val COMMAND_NUL = 0x00 // Nul
        private const val COMMAND_ETX = 0x03 // EndOfText
        private const val COMMAND_BS = 0x08 // Backspace
        private const val COMMAND_FF = 0x0C // FormFeed (Flush)
        private const val COMMAND_CR = 0x0D // CarriageReturn
        private const val COMMAND_HCR = 0x0E // ClearLine
        private const val COMMAND_EXT1 = 0x10 // Extended Control Code Flag
        private const val COMMAND_EXT1_START = 0x11
        private const val COMMAND_EXT1_END = 0x17
        private const val COMMAND_P16_START = 0x18
        private const val COMMAND_P16_END = 0x1F

        // Group C1 Commands
        private const val COMMAND_CW0 = 0x80 // SetCurrentWindow to 0
        private const val COMMAND_CW1 = 0x81 // SetCurrentWindow to 1
        private const val COMMAND_CW2 = 0x82 // SetCurrentWindow to 2
        private const val COMMAND_CW3 = 0x83 // SetCurrentWindow to 3
        private const val COMMAND_CW4 = 0x84 // SetCurrentWindow to 4
        private const val COMMAND_CW5 = 0x85 // SetCurrentWindow to 5
        private const val COMMAND_CW6 = 0x86 // SetCurrentWindow to 6
        private const val COMMAND_CW7 = 0x87 // SetCurrentWindow to 7
        private const val COMMAND_CLW = 0x88 // ClearWindows (+1 byte)
        private const val COMMAND_DSW = 0x89 // DisplayWindows (+1 byte)
        private const val COMMAND_HDW = 0x8A // HideWindows (+1 byte)
        private const val COMMAND_TGW = 0x8B // ToggleWindows (+1 byte)
        private const val COMMAND_DLW = 0x8C // DeleteWindows (+1 byte)
        private const val COMMAND_DLY = 0x8D // Delay (+1 byte)
        private const val COMMAND_DLC = 0x8E // DelayCancel
        private const val COMMAND_RST = 0x8F // Reset
        private const val COMMAND_SPA = 0x90 // SetPenAttributes (+2 bytes)
        private const val COMMAND_SPC = 0x91 // SetPenColor (+3 bytes)
        private const val COMMAND_SPL = 0x92 // SetPenLocation (+2 bytes)
        private const val COMMAND_SWA = 0x97 // SetWindowAttributes (+4 bytes)
        private const val COMMAND_DF0 = 0x98 // DefineWindow 0 (+6 bytes)
        private const val COMMAND_DF1 = 0x99 // DefineWindow 1 (+6 bytes)
        private const val COMMAND_DF2 = 0x9A // DefineWindow 2 (+6 bytes)
        private const val COMMAND_DF3 = 0x9B // DefineWindow 3 (+6 bytes)
        private const val COMMAND_DF4 = 0x9C // DefineWindow 4 (+6 bytes)
        private const val COMMAND_DF5 = 0x9D // DefineWindow 5 (+6 bytes)
        private const val COMMAND_DF6 = 0x9E // DefineWindow 6 (+6 bytes)
        private const val COMMAND_DF7 = 0x9F // DefineWindow 7 (+6 bytes)

        // G0 Table Special Chars
        private const val CHARACTER_MN = 0x7F // MusicNote

        // G2 Table Special Chars
        private const val CHARACTER_TSP = 0x20
        private const val CHARACTER_NBTSP = 0x21
        private const val CHARACTER_ELLIPSIS = 0x25
        private const val CHARACTER_BIG_CARONS = 0x2A
        private const val CHARACTER_BIG_OE = 0x2C
        private const val CHARACTER_SOLID_BLOCK = 0x30
        private const val CHARACTER_OPEN_SINGLE_QUOTE = 0x31
        private const val CHARACTER_CLOSE_SINGLE_QUOTE = 0x32
        private const val CHARACTER_OPEN_DOUBLE_QUOTE = 0x33
        private const val CHARACTER_CLOSE_DOUBLE_QUOTE = 0x34
        private const val CHARACTER_BOLD_BULLET = 0x35
        private const val CHARACTER_TM = 0x39
        private const val CHARACTER_SMALL_CARONS = 0x3A
        private const val CHARACTER_SMALL_OE = 0x3C
        private const val CHARACTER_SM = 0x3D
        private const val CHARACTER_DIAERESIS_Y = 0x3F
        private const val CHARACTER_ONE_EIGHTH = 0x76
        private const val CHARACTER_THREE_EIGHTHS = 0x77
        private const val CHARACTER_FIVE_EIGHTHS = 0x78
        private const val CHARACTER_SEVEN_EIGHTHS = 0x79
        private const val CHARACTER_VERTICAL_BORDER = 0x7A
        private const val CHARACTER_UPPER_RIGHT_BORDER = 0x7B
        private const val CHARACTER_LOWER_LEFT_BORDER = 0x7C
        private const val CHARACTER_HORIZONTAL_BORDER = 0x7D
        private const val CHARACTER_LOWER_RIGHT_BORDER = 0x7E
        private const val CHARACTER_UPPER_LEFT_BORDER = 0x7F
    }
}
