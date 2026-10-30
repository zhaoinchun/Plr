package androidx.media3.extractor.metadata.dvbsi

import androidx.media3.common.Metadata

class AppInfoTable(val controlCode: Int, val url: String?) : Metadata.Entry {
    override fun toString(): String {
        return "Ait(controlCode=" + controlCode + ",url=" + url + ")"
    }

    companion object {
        
        const val CONTROL_CODE_AUTOSTART: Int = 0x01

        const val CONTROL_CODE_PRESENT: Int = 0x02
    }
}
