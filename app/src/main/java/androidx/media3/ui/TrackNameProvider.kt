package androidx.media3.ui

import androidx.media3.common.Format

interface TrackNameProvider {
    
    fun getTrackName(format: Format): String
}
