
package androidx.media3.extractor.text.webvtt

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.fromUtf8Bytes
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttCueParser.newCueForText
import androidx.media3.extractor.text.webvtt.WebvttCueParser.parseCueSettingsList
import androidx.media3.extractor.text.webvtt.WebvttCueParser.parseCueText
import com.google.common.base.Preconditions

class Mp4WebvttParser : SubtitleParser {
    private val parsableByteArray: ParsableByteArray

    init {
        parsableByteArray = ParsableByteArray()
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: ((CuesWithTiming)-> Unit)
    ) {
        parsableByteArray.reset(data!!,  offset + length)
        parsableByteArray.setPosition(offset)
        val cues: MutableList<Cue> = ArrayList<Cue>()
        while (parsableByteArray.bytesLeft() > 0) {
            // Webvtt in Mp4 samples have boxes inside of them, so we have to do a traditional box
            // parsing: first 4 bytes size and then 4 bytes type.
            Preconditions.checkArgument(
                parsableByteArray.bytesLeft() >= BOX_HEADER_SIZE,
                "Incomplete Mp4Webvtt Top Level box header found."
            )
            val boxSize = parsableByteArray.readInt()
            val boxType = parsableByteArray.readInt()
            if (boxType == TYPE_vttc) {
                cues.add(parseVttCueBox(parsableByteArray, boxSize - BOX_HEADER_SIZE))
            } else {
                // Peers of the VTTCueBox are still not supported and are skipped.
                parsableByteArray.skipBytes(boxSize - BOX_HEADER_SIZE)
            }
        }
        output(
            CuesWithTiming(cues,  C.TIME_UNSET,  C.TIME_UNSET)
        )
    }

    override val cueReplacementBehavior: Int
        get() = Mp4WebvttParser.cueReplacementBehavior

    companion object {
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

        private const val BOX_HEADER_SIZE = 8

        private const val TYPE_payl = 0x7061796c

        private const val TYPE_sttg = 0x73747467

        private const val TYPE_vttc = 0x76747463

        private fun parseVttCueBox(sampleData: ParsableByteArray, remainingCueBoxBytes: Int): Cue {
            var remainingCueBoxBytes = remainingCueBoxBytes
            var cueBuilder: Cue.Builder? = null
            var cueText: CharSequence? = null
            while (remainingCueBoxBytes > 0) {
                Preconditions.checkArgument(
                    remainingCueBoxBytes >= BOX_HEADER_SIZE, "Incomplete vtt cue box header found."
                )
                val boxSize = sampleData.readInt()
                val boxType = sampleData.readInt()
                remainingCueBoxBytes -= BOX_HEADER_SIZE
                val payloadLength: Int = boxSize - BOX_HEADER_SIZE
                val boxPayload =
                    fromUtf8Bytes(sampleData.data, sampleData.getPosition(), payloadLength)
                sampleData.skipBytes(payloadLength)
                remainingCueBoxBytes -= payloadLength
                if (boxType == TYPE_sttg) {
                    cueBuilder = parseCueSettingsList(boxPayload)
                } else if (boxType == TYPE_payl) {
                    cueText =
                        parseCueText( 
                            null, boxPayload.trim { it <= ' ' },  
                            mutableListOf<WebvttCssStyle>()
                        )
                } else {
                    // Other VTTCueBox children are still not supported and are ignored.
                }
            }
            if (cueText == null) {
                cueText = ""
            }
            return if (cueBuilder != null)
                cueBuilder.setText(cueText).build()
            else
                newCueForText(cueText)
        }
    }
}
