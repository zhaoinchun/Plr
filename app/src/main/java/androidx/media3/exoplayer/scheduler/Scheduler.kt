package androidx.media3.exoplayer.scheduler

interface Scheduler {
    fun schedule(
        requirements: Requirements,
        servicePackage: String,
        serviceAction: String
    ): Boolean

    fun cancel(): Boolean
    fun getSupportedRequirements(requirements: Requirements): Requirements
}
