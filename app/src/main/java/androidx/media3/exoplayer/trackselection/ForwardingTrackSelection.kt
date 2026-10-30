package androidx.media3.exoplayer.trackselection

open class ForwardingTrackSelection(val wrappedInstance: ExoTrackSelection) :
    ExoTrackSelection by wrappedInstance {

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is ForwardingTrackSelection) {
            return false
        }
        return this.wrappedInstance == obj.wrappedInstance
    }

    override fun hashCode(): Int {
        return wrappedInstance.hashCode()
    }
}
