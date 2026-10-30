package androidx.media3.common

interface Effect {
    fun getDurationAfterEffectApplied(durationUs: Long): Long {
        return durationUs
    }
}
