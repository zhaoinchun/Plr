
package androidx.media3.extractor.mp4

import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.Mp4Box
import java.nio.ByteBuffer
import java.util.UUID

object PsshAtomUtil {
    private const val TAG = "PsshAtomUtil"

    fun buildPsshAtom(systemId: UUID, data: ByteArray?): ByteArray {
        return buildPsshAtom(systemId, null, data)
    }

    fun buildPsshAtom(
        systemId: UUID, keyIds: Array<UUID>?, data: ByteArray?
    ): ByteArray {
        val dataLength = if (data != null) data.size else 0
        var psshBoxLength =
            Mp4Box.FULL_HEADER_SIZE + 16  + 4  + dataLength
        if (keyIds != null) {
            psshBoxLength += 4  + (keyIds.size * 16) 
        }
        val psshBox = ByteBuffer.allocate(psshBoxLength)
        psshBox.putInt(psshBoxLength)
        psshBox.putInt(Mp4Box.TYPE_pssh)
        psshBox.putInt(if (keyIds != null) 0x01000000 else 0 )
        psshBox.putLong(systemId.getMostSignificantBits())
        psshBox.putLong(systemId.getLeastSignificantBits())
        if (keyIds != null) {
            psshBox.putInt(keyIds.size)
            for (keyId in keyIds) {
                psshBox.putLong(keyId.getMostSignificantBits())
                psshBox.putLong(keyId.getLeastSignificantBits())
            }
        }
        if (data != null && data.size != 0) {
            psshBox.putInt(data.size)
            psshBox.put(data)
        } else {
            psshBox.putInt(0)
        }
        return psshBox.array()
    }

    fun isPsshAtom(data: ByteArray): Boolean {
        return parsePsshAtom(data) != null
    }

    fun parseUuid(atom: ByteArray): UUID? {
        val parsedAtom = parsePsshAtom(atom)
        if (parsedAtom == null) {
            return null
        }
        return parsedAtom.uuid
    }

    fun parseVersion(atom: ByteArray): Int {
        val parsedAtom = parsePsshAtom(atom)
        if (parsedAtom == null) {
            return -1
        }
        return parsedAtom.version
    }

    fun parseSchemeSpecificData(atom: ByteArray, uuid: UUID): ByteArray? {
        val parsedAtom = parsePsshAtom(atom)
        if (parsedAtom == null) {
            return null
        }
        if (uuid != parsedAtom.uuid) {
            w(TAG, "UUID mismatch. Expected: " + uuid + ", got: " + parsedAtom.uuid + ".")
            return null
        }
        return parsedAtom.schemeData
    }

    fun parsePsshAtom(atom: ByteArray): PsshAtom? {
        val atomData = ParsableByteArray(atom)
        if (atomData.limit() < Mp4Box.FULL_HEADER_SIZE + 16  + 4 ) {
            // Data too short.
            return null
        }
        atomData.setPosition(0)
        var bufferLength = atomData.bytesLeft()
        val atomSize = atomData.readInt()
        if (atomSize != bufferLength) {
            w(
                TAG,
                "Advertised atom size (" + atomSize + ") does not match buffer size: " + bufferLength
            )
            return null
        }
        val atomType = atomData.readInt()
        if (atomType != Mp4Box.TYPE_pssh) {
            w(TAG, "Atom type is not pssh: " + atomType)
            return null
        }
        val atomVersion = BoxParser.parseFullBoxVersion(atomData.readInt())
        if (atomVersion > 1) {
            w(TAG, "Unsupported pssh version: " + atomVersion)
            return null
        }
        val uuid = UUID(atomData.readLong(), atomData.readLong())
        var keyIds: Array<UUID>? = null
        if (atomVersion == 1) {
            val keyIdCount = atomData.readUnsignedIntToInt()
            keyIds = Array<UUID>(keyIdCount){
                UUID(atomData.readLong(), atomData.readLong())
            }
        }
        val dataSize = atomData.readUnsignedIntToInt()
        bufferLength = atomData.bytesLeft()
        if (dataSize != bufferLength) {
            w(
                TAG,
                "Atom data size (" + dataSize + ") does not match the bytes left: " + bufferLength
            )
            return null
        }
        val data = ByteArray(dataSize)
        atomData.readBytes(data, 0, dataSize)
        return PsshAtom(uuid, atomVersion, data, keyIds)
    }

    class PsshAtom  internal constructor(
        
        val uuid: UUID?,
        
        val version: Int,
        
        val schemeData: ByteArray?,
        
        val keyIds: Array<UUID>?
    )
}
