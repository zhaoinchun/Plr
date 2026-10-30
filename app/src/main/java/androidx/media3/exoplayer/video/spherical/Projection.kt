package androidx.media3.exoplayer.video.spherical

import androidx.annotation.IntDef
import androidx.media3.common.C
import com.google.common.base.Preconditions
import kotlin.math.cos
import kotlin.math.sin

internal class Projection(
    val leftMesh: Mesh,
    val rightMesh: Mesh,
    val stereoMode: @C.StereoMode Int
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [DRAW_MODE_TRIANGLES, DRAW_MODE_TRIANGLES_STRIP, DRAW_MODE_TRIANGLES_FAN])
    annotation class DrawMode

    val singleMesh = leftMesh == rightMesh

    constructor(mesh: Mesh, stereoMode: Int) : this(mesh, mesh, stereoMode)

    class SubMesh(
        val textureId: Int, vertices: FloatArray, textureCoords: FloatArray, mode: @DrawMode Int
    ) {
        val mode: @DrawMode Int
        val vertices: FloatArray
        val textureCoords: FloatArray

        init {
            Preconditions.checkArgument(
                vertices.size * TEXTURE_COORDS_PER_VERTEX.toLong()
                        == textureCoords.size * POSITION_COORDS_PER_VERTEX.toLong()
            )
            this.vertices = vertices
            this.textureCoords = textureCoords
            this.mode = mode
        }

        val vertexCount: Int
            get() = vertices.size / POSITION_COORDS_PER_VERTEX

        companion object {
            const val VIDEO_TEXTURE_ID: Int = 0
        }
    }

    class Mesh(private val subMeshes: Array<SubMesh>) {
        val subMeshCount: Int
            get() = subMeshes.size

        fun getSubMesh(index: Int): SubMesh {
            return subMeshes[index]
        }
    }

    companion object {
        const val DRAW_MODE_TRIANGLES: Int = 0
        const val DRAW_MODE_TRIANGLES_STRIP: Int = 1
        const val DRAW_MODE_TRIANGLES_FAN: Int = 2
        const val TEXTURE_COORDS_PER_VERTEX: Int = 2
        const val POSITION_COORDS_PER_VERTEX: Int = 3

        fun createEquirectangular(stereoMode: @C.StereoMode Int): Projection {
            return createEquirectangular( 
                50f,  // Should be large enough that there are no stereo artifacts.
                
                36,  // Should be large enough to prevent videos looking wavy.
                
                72,  // Should be large enough to prevent videos looking wavy.
                
                180f,  
                360f,
                stereoMode
            )
        }

        fun createEquirectangular(
            radius: Float,
            latitudes: Int,
            longitudes: Int,
            verticalFovDegrees: Float,
            horizontalFovDegrees: Float,
            stereoMode: @C.StereoMode Int
        ): Projection {
            Preconditions.checkArgument(radius > 0)
            Preconditions.checkArgument(latitudes >= 1)
            Preconditions.checkArgument(longitudes >= 1)
            Preconditions.checkArgument(verticalFovDegrees > 0 && verticalFovDegrees <= 180)
            Preconditions.checkArgument(horizontalFovDegrees > 0 && horizontalFovDegrees <= 360)

            // Compute angular size in radians of each UV quad.
            val verticalFovRads = Math.toRadians(verticalFovDegrees.toDouble()).toFloat()
            val horizontalFovRads = Math.toRadians(horizontalFovDegrees.toDouble()).toFloat()
            val quadHeightRads = verticalFovRads / latitudes
            val quadWidthRads = horizontalFovRads / longitudes

            // Each latitude strip has 2 * (longitudes quads + extra edge) vertices + 2 degenerate vertices.
            val vertexCount = (2 * (longitudes + 1) + 2) * latitudes
            // Buffer to return.
            val vertexData = FloatArray(vertexCount * POSITION_COORDS_PER_VERTEX)
            val textureData = FloatArray(vertexCount * TEXTURE_COORDS_PER_VERTEX)

            // Generate the data for the sphere which is a set of triangle strips representing each
            // latitude band.
            var vOffset = 0 // Offset into the vertexData array.
            var tOffset = 0 // Offset into the textureData array.
            // (i, j) represents a quad in the equirectangular sphere.
            for (j in 0..<latitudes) { // For each horizontal triangle strip.
                // Each latitude band lies between the two phi values. Each vertical edge on a band lies on
                // a theta value.
                val phiLow = quadHeightRads * j - verticalFovRads / 2
                val phiHigh = quadHeightRads * (j + 1) - verticalFovRads / 2

                for (i in 0..<longitudes + 1) { // For each vertical edge in the band.
                    for (k in 0..1) { // For low and high points on an edge.
                        // For each point, determine its position in polar coordinates.
                        val phi = if (k == 0) phiLow else phiHigh
                        val theta = quadWidthRads * i + Math.PI.toFloat() - horizontalFovRads / 2

                        // Set vertex position data as Cartesian coordinates.
                        vertexData[vOffset++] =
                            -(radius * sin(theta.toDouble()) * cos(phi.toDouble())).toFloat()
                        vertexData[vOffset++] = (radius * sin(phi.toDouble())).toFloat()
                        vertexData[vOffset++] =
                            (radius * cos(theta.toDouble()) * cos(phi.toDouble())).toFloat()

                        textureData[tOffset++] = i * quadWidthRads / horizontalFovRads
                        textureData[tOffset++] = (j + k) * quadHeightRads / verticalFovRads

                        // Break up the triangle strip with degenerate vertices by copying first and last points.
                        if ((i == 0 && k == 0) || (i == longitudes && k == 1)) {
                            System.arraycopy(
                                vertexData,
                                vOffset - POSITION_COORDS_PER_VERTEX,
                                vertexData,
                                vOffset,
                                POSITION_COORDS_PER_VERTEX
                            )
                            vOffset += POSITION_COORDS_PER_VERTEX
                            System.arraycopy(
                                textureData,
                                tOffset - TEXTURE_COORDS_PER_VERTEX,
                                textureData,
                                tOffset,
                                TEXTURE_COORDS_PER_VERTEX
                            )
                            tOffset += TEXTURE_COORDS_PER_VERTEX
                        }
                    }
                    // Move on to the next vertical edge in the triangle strip.
                }
                // Move on to the next triangle strip.
            }
            val subMesh =
                SubMesh(
                    SubMesh.VIDEO_TEXTURE_ID,
                    vertexData,
                    textureData,
                    DRAW_MODE_TRIANGLES_STRIP
                )
            return Projection(Mesh(arrayOf(subMesh)), stereoMode)
        }
    }
}
