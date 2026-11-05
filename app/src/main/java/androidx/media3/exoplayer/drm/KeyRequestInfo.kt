package androidx.media3.exoplayer.drm

import androidx.media3.common.DrmInitData
import androidx.media3.exoplayer.source.LoadEventInfo

class KeyRequestInfo {
    var loadInfos: MutableList<LoadEventInfo> = mutableListOf()
    var schemeDatas: List<DrmInitData.SchemeData?>? = null

    fun addLoadInfo(loadEventInfo: LoadEventInfo): KeyRequestInfo {
        loadInfos.add(loadEventInfo)
        return this
    }
}
