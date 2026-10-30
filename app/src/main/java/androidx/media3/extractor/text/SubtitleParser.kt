package androidx.media3.extractor.text

import androidx.media3.common.C
import androidx.media3.common.Format

interface SubtitleParser {
    
    interface Factory {
        
        fun supportsFormat(format: Format): Boolean

        fun getCueReplacementBehavior(format: Format): @Format.CueReplacementBehavior Int

        fun create(format: Format): SubtitleParser?

        companion object {

            val UNSUPPORTED: Factory = object : Factory {
                override fun supportsFormat(format: Format): Boolean {
                    return false
                }

                override fun getCueReplacementBehavior(format: Format): @Format.CueReplacementBehavior Int {
                    return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
                }

                override fun create(format: Format): SubtitleParser? {
                    throw IllegalStateException(
                        "This SubtitleParser.Factory doesn't support any formats."
                    )
                }
            }
        }
    }

    class OutputOptions private constructor(
        
        val startTimeUs: Long,
        
        val outputAllCues: Boolean
    ) {
        companion object {
             val ALL = OutputOptions(C.TIME_UNSET,  false)

            fun allCues(): OutputOptions {
                return ALL
            }

            fun onlyCuesAfter(startTimeUs: Long): OutputOptions {
                return OutputOptions(startTimeUs,  false)
            }

            fun cuesAfterThenRemainingCuesBefore(startTimeUs: Long): OutputOptions {
                return OutputOptions(startTimeUs,  true)
            }
        }
    }

    fun parse(data: ByteArray, outputOptions: OutputOptions, output: ((CuesWithTiming)-> Unit)) {
        parse(data,  0, data.size, outputOptions, output)
    }

    fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: OutputOptions,
        output: ((CuesWithTiming)-> Unit)
    )

    fun parseToLegacySubtitle(data: ByteArray, offset: Int, length: Int): Subtitle {
        val cuesWithTimingList = mutableListOf<CuesWithTiming>()
        parse(data, offset, length, OutputOptions.ALL, cuesWithTimingList::add)
        return CuesWithTimingSubtitle(cuesWithTimingList)
    }

    fun reset() {}

    val cueReplacementBehavior:  Int
}
