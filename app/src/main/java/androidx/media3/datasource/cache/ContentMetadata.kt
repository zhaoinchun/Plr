package androidx.media3.datasource.cache

import android.net.Uri
import androidx.media3.common.C

interface ContentMetadata {
    fun get(key: String?, defaultValue: ByteArray?): ByteArray?

    fun get(key: String?, defaultValue: String?): String?

    fun get(key: String?, defaultValue: Long): Long

    fun contains(key: String?): Boolean

    companion object {
        fun getContentLength(contentMetadata: ContentMetadata): Long {
            return contentMetadata.get(KEY_CONTENT_LENGTH, C.LENGTH_UNSET.toLong())
        }

        fun getRedirectedUri(contentMetadata: ContentMetadata): Uri? {
            val redirectedUri = contentMetadata.get(KEY_REDIRECTED_URI, null as String?)
            return if (redirectedUri == null) null else Uri.parse(redirectedUri)
        }

        @Suppress("unused")
        const val KEY_CUSTOM_PREFIX: String = "custom_"

        const val KEY_REDIRECTED_URI: String = "exo_redir"

        const val KEY_CONTENT_LENGTH: String = "exo_len"
    }
}
