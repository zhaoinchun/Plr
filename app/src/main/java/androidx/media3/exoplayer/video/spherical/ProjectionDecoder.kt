
package androidx.media3.exoplayer.video.spherical

import androidx.media3.common.C
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.inflate
import androidx.media3.exoplayer.video.spherical.Projection.SubMesh
import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.ln

internal object ProjectionDecoder {
    private const val TYPE_YTMP = 0x79746d70
    private const val TYPE_MSHP = 0x6d736870
    private const val TYPE_RAW = 0x72617720
    private const val TYPE_DFL8 = 0x64666c38
    private const val TYPE_MESH = 0x6d657368
    private const val TYPE_PROJ = 0x70726f6a

    // Limits to prevent a bad file from creating an OOM situation. We don't expect a mesh to
    // exceed these limits.
    private const val MAX_COORDINATE_COUNT = 10000
    private val MAX_VERTEX_COUNT = 32 * 1000
    private val MAX_TRIANGLE_INDICES = 128 * 1000
    private val LOG2 = ln(2.0)

    fun decode(projectionData: ByteArray, stereoMode: @C.StereoMode Int): Projection? {
        val input = ParsableByteArray(projectionData)
        // MP4 containers include the proj box but webm containers do not.
        // Both containers use mshp.
        var meshes: ArrayList<Projection.Mesh>? = null
        try {
            meshes = if (isProj(input)) parseProj(input) else parseMshp(input)
        } catch (ignored: ArrayIndexOutOfBoundsException) {
            // Do nothing.
        }
        if (meshes == null) {
            return null
        } else {
            when (meshes.size) {
                1 -> return Projection(meshes[0], stereoMode)
                2 -> return Projection(meshes[0], meshes.get(1), stereoMode)
                0 -> return null
                else -> return null
            }
        }
    }

    private fun isProj(input: ParsableByteArray): Boolean {
        input.skipBytes(4) // size
        val type = input.readInt()
        input.setPosition(0)
        return type == TYPE_PROJ
    }

    private fun parseProj(input: ParsableByteArray): ArrayList<Projection.Mesh>? {
        input.skipBytes(8) // size and type.
        var position = input.getPosition()
        val limit = input.limit()
        while (position < limit) {
            val childEnd = position + input.readInt()
            if (childEnd <= position || childEnd > limit) {
                return null
            }
            val childAtomType = input.readInt()
            // Some early files named the atom ytmp rather than mshp.
            if (childAtomType == TYPE_YTMP || childAtomType == TYPE_MSHP) {
                input.setLimit(childEnd)
                return parseMshp(input)
            }
            position = childEnd
            input.setPosition(position)
        }
        return null
    }

    private fun parseMshp(input: ParsableByteArray): ArrayList<Projection.Mesh>? {
        var input = input
        val version = input.readUnsignedByte()
        if (version != 0) {
            return null
        }
        input.skipBytes(7) // flags + crc.
        val encoding = input.readInt()
        if (encoding == TYPE_DFL8) {
            val output = ParsableByteArray()
            val inflater = Inflater(true)
            try {
                if (!inflate(input, output, inflater)) {
                    return null
                }
            } finally {
                inflater.end()
            }
            input = output
        } else if (encoding != TYPE_RAW) {
            return null
        }
        return parseRawMshpData(input)
    }

    private fun parseRawMshpData(input: ParsableByteArray): ArrayList<Projection.Mesh>? {
        val meshes = ArrayList<Projection.Mesh>()
        var position = input.getPosition()
        val limit = input.limit()
        while (position < limit) {
            val childEnd = position + input.readInt()
            if (childEnd !in (position + 1)..limit) {
                return null
            }
            val childAtomType = input.readInt()
            if (childAtomType == TYPE_MESH) {
                val mesh = parseMesh(input) ?: return null
                meshes.add(mesh)
            }
            position = childEnd
            input.setPosition(position)
        }
        return meshes
    }

    private fun parseMesh(input: ParsableByteArray): Projection.Mesh? {
        // Read the coordinates.
        val coordinateCount = input.readInt()
        if (coordinateCount !in 1..MAX_COORDINATE_COUNT) {
            return null
        }
        val coordinates = FloatArray(coordinateCount)
        for (coordinate in 0..<coordinateCount) {
            coordinates[coordinate] = input.readFloat()
        }
        // Read the vertices.
        val vertexCount = input.readInt()
        if (vertexCount !in 1..MAX_VERTEX_COUNT) {
            return null
        }

        val coordinateCountSizeBits = ceil(ln(2.0 * coordinateCount) / LOG2).toInt()

        val bitInput = ParsableBitArray(input.data)
        bitInput.position=(input.getPosition() * 8)
        val vertices = FloatArray(vertexCount * 5)
        val coordinateIndices = IntArray(5)
        var vertexIndex = 0
        for (vertex in 0..<vertexCount) {
            for (i in 0..4) {
                val coordinateIndex =
                    coordinateIndices[i] + decodeZigZag(bitInput.readBits(coordinateCountSizeBits))
                if (coordinateIndex !in 0..<coordinateCount) {
                    return null
                }
                vertices[vertexIndex++] = coordinates[coordinateIndex]
                coordinateIndices[i] = coordinateIndex
            }
        }

        // Pad to next byte boundary
        bitInput.position=(((bitInput.position + 7) and 7.inv()))

        val subMeshCount = bitInput.readBits(32)
        if (subMeshCount <= 0) {
            return null
        }
        val subMeshes = arrayOfNulls<SubMesh>(subMeshCount)
        for (i in 0..<subMeshCount) {
            val textureId = bitInput.readBits(8)
            val drawMode = bitInput.readBits(8)
            val triangleIndexCount = bitInput.readBits(32)
            if (triangleIndexCount !in 1..MAX_TRIANGLE_INDICES) {
                return null
            }
            val vertexCountSizeBits = ceil(ln(2.0 * vertexCount) / LOG2).toInt()
            var index = 0
            val triangleVertices = FloatArray(triangleIndexCount * 3)
            val textureCoords = FloatArray(triangleIndexCount * 2)
            for (counter in 0..<triangleIndexCount) {
                index += decodeZigZag(bitInput.readBits(vertexCountSizeBits))
                if (index !in 0..<vertexCount) {
                    return null
                }
                triangleVertices[counter * 3] = vertices[index * 5]
                triangleVertices[counter * 3 + 1] = vertices[index * 5 + 1]
                triangleVertices[counter * 3 + 2] = vertices[index * 5 + 2]
                textureCoords[counter * 2] = vertices[index * 5 + 3]
                textureCoords[counter * 2 + 1] = vertices[index * 5 + 4]
            }
            subMeshes[i] = SubMesh(textureId, triangleVertices, textureCoords, drawMode)
        }
        return Projection.Mesh(subMeshes as Array<SubMesh>)
    }

    private fun decodeZigZag(n: Int): Int {
        return (n shr 1) xor -(n and 1)
    }
}
