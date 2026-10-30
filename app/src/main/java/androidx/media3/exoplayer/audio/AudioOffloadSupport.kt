package androidx.media3.exoplayer.audio

data class AudioOffloadSupport private constructor(
    var isFormatSupported: Boolean,
    var isGaplessSupported: Boolean,
    var isSpeedChangeSupported: Boolean,
) {
    private constructor(builder: Builder) : this(
        builder.isFormatSupported,
        builder.isGaplessSupported,
        builder.isSpeedChangeSupported,
    )

    class Builder {
        var isFormatSupported = false
        var isGaplessSupported = false
        var isSpeedChangeSupported = false

        constructor()

        fun setIsFormatSupported(isFormatSupported: Boolean): Builder {
            this.isFormatSupported = isFormatSupported
            return this
        }

        fun setIsGaplessSupported(isGaplessSupported: Boolean): Builder {
            this.isGaplessSupported = isGaplessSupported
            return this
        }

        fun setIsSpeedChangeSupported(isSpeedChangeSupported: Boolean): Builder {
            this.isSpeedChangeSupported = isSpeedChangeSupported
            return this
        }

        fun build(): AudioOffloadSupport {
            check(!(!isFormatSupported && (isGaplessSupported || isSpeedChangeSupported))) { "Secondary offload attribute fields are true but primary isFormatSupported is false" }
            return AudioOffloadSupport(this)
        }
    }

    companion object {
        val DEFAULT_UNSUPPORTED: AudioOffloadSupport = Builder().build()
    }
}
