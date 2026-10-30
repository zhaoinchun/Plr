package androidx.media3.common.util

import android.os.Trace
import androidx.media3.common.MediaLibraryInfo

object TraceUtil {
    fun beginSection(sectionName: String) {
        if (MediaLibraryInfo.TRACE_ENABLED) {
            Trace.beginSection(sectionName)
        }
    }

    fun endSection() {
        if (MediaLibraryInfo.TRACE_ENABLED) {
            Trace.endSection()
        }
    }
}
