package androidx.media3.extractor.metadata.id3

import androidx.media3.common.Label

import androidx.media3.extractor.metadata.Chapter
import com.google.common.base.Preconditions

class ChapterFrame(
    chapterId: String?,
    startTimeMs: Int,
    endTimeMs: Int,
    startOffset: Long,
    endOffset: Long,
    subFrames: Array<Id3Frame?>
) : Id3Frame(ID), Chapter {
    val chapterId: String?
    override val startTimeMs = startTimeMs.toLong()
    override val endTimeMs=startTimeMs.toLong()
    override val title: Label?
    override val isHidden: Boolean=false

    val startOffset: Long
    val endOffset: Long

    private val subFrames: Array<Id3Frame?>

    init {
        Preconditions.checkArgument(startTimeMs <= endTimeMs)
        this.chapterId = chapterId
        val title: String? = findTitle(subFrames)
        this.title = if (title != null) Label( null, title) else null
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.subFrames = subFrames
    }

    val subFrameCount: Int
        
        get() = subFrames.size

    fun getSubFrame(index: Int): Id3Frame? {
        return subFrames[index]
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as ChapterFrame
        return startTimeMs == other.startTimeMs && endTimeMs == other.endTimeMs && startOffset == other.startOffset && endOffset == other.endOffset && chapterId == other.chapterId
                && subFrames.contentEquals(other.subFrames)
    }

    override fun hashCode(): Int {
        var result = 17
        result = (31 * result + startTimeMs).toInt()
        result = (31 * result + endTimeMs).toInt()
        result = 31 * result + startOffset.toInt()
        result = 31 * result + endOffset.toInt()
        result = 31 * result + (if (chapterId != null) chapterId.hashCode() else 0)
        return result
    }

    companion object {
        const val ID: String = "CHAP"

        private fun findTitle(subFrames: Array<Id3Frame?>): String? {
            for (frame in subFrames) {
                if (frame is TextInformationFrame
                    && frame.id == "TIT2"
                ) {
                    val textFrame = frame
                    if (!textFrame.values.isEmpty()) {
                        return textFrame.values[0]
                    }
                }
            }
            return null
        }
    }
}
