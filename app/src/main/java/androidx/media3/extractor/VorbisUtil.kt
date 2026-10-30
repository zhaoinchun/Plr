
package androidx.media3.extractor

import android.util.Base64
import androidx.media3.common.Metadata
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.splitAtFirst
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.primitives.ImmutableIntArray

object VorbisUtil {
    private const val TAG = "VorbisUtil"

    private val VORBIS_MAPPING_3: ImmutableIntArray = ImmutableIntArray.of(0, 2, 1)
    private val VORBIS_MAPPING_5: ImmutableIntArray = ImmutableIntArray.of(0, 2, 1, 3, 4)
    private val VORBIS_MAPPING_6: ImmutableIntArray = ImmutableIntArray.of(0, 2, 1, 5, 3, 4)
    private val VORBIS_MAPPING_7: ImmutableIntArray = ImmutableIntArray.of(0, 2, 1, 6, 5, 3, 4)
    private val VORBIS_MAPPING_8: ImmutableIntArray = ImmutableIntArray.of(0, 2, 1, 7, 5, 6, 3, 4)

    fun getVorbisToAndroidChannelLayoutMapping(channelCount: Int): ImmutableIntArray? {
        when (channelCount) {
            3 -> return VORBIS_MAPPING_3
            5 -> return VORBIS_MAPPING_5
            6 -> return VORBIS_MAPPING_6
            7 -> return VORBIS_MAPPING_7
            8 -> return VORBIS_MAPPING_8
            else -> return null
        }
    }

    fun parseVorbisCsdFromEsdsInitializationData(
        initializationData: ByteArray
    ): List<ByteArray> {
        val buffer = ParsableByteArray(initializationData)
        buffer.skipBytes(1) // 0x02 for vorbis audio

        var identificationHeaderLength = 0
        while (buffer.bytesLeft() > 0 && buffer.peekUnsignedByte() == 0xFF) {
            identificationHeaderLength += 0xFF
            buffer.skipBytes(1)
        }
        identificationHeaderLength += buffer.readUnsignedByte()

        var commentHeaderLength = 0
        while (buffer.bytesLeft() > 0 && buffer.peekUnsignedByte() == 0xFF) {
            commentHeaderLength += 0xFF
            buffer.skipBytes(1)
        }
        commentHeaderLength += buffer.readUnsignedByte()

        // csd-0 is the identification header.
        val csd0 = ByteArray(identificationHeaderLength)
        val identificationHeaderOffset = buffer.getPosition()
        System.arraycopy( 
            initializationData,  
            identificationHeaderOffset,  
            csd0,  
            0,  
            identificationHeaderLength
        )

        // csd-1 is the setup header, which is the remaining data after the identification and comment
        // headers.
        val setupHeaderOffset =
            identificationHeaderOffset + identificationHeaderLength + commentHeaderLength
        val setupHeaderLength = initializationData.size - setupHeaderOffset
        val csd1 = ByteArray(setupHeaderLength)
        System.arraycopy( 
            initializationData,  
            setupHeaderOffset,  
            csd1,  
            0,  
            setupHeaderLength
        )
        return listOf(csd0, csd1)
    }

    fun parseVorbisComments(vorbisComments: List<String>): Metadata? {
        val metadataEntries = mutableListOf<Metadata.Entry>()
        for (i in vorbisComments.indices) {
            val vorbisComment = vorbisComments[i]
            val keyAndValue = splitAtFirst(vorbisComment, "=")
            if (keyAndValue.size != 2) {
                w(TAG, "Failed to parse Vorbis comment: " + vorbisComment)
                continue
            }

            if (keyAndValue[0] == "METADATA_BLOCK_PICTURE") {
                // This tag is a special cover art tag, outlined by
                // https://wiki.xiph.org/index.php/VorbisComment#Cover_art.
                // Decode it from Base64 and transform it into a PictureFrame.
                try {
                    val decoded = Base64.decode(keyAndValue[1], Base64.DEFAULT)
                    metadataEntries.add(PictureFrame.fromPictureBlock(ParsableByteArray(decoded)))
                } catch (e: RuntimeException) {
                    w(TAG, "Failed to parse vorbis picture", e)
                }
            } else {
                val entry = VorbisComment(keyAndValue[0], keyAndValue[1])
                metadataEntries.add(entry)
            }
        }

        return if (metadataEntries.isEmpty()) null else Metadata(metadataEntries)
    }
}
