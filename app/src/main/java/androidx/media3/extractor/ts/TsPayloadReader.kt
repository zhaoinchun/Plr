
package androidx.media3.extractor.ts

import android.util.SparseArray
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ExtractorOutput
import java.util.Collections

interface TsPayloadReader {
    
    interface Factory {
        
        fun createInitialPayloadReaders(): SparseArray<TsPayloadReader>

        fun createPayloadReader(streamType: Int, esInfo: EsInfo): TsPayloadReader?
    }

    class EsInfo(
        val streamType: Int,
        val language: String?,
        val audioType: @AudioType Int,
        dvbSubtitleInfos: MutableList<DvbSubtitleInfo?>?,
        val descriptorBytes: ByteArray
    ) {
        
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [AUDIO_TYPE_UNDEFINED, AUDIO_TYPE_CLEAN_EFFECTS, AUDIO_TYPE_HEARING_IMPAIRED, AUDIO_TYPE_VISUAL_IMPAIRED_COMMENTARY
            ]
        )
        annotation class AudioType

        @get:C.RoleFlags
        val roleFlags: Int
            get() {
                when (audioType) {
                    AUDIO_TYPE_HEARING_IMPAIRED -> return C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY
                    AUDIO_TYPE_VISUAL_IMPAIRED_COMMENTARY -> return C.ROLE_FLAG_DESCRIBES_VIDEO
                    else -> return 0
                }
            }

        val dvbSubtitleInfos: MutableList<DvbSubtitleInfo>

        init {
            this.dvbSubtitleInfos =
                if (dvbSubtitleInfos == null) mutableListOf<DvbSubtitleInfo>() else
                    Collections.unmodifiableList<DvbSubtitleInfo>(dvbSubtitleInfos)
        }

        companion object {
            const val AUDIO_TYPE_UNDEFINED: Int = 0

            const val AUDIO_TYPE_CLEAN_EFFECTS: Int = 1

            const val AUDIO_TYPE_HEARING_IMPAIRED: Int = 2

            const val AUDIO_TYPE_VISUAL_IMPAIRED_COMMENTARY: Int = 3
        }
    }

    class DvbSubtitleInfo
    (val language: String?, val type: Int, val initializationData: ByteArray)

    class TrackIdGenerator(
        programNumber: Int,
        private val firstTrackId: Int,
        private val trackIdIncrement: Int
    ) {
        private val formatIdPrefix: String
        private var trackId: Int
        private var formatId: String? = ""

        constructor(firstTrackId: Int, trackIdIncrement: Int) : this(
            ID_UNSET,
            firstTrackId,
            trackIdIncrement
        )

        init {
            this.formatIdPrefix =
                if (programNumber != ID_UNSET) programNumber.toString() + "/" else ""
            trackId = ID_UNSET
        }

        fun generateNewId() {
            trackId = if (trackId == ID_UNSET) firstTrackId else trackId + trackIdIncrement
            formatId = formatIdPrefix + trackId
        }

        fun getTrackId(): Int {
            maybeThrowUninitializedError()
            return trackId
        }

        fun getFormatId(): String? {
            maybeThrowUninitializedError()
            return formatId
        }

        private fun maybeThrowUninitializedError() {
            check(trackId != ID_UNSET) { "generateNewId() must be called before retrieving ids." }
        }

        companion object {
            private val ID_UNSET = Int.MIN_VALUE
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_PAYLOAD_UNIT_START_INDICATOR, FLAG_RANDOM_ACCESS_INDICATOR, FLAG_DATA_ALIGNMENT_INDICATOR
        ]
    )
    annotation class Flags

    fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TrackIdGenerator
    )

    fun seek()

    @Throws(ParserException::class)
    fun consume(data: ParsableByteArray, flags: @Flags Int)

    companion object {
        const val FLAG_PAYLOAD_UNIT_START_INDICATOR: Int = 1
        const val FLAG_RANDOM_ACCESS_INDICATOR: Int = 1 shl 1
        const  val FLAG_DATA_ALIGNMENT_INDICATOR: Int = 1 shl 2
    }
}
