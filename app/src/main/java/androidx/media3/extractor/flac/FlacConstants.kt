
package androidx.media3.extractor.flac

object FlacConstants {
    const val STREAM_MARKER_SIZE: Int = 4
    const val METADATA_BLOCK_HEADER_SIZE: Int = 4
    const val STREAM_INFO_BLOCK_SIZE: Int = 38
    const val MIN_FRAME_HEADER_SIZE: Int = 6
    const val MAX_FRAME_HEADER_SIZE: Int = 16
    const val METADATA_TYPE_STREAM_INFO: Int = 0
    const val METADATA_TYPE_SEEK_TABLE: Int = 3
    const val METADATA_TYPE_VORBIS_COMMENT: Int = 4
    const val METADATA_TYPE_PICTURE: Int = 6
}
