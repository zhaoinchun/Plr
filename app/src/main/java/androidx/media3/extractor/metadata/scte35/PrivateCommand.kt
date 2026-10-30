package androidx.media3.extractor.metadata.scte35

import androidx.media3.common.util.ParsableByteArray

class PrivateCommand private constructor(
    
    val identifier: Long,
    
    val commandBytes: ByteArray?,
    
    val ptsAdjustment: Long
) : SpliceCommand() {
    override fun toString(): String {
        return ("SCTE-35 PrivateCommand { ptsAdjustment="
                + ptsAdjustment
                + ", identifier= "
                + identifier
                + " }")
    }

    companion object {
        
        fun parseFromSection(
            sectionData: ParsableByteArray, commandLength: Int, ptsAdjustment: Long
        ): PrivateCommand {
            val identifier = sectionData.readUnsignedInt()
            val privateBytes = ByteArray(commandLength - 4 )
            sectionData.readBytes(privateBytes, 0, privateBytes.size)
            return PrivateCommand(identifier, privateBytes, ptsAdjustment)
        }
    }
}
