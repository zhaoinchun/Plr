package androidx.media3.exoplayer

import android.media.MediaFormat
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.annotation.RestrictTo

import java.nio.ByteBuffer
import java.util.Collections

class CodecParameters private constructor(params: MutableMap<String?, Any?>) {
    private val params: MutableMap<String?, Any?>

    init {
        this.params = Collections.unmodifiableMap<String?, Any?>(params)
    }

    fun buildUpon(): Builder {
        return CodecParameters.Builder(this)
    }

    class Builder {
        private val params: MutableMap<String?, Any?>

        constructor() {
            params = HashMap<String?, Any?>()
        }

        constructor(codecParameters: CodecParameters) {
            this.params = HashMap<String?, Any?>(codecParameters.params)
        }

        fun setInteger(key: String?, value: Int): Builder {
            params.put(key, value)
            return this
        }

        fun setLong(key: String?, value: Long): Builder {
            params.put(key, value)
            return this
        }

        fun setFloat(key: String?, value: Float): Builder {
            params.put(key, value)
            return this
        }

        fun setString(key: String?, value: String?): Builder {
            params.put(key, value)
            return this
        }

        fun setByteBuffer(key: String?, value: ByteBuffer?): Builder {
            if (value == null) {
                params.put(key, null)
            } else {
                val clone = ByteBuffer.allocate(value.remaining())
                clone.put(value.duplicate())
                clone.flip()
                params.put(key, clone)
            }
            return this
        }

        fun remove(key: String?): Builder {
            params.remove(key)
            return this
        }

        fun build(): CodecParameters {
            return CodecParameters(params)
        }
    }

    fun get(key: String?): Any? {
        return params.get(key)
    }

    fun keySet(): MutableSet<String?> {
        return params.keys
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is CodecParameters) {
            return false
        }
        val other = obj
        return this.params == other.params
    }

    override fun hashCode(): Int {
        return params.hashCode()
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun toBundle(): Bundle {
        val bundle = Bundle()
        for (entry in params.entries) {
            val key = entry.key
            val value = entry.value
            if (value == null) {
                continue
            }
            if (value is Int) {
                bundle.putInt(key, value)
            } else if (value is Long) {
                bundle.putLong(key, value)
            } else if (value is Float) {
                bundle.putFloat(key, value)
            } else if (value is String) {
                bundle.putString(key, value)
            } else if (value is ByteBuffer) {
                val byteBuffer = value
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.duplicate().get(bytes)
                bundle.putByteArray(key, bytes)
            }
        }
        return bundle
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun applyTo(mediaFormat: MediaFormat) {
        for (entry in params.entries) {
            val key: String = entry.key!!
            val value = entry.value
            if (value == null) {
                mediaFormat.setString(key, null)
                continue
            }
            if (value is Int) {
                mediaFormat.setInteger(key, value)
            } else if (value is Long) {
                mediaFormat.setLong(key, value)
            } else if (value is Float) {
                mediaFormat.setFloat(key, value)
            } else if (value is String) {
                mediaFormat.setString(key, value)
            } else if (value is ByteBuffer) {
                mediaFormat.setByteBuffer(key, value)
            }
        }
    }

    companion object {
        val EMPTY: CodecParameters = Builder().build()

        @RequiresApi(29)
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun createFrom(mediaFormat: MediaFormat, keys: Set<String>): Builder {
            val builder = Builder()
            for (key in keys) {
                if (mediaFormat.containsKey(key)) {
                    val type = mediaFormat.getValueTypeForKey(key)
                    when (type) {
                        MediaFormat.TYPE_INTEGER -> builder.setInteger(
                            key,
                            mediaFormat.getInteger(key)
                        )

                        MediaFormat.TYPE_LONG -> builder.setLong(key, mediaFormat.getLong(key))
                        MediaFormat.TYPE_FLOAT -> builder.setFloat(key, mediaFormat.getFloat(key))
                        MediaFormat.TYPE_STRING -> builder.setString(
                            key,
                            mediaFormat.getString(key)
                        )

                        MediaFormat.TYPE_BYTE_BUFFER -> builder.setByteBuffer(
                            key,
                            mediaFormat.getByteBuffer(key)
                        )

                        else -> {}
                    }
                }
            }
            return builder
        }
    }
}
