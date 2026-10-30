package androidx.media3.common.audio

import androidx.annotation.IntRange

import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue

class ChannelMixingMatrix(
    inputChannelCount: Int,
    outputChannelCount: Int,
    coefficients: FloatArray
) {
    val inputChannelCount: Int
    val outputChannelCount: Int
    private val coefficients: FloatArray

    val isZero: Boolean

    val isDiagonal: Boolean

    val isIdentity: Boolean

    init {
        Preconditions.checkArgument(inputChannelCount > 0, "Input channel count must be positive.")
        Preconditions.checkArgument(
            outputChannelCount > 0,
            "Output channel count must be positive."
        )
        Preconditions.checkArgument(
            coefficients.size == inputChannelCount * outputChannelCount,
            "Coefficient array length is invalid."
        )
        this.inputChannelCount = inputChannelCount
        this.outputChannelCount = outputChannelCount
        this.coefficients = checkCoefficientsValid(coefficients)

        // Calculate matrix properties.
        var allDiagonalCoefficientsAreOne = true
        var allCoefficientsAreZero = true
        var allNonDiagonalCoefficientsAreZero = true
        for (row in 0..<inputChannelCount) {
            for (col in 0..<outputChannelCount) {
                val coefficient = getMixingCoefficient(row, col)
                val onDiagonal = row == col

                if (coefficient != 1f && onDiagonal) {
                    allDiagonalCoefficientsAreOne = false
                }
                if (coefficient != 0f) {
                    allCoefficientsAreZero = false
                    if (!onDiagonal) {
                        allNonDiagonalCoefficientsAreZero = false
                    }
                }
            }
        }
        isZero = allCoefficientsAreZero
        isDiagonal = this.isSquare && allNonDiagonalCoefficientsAreZero
        isIdentity = isDiagonal && allDiagonalCoefficientsAreOne
    }

    fun getMixingCoefficient(inputChannel: Int, outputChannel: Int): Float {
        return coefficients[inputChannel * outputChannelCount + outputChannel]
    }

    val isSquare: Boolean
        
        get() = inputChannelCount == outputChannelCount

    fun scaleBy(scale: Float): ChannelMixingMatrix {
        val scaledCoefficients = FloatArray(coefficients.size)
        for (i in coefficients.indices) {
            scaledCoefficients[i] = scale * coefficients[i]
        }
        return ChannelMixingMatrix(inputChannelCount, outputChannelCount, scaledCoefficients)
    }

    companion object {
        
        fun createForConstantGain(
            @IntRange(from = 1, to = 2) inputChannelCount: Int,
            @IntRange(from = 1, to = 2) outputChannelCount: Int
        ): ChannelMixingMatrix {
            return ChannelMixingMatrix(
                inputChannelCount,
                outputChannelCount,
                createConstantGainMixingCoefficients(inputChannelCount, outputChannelCount)
            )
        }

        fun createForConstantPower(
            @IntRange(from = 1, to = 6) inputChannelCount: Int,
            @IntRange(from = 1, to = 2) outputChannelCount: Int
        ): ChannelMixingMatrix {
            return ChannelMixingMatrix(
                inputChannelCount,
                outputChannelCount,
                createConstantPowerMixingCoefficients(inputChannelCount, outputChannelCount)
            )
        }

        private fun initializeIdentityMatrix(channelCount: Int): FloatArray {
            val coefficients = FloatArray(channelCount * channelCount)
            for (c in 0..<channelCount) {
                coefficients[channelCount * c + c] = 1f
            }
            return coefficients
        }

        private fun checkCoefficientsValid(coefficients: FloatArray): FloatArray {
            for (i in coefficients.indices) {
                require(!(coefficients[i] < 0f)) { "Coefficient at index " + i + " is negative." }
            }
            return coefficients
        }

        private fun createConstantGainMixingCoefficients(
            inputChannelCount: Int, outputChannelCount: Int
        ): FloatArray {
            if (inputChannelCount == outputChannelCount) {
                return initializeIdentityMatrix(outputChannelCount)
            }
            if (inputChannelCount == 1 && outputChannelCount == 2) {
                // Mono -> stereo.
                return floatArrayOf(
                    1f,  // left
                    1f // right
                )
            }
            if (inputChannelCount == 2 && outputChannelCount == 1) {
                // Stereo -> mono.
                // 2 channels: [FRONT_LEFT, FRONT_RIGHT]
                return floatArrayOf(0.5f, 0.5f)
            }

            throw UnsupportedOperationException(
                ("Default channel mixing coefficients for "
                        + inputChannelCount
                        + "->"
                        + outputChannelCount
                        + " are not yet implemented.")
            )
        }

        private fun createConstantPowerMixingCoefficients(
            inputChannelCount: Int, outputChannelCount: Int
        ): FloatArray {
            if (outputChannelCount == 1) {
                return getConstantPowerCoefficientsToMono(inputChannelCount)
            }
            if (outputChannelCount == 2) {
                return getConstantPowerCoefficientsToStereo(inputChannelCount)
            }
            if (inputChannelCount == outputChannelCount) {
                return initializeIdentityMatrix(outputChannelCount)
            }
            throw UnsupportedOperationException(
                ("Default constant power channel mixing coefficients for "
                        + inputChannelCount
                        + "->"
                        + outputChannelCount
                        + " are not implemented.")
            )
        }

        private fun getConstantPowerCoefficientsToMono(inputChannelCount: Int): FloatArray {
            when (inputChannelCount) {
                1 ->         // 1 channel: [MONO]
                    return floatArrayOf(1.0f)

                2 ->         // 2 channels: [FRONT_LEFT, FRONT_RIGHT]
                    return floatArrayOf(0.7071f, 0.7071f)

                3 ->         // 3 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER]
                    return floatArrayOf(0.7071f, 0.7071f, 1.0f)

                4 ->         // 4 channels: [FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT]
                    return floatArrayOf(0.7071f, 0.7071f, 0.5f, 0.5f)

                5 ->         // 5 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, BACK_LEFT, BACK_RIGHT]
                    return floatArrayOf(0.7071f, 0.7071f, 1.0f, 0.5f, 0.5f)

                6 ->         // 6 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_LEFT, BACK_RIGHT]
                    return floatArrayOf(0.7071f, 0.7071f, 1.0f, 0.7071f, 0.5f, 0.5f)

                else -> throw UnsupportedOperationException(
                    ("Default constant power channel mixing coefficients for "
                            + inputChannelCount
                            + "->1 are not implemented.")
                )
            }
        }

        private fun getConstantPowerCoefficientsToStereo(inputChannelCount: Int): FloatArray {
            when (inputChannelCount) {
                1 ->         // 1 channel: [FRONT_CENTER]
                    return floatArrayOf(
                        0.7071f,  // left
                        0.7071f // right
                    )

                2 ->         // 2 channels: [FRONT_LEFT, FRONT_RIGHT]
                    return floatArrayOf( 1.0f, 0.0f,  
                        0.0f, 1.0f
                    )

                3 ->         // 3 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER]
                    return floatArrayOf( 1.0f, 0.0f, 0.7071f,  
                        0.0f, 1.0f, 0.7071f
                    )

                4 ->         // 4 channels: [FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT]
                    return floatArrayOf( 1.0f, 0.0f, 0.7071f, 0.0f,  
                        0.0f, 1.0f, 0.0f, 0.7071f
                    )

                5 ->         // 5 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, BACK_LEFT, BACK_RIGHT]
                    return floatArrayOf( 1.0f, 0.0f, 0.7071f, 0.7071f, 0.0f,  
                        0.0f, 1.0f, 0.7071f, 0.0f, 0.7071f
                    )

                6 ->         // 6 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_LEFT, BACK_RIGHT]
                    return floatArrayOf( 1.0f,
                        0.0f,
                        0.7071f,
                        0.5f,
                        0.7071f,
                        0.0f,  
                        0.0f,
                        1.0f,
                        0.7071f,
                        0.5f,
                        0.0f,
                        0.7071f
                    )

                else -> throw UnsupportedOperationException(
                    ("Default constant power channel mixing coefficients for "
                            + inputChannelCount
                            + "->2 are not implemented.")
                )
            }
        }
    }
}
