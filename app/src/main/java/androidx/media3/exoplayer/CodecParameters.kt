package androidx.media3.exoplayer

import android.media.MediaFormat
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.annotation.RestrictTo
import java.nio.ByteBuffer

data class CodecParameters(val params: MutableMap<String?, Any?>) {
    fun get(key: String?): Any? {
        return params.get(key)
    }

    fun keySet(): MutableSet<String?> {
        return params.keys
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
        val EMPTY: CodecParameters = CodecParameters(mutableMapOf())

        @RequiresApi(29)
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun createFrom(mediaFormat: MediaFormat, keys: Set<String>): CodecParameters {
            val params: MutableMap<String?, Any?> = mutableMapOf()
            for (key in keys) {
                if (mediaFormat.containsKey(key)) {
                    val type = mediaFormat.getValueTypeForKey(key)
                    when (type) {
                        MediaFormat.TYPE_INTEGER -> params[key] = mediaFormat.getInteger(key)
                        MediaFormat.TYPE_LONG -> params[key] = mediaFormat.getLong(key)
                        MediaFormat.TYPE_FLOAT -> params[key] = mediaFormat.getFloat(key)
                        MediaFormat.TYPE_STRING -> params[key] = mediaFormat.getString(key)
                        MediaFormat.TYPE_BYTE_BUFFER -> {
                            val value = mediaFormat.getByteBuffer(key)
                            if (value == null) {
                                params[key] = null
                            } else {
                                val clone = ByteBuffer.allocate(value.remaining())
                                clone.put(value.duplicate())
                                clone.flip()
                                params[key] = clone
                            }
                        }

                        else -> {}
                    }
                }
            }
            return CodecParameters(params)
        }
    }
}
