package androidx.media3.common.util

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.C.TEXTURE_MIN_FILTER_LINEAR_MIPMAP_LINEAR
import androidx.media3.common.util.GlUtil.bindTexture
import androidx.media3.common.util.GlUtil.checkGlError
import androidx.media3.common.util.GlUtil.checkGlException
import androidx.media3.common.util.GlUtil.createBuffer
import androidx.media3.common.util.Util.loadAsset
import androidx.media3.common.util.Util.loadRawResource
import com.google.common.base.Preconditions
import java.nio.Buffer

class GlProgram(vertexShaderGlsl: String?, fragmentShaderGlsl: String?) {
    
    private val programId: Int

    private val attributes: Array<Attribute>
    private val uniforms: Array<Uniform>
    private val attributeByName: MutableMap<String?, Attribute?>
    private val uniformByName: MutableMap<String?, Uniform?>

    private var externalTexturesRequireNearestSampling = false

    constructor(context: Context, vertexShaderResId: Int, fragmentShaderResId: Int) : this(
        loadRawResource(context, vertexShaderResId),
        loadRawResource(context, fragmentShaderResId)
    )

    constructor(
        context: Context,
        vertexShaderFilePath: String,
        fragmentShaderFilePath: String
    ) : this(
        loadAsset(context, vertexShaderFilePath),
        loadAsset(context, fragmentShaderFilePath)
    )

    init {
        programId = GLES20.glCreateProgram()
        checkGlError()

        // Add the vertex and fragment shaders.
        addShader(programId, GLES20.GL_VERTEX_SHADER, vertexShaderGlsl)
        addShader(programId, GLES20.GL_FRAGMENT_SHADER, fragmentShaderGlsl)

        // Link and use the program, and enumerate attributes/uniforms.
        GLES20.glLinkProgram(programId)
        val linkStatus = intArrayOf(GLES20.GL_FALSE)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus,  0)
        checkGlException(
            linkStatus[0] == GLES20.GL_TRUE,
            "Unable to link shader program: \n" + GLES20.glGetProgramInfoLog(programId)
        )
        GLES20.glUseProgram(programId)
        attributeByName = HashMap<String?, Attribute?>()
        val attributeCount = IntArray(1)
        GLES20.glGetProgramiv(
            programId,
            GLES20.GL_ACTIVE_ATTRIBUTES,
            attributeCount,  
            0
        )
        attributes = Array(attributeCount[0]){ i->
             Attribute.create(programId, i).also {
                 attributeByName[it.name] = it
             }
        }
        for (i in 0..<attributeCount[0]) {
            val attribute: Attribute = Attribute.create(programId, i)
            attributes[i] = attribute
            attributeByName[attribute.name] = attribute
        }
        uniformByName = HashMap<String?, Uniform?>()
        val uniformCount = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_ACTIVE_UNIFORMS, uniformCount,  0)
        uniforms = Array<Uniform>(uniformCount[0]){i->
            Uniform.create(programId, i).also {
               uniformByName[it.name] = it
           }
        }
        for (i in 0..<uniformCount[0]) {
        }
        checkGlError()
    }

    private fun getAttributeLocation(attributeName: String?): Int {
        return getAttributeLocation(programId, attributeName)
    }

    fun getUniformLocation(uniformName: String?): Int {
        return getUniformLocation(programId, uniformName)
    }

    @Throws(GlUtil.GlException::class)
    fun use() {
        GLES20.glUseProgram(programId)
        checkGlError()
    }

    @Throws(GlUtil.GlException::class)
    fun delete() {
        if (Build.VERSION.SDK_INT == 28) {
            // Some MediaTek devices running API 28 crash with Fatal signal 6 (SIGABRT), code -6
            // (SI_TKILL) during glDeleteProgram. Leak the GL program instead of crashing.
            // Any leaked GL program memory will be cleaned up when the GL context is deleted.
            // Devices with the chipset Unisoc SC9863A are also affected. Unfortunately, these devices
            // return an extremely wide range of Build.HARDWARE values, and we cannot allow-list them
            // all. delete() calls are frequently followed by GL context deletion, so the program memory
            // won't leak for long.
            // See b/446675921.
            return
        }
        GLES20.glDeleteProgram(programId)
        checkGlError()
    }

    @Throws(GlUtil.GlException::class)
    fun getAttributeArrayLocationAndEnable(attributeName: String?): Int {
        val location = getAttributeLocation(attributeName)
        GLES20.glEnableVertexAttribArray(location)
        checkGlError()
        return location
    }

    fun setBufferAttribute(name: String?, values: FloatArray, size: Int) {
        attributeByName[name]!!.setBuffer(values, size)
    }

    fun setSamplerTexIdUniform(name: String?, texId: Int, texUnitIndex: Int) {
        uniformByName[name]!!.setSamplerTexId(texId, texUnitIndex)
    }

    fun setSamplerTexIdUniform(
        name: String?, texId: Int, texUnitIndex: Int, texMinFilter: @C.TextureMinFilter Int
    ) {
        val texUniform = uniformByName[name]!!
        texUniform.setSamplerTexId(texId, texUnitIndex)
        texUniform.setTexMinFilter(texMinFilter)
    }

    fun setIntUniform(name: String?, value: Int) {
        uniformByName[name]!!.setInt(value)
    }

    fun setIntsUniform(name: String?, value: IntArray) {
        uniformByName[name]!!.setInts(value)
    }

    fun setFloatUniform(name: String?, value: Float) {
        uniformByName[name]!!.setFloat(value)
    }

    fun setFloatsUniform(name: String?, value: FloatArray) {
        uniformByName[name]!!.setFloats(value)
    }

    fun setFloatsUniformIfPresent(name: String?, value: FloatArray) {
        val uniform = uniformByName[name] ?: return
        uniform.setFloats(value)
    }

    @Throws(GlUtil.GlException::class)
    fun bindAttributesAndUniforms() {
        for (attribute in attributes) {
            attribute.bind()
        }
        for (uniform in uniforms) {
            uniform.bind(externalTexturesRequireNearestSampling)
        }
    }

    fun setExternalTexturesRequireNearestSampling(
        externalTexturesRequireNearestSampling: Boolean
    ) {
        this.externalTexturesRequireNearestSampling = externalTexturesRequireNearestSampling
    }

    private class Attribute(
        
        val name: String?,
        
        private val location: Int
    ) {
        private var buffer: Buffer? = null
        private var size = 0

        fun setBuffer(buffer: FloatArray, size: Int) {
            this.buffer = createBuffer(buffer)
            this.size = size
        }

        @Throws(GlUtil.GlException::class)
        fun bind() {
            val buffer =
                Preconditions.checkNotNull<Buffer>(this.buffer, "call setBuffer before bind")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,  0)
            GLES20.glVertexAttribPointer(
                location, size, GLES20.GL_FLOAT,  false,  0, buffer
            )
            GLES20.glEnableVertexAttribArray(location)
            checkGlError()
        }

        companion object {
            
            fun create(programId: Int, index: Int): Attribute {
                val attributeNameMaxLength = IntArray(1)
                GLES20.glGetProgramiv(
                    programId,
                    GLES20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH,
                    attributeNameMaxLength,  
                    0
                )
                val nameBytes = ByteArray(attributeNameMaxLength[0])

                GLES20.glGetActiveAttrib(
                    programId,
                    index,  
                    attributeNameMaxLength[0],  
                    IntArray(1),  
                    0,  
                    IntArray(1),  
                    0,  
                    IntArray(1),  
                    0,  
                    nameBytes,  
                    0
                )
                val name = String(nameBytes,  0, getCStringLength(nameBytes))
                val location: Int = getAttributeLocation(programId, name)

                return Attribute(name, location)
            }
        }
    }

    private class Uniform(
        
        val name: String?, private val location: Int, private val type: Int
    ) {
        private val floatValue: FloatArray
        private val intValue: IntArray

        private var texIdValue = 0
        private var texUnitIndex = 0
        private var texMinFilter: @C.TextureMinFilter Int

        init {
            this.floatValue = FloatArray(16) // Allocate 16 for mat4
            this.intValue = IntArray(4) // Allocate 4 for ivec4
            this.texMinFilter = C.TEXTURE_MIN_FILTER_LINEAR
        }

        fun setSamplerTexId(texId: Int, texUnitIndex: Int) {
            this.texIdValue = texId
            this.texUnitIndex = texUnitIndex
        }

        fun setTexMinFilter(texMinFilter: @C.TextureMinFilter Int) {
            this.texMinFilter = texMinFilter
        }

        fun setInt(value: Int) {
            this.intValue[0] = value
        }

        fun setInts(value: IntArray) {
            System.arraycopy(value,  0, this.intValue,  0, value.size)
        }

        fun setFloat(value: Float) {
            this.floatValue[0] = value
        }

        fun setFloats(value: FloatArray) {
            System.arraycopy(value,  0, this.floatValue,  0, value.size)
        }

        @Throws(GlUtil.GlException::class)
        fun bind(externalTexturesRequireNearestSampling: Boolean) {
            when (type) {
                GLES20.GL_INT -> {
                    GLES20.glUniform1iv(location,  1, intValue,  0)
                    checkGlError()
                }

                GLES20.GL_INT_VEC2 -> {
                    GLES20.glUniform2iv(location,  1, intValue,  0)
                    checkGlError()
                }

                GLES20.GL_INT_VEC3 -> {
                    GLES20.glUniform3iv(location,  1, intValue,  0)
                    checkGlError()
                }

                GLES20.GL_INT_VEC4 -> {
                    GLES20.glUniform4iv(location,  1, intValue,  0)
                    checkGlError()
                }

                GLES20.GL_FLOAT -> {
                    GLES20.glUniform1fv(location,  1, floatValue,  0)
                    checkGlError()
                }

                GLES20.GL_FLOAT_VEC2 -> {
                    GLES20.glUniform2fv(location,  1, floatValue,  0)
                    checkGlError()
                }

                GLES20.GL_FLOAT_VEC3 -> {
                    GLES20.glUniform3fv(location,  1, floatValue,  0)
                    checkGlError()
                }

                GLES20.GL_FLOAT_VEC4 -> {
                    GLES20.glUniform4fv(location,  1, floatValue,  0)
                    checkGlError()
                }

                GLES20.GL_FLOAT_MAT3 -> {
                    GLES20.glUniformMatrix3fv(
                        location,  
                        1,  
                        false,
                        floatValue,  
                        0
                    )
                    checkGlError()
                }

                GLES20.GL_FLOAT_MAT4 -> {
                    GLES20.glUniformMatrix4fv(
                        location,  
                        1,  
                        false,
                        floatValue,  
                        0
                    )
                    checkGlError()
                }

                GLES20.GL_SAMPLER_2D, GLES11Ext.GL_SAMPLER_EXTERNAL_OES, GL_SAMPLER_EXTERNAL_2D_Y2Y_EXT, GLES30.GL_SAMPLER_3D -> {
                    check(texIdValue != 0) { "No call to setSamplerTexId() before bind." }
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + texUnitIndex)
                    checkGlError()
                    val target: Int
                    val samplingMode: Int
                    when (type) {
                        GLES20.GL_SAMPLER_2D -> {
                            target = GLES20.GL_TEXTURE_2D
                            samplingMode = GLES20.GL_LINEAR
                        }

                        GLES11Ext.GL_SAMPLER_EXTERNAL_OES, GL_SAMPLER_EXTERNAL_2D_Y2Y_EXT -> {
                            target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                            samplingMode =
                                if (externalTexturesRequireNearestSampling) GLES20.GL_NEAREST else GLES20.GL_LINEAR
                        }

                        GLES30.GL_SAMPLER_3D -> {
                            target = GLES30.GL_TEXTURE_3D
                            samplingMode = GLES30.GL_LINEAR
                        }

                        else -> throw IllegalStateException("Unexpected sampler type: " + type)
                    }
                    bindTexture(target, texIdValue, samplingMode)
                    if (type == GLES20.GL_SAMPLER_2D) {
                        if (texMinFilter == TEXTURE_MIN_FILTER_LINEAR_MIPMAP_LINEAR) {
                            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                            checkGlError()
                        }
                        GLES20.glTexParameteri(
                            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, texMinFilter
                        )
                        checkGlError()
                    }
                    GLES20.glUniform1i(location, texUnitIndex)
                    checkGlError()
                }

                else -> throw IllegalStateException("Unexpected uniform type: " + type)
            }
        }

        companion object {
            
            fun create(programId: Int, index: Int): Uniform {
                val length = IntArray(1)
                GLES20.glGetProgramiv(
                    programId, GLES20.GL_ACTIVE_UNIFORM_MAX_LENGTH, length,  0
                )

                val type = IntArray(1)
                val nameBytes = ByteArray(length[0])

                GLES20.glGetActiveUniform(
                    programId,
                    index,
                    length[0],  
                    IntArray(1),  
                    0,  
                    IntArray(1),  
                    0,
                    type,  
                    0,
                    nameBytes,  
                    0
                )
                val name = String(nameBytes,  0, getCStringLength(nameBytes))
                val location: Int = getUniformLocation(programId, name)

                return Uniform(name, location, type[0])
            }
        }
    }

    companion object {
        // https://www.khronos.org/registry/OpenGL/extensions/EXT/EXT_YUV_target.txt
        private const val GL_SAMPLER_EXTERNAL_2D_Y2Y_EXT = 0x8BE7

        @Throws(GlUtil.GlException::class)
        private fun addShader(programId: Int, type: Int, glsl: String?) {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, glsl)
            GLES20.glCompileShader(shader)

            val result = intArrayOf(GLES20.GL_FALSE)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result,  0)
            checkGlException(
                result[0] == GLES20.GL_TRUE,
                GLES20.glGetShaderInfoLog(shader) + ", source: \n" + glsl
            )

            GLES20.glAttachShader(programId, shader)
            GLES20.glDeleteShader(shader)
            checkGlError()
        }

        private fun getAttributeLocation(programId: Int, attributeName: String?): Int {
            return GLES20.glGetAttribLocation(programId, attributeName)
        }

        private fun getUniformLocation(programId: Int, uniformName: String?): Int {
            return GLES20.glGetUniformLocation(programId, uniformName)
        }

        private fun getCStringLength(cString: ByteArray): Int {
            for (i in cString.indices) {
                if (cString[i] == '\u0000'.code.toByte()) {
                    return i
                }
            }
            return cString.size
        }
    }
}
