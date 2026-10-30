package androidx.media3.extractor.metadata.flac

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes.normalizeMimeType
import androidx.media3.common.util.ParsableByteArray
import java.nio.charset.StandardCharsets

class PictureFrame(
    
    val pictureType: Int,
    
    val mimeType: String,
    
    val description: String,
    
    val width: Int,
    
    val height: Int,
    
    val depth: Int,
    
    val colors: Int,
    
    val pictureData: ByteArray
) : Metadata.Entry {
    override fun populateMediaMetadata(builder: MediaMetadata.Builder) {
        builder.maybeSetArtworkData(pictureData, pictureType)
    }

    override fun toString(): String {
        return "Picture: mimeType=" + mimeType + ", description=" + description
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as PictureFrame
        return (pictureType == other.pictureType)
                && mimeType == other.mimeType
                && description == other.description
                && (width == other.width)
                && (height == other.height)
                && (depth == other.depth)
                && (colors == other.colors)
                && pictureData.contentEquals(other.pictureData)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + pictureType
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + depth
        result = 31 * result + colors
        result = 31 * result + pictureData.contentHashCode()
        return result
    }

    companion object {
        
        fun fromPictureBlock(pictureBlock: ParsableByteArray): PictureFrame {
            val pictureType = pictureBlock.readInt()
            val mimeTypeLength = pictureBlock.readInt()
            val mimeType =
                normalizeMimeType(
                    pictureBlock.readString(mimeTypeLength, StandardCharsets.US_ASCII)
                )
            val descriptionLength = pictureBlock.readInt()
            val description = pictureBlock.readString(descriptionLength)
            val width = pictureBlock.readInt()
            val height = pictureBlock.readInt()
            val depth = pictureBlock.readInt()
            val colors = pictureBlock.readInt()
            val pictureDataLength = pictureBlock.readInt()
            val pictureData = ByteArray(pictureDataLength)
            pictureBlock.readBytes(pictureData, 0, pictureDataLength)

            return PictureFrame(
                pictureType, mimeType!!, description, width, height, depth, colors, pictureData
            )
        }
    }
}
