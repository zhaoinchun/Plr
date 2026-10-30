
package androidx.media3.exoplayer.drm

import androidx.media3.common.DrmInitData

import androidx.media3.exoplayer.source.LoadEventInfo
import com.google.errorprone.annotations.CanIgnoreReturnValue

import org.checkerframework.dataflow.qual.SideEffectFree

class KeyRequestInfo private constructor(builder: Builder) {
    class Builder {
         val loadEventInfos = mutableListOf<LoadEventInfo>()
        var schemeDatas:List<DrmInitData.SchemeData?>? = null

        fun setSchemeDatas(schemeDatas: List<DrmInitData.SchemeData>): Builder {
            this.schemeDatas = schemeDatas.toList()
            return this
        }

        fun addLoadInfo(loadEventInfo: LoadEventInfo): Builder {
            this.loadEventInfos.add(loadEventInfo)
            return this
        }

        @SideEffectFree
        fun build(): KeyRequestInfo {
            return KeyRequestInfo(this)
        }
    }

    val loadInfos: List<LoadEventInfo?> = builder.loadEventInfos.toList()
    val schemeDatas: List<DrmInitData.SchemeData?>? = builder.schemeDatas

}
