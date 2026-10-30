package androidx.media3.datasource

import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import com.google.common.net.HttpHeaders
import java.net.CookieHandler
import java.net.URI
import java.util.regex.Pattern
import kotlin.math.max

object HttpUtil {
    private const val TAG = "HttpUtil"
    private val CONTENT_RANGE_WITH_START_AND_END: Pattern =
        Pattern.compile("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)")
    private val CONTENT_RANGE_WITH_SIZE: Pattern =
        Pattern.compile("bytes (?:(?:\\d+-\\d+)|\\*)/(\\d+)")

    fun buildRangeRequestHeader(position: Long, length: Long): String? {
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) {
            return null
        }
        val rangeValue = StringBuilder()
        rangeValue.append("bytes=")
        rangeValue.append(position)
        rangeValue.append("-")
        if (length != C.LENGTH_UNSET.toLong()) {
            rangeValue.append(position + length - 1)
        }
        return rangeValue.toString()
    }

    fun getDocumentSize(contentRangeHeader: String?): Long {
        if (TextUtils.isEmpty(contentRangeHeader)) {
            return C.LENGTH_UNSET.toLong()
        }
        val matcher = CONTENT_RANGE_WITH_SIZE.matcher(contentRangeHeader)
        return if (matcher.matches()) matcher.group(1)!!.toLong() else C.LENGTH_UNSET.toLong()
    }

    fun getContentLength(
        contentLengthHeader: String?, contentRangeHeader: String?
    ): Long {
        var contentLength = C.LENGTH_UNSET.toLong()
        if (!TextUtils.isEmpty(contentLengthHeader)) {
            try {
                contentLength = contentLengthHeader!!.toLong()
            } catch (e: NumberFormatException) {
                e(TAG, "Unexpected Content-Length [" + contentLengthHeader + "]")
            }
        }
        if (!TextUtils.isEmpty(contentRangeHeader)) {
            val matcher = CONTENT_RANGE_WITH_START_AND_END.matcher(contentRangeHeader)
            if (matcher.matches()) {
                try {
                    val contentLengthFromRange =
                        matcher.group(2)!!.toLong() - matcher.group(1)!!.toLong() + 1
                    if (contentLength < 0) {
                        // Some proxy servers strip the Content-Length header. Fall back to the length
                        // calculated here in this case.
                        contentLength = contentLengthFromRange
                    } else if (contentLength != contentLengthFromRange) {
                        // If there is a discrepancy between the Content-Length and Content-Range headers,
                        // assume the one with the larger value is correct. We have seen cases where carrier
                        // change one of them to reduce the size of a request, but it is unlikely anybody would
                        // increase it.
                        w(
                            TAG,
                            "Inconsistent headers [" + contentLengthHeader + "] [" + contentRangeHeader + "]"
                        )
                        contentLength = max(contentLength, contentLengthFromRange)
                    }
                } catch (e: NumberFormatException) {
                    e(TAG, "Unexpected Content-Range [" + contentRangeHeader + "]")
                }
            }
        }
        return contentLength
    }

    fun storeCookiesFromHeaders(
        url: String?,
        headers: MutableMap<String?, MutableList<String?>?>?,
        cookieHandler: CookieHandler?
    ) {
        if (cookieHandler == null) {
            return
        }

        try {
            cookieHandler.put(URI(url), headers)
        } catch (e: Exception) {
            w(TAG, "Failed to store cookies in CookieHandler", e)
        }
    }

    fun getCookieHeader(
        url: String?,
        headers: MutableMap<String?, MutableList<String?>?>?,
        cookieHandler: CookieHandler?
    ): String {
        if (cookieHandler == null) {
            return ""
        }

        var cookieHeaders: MutableMap<String?, MutableList<String?>?> = mutableMapOf<String?, MutableList<String?>?>()
        try {
            cookieHeaders = cookieHandler.get(URI(url), headers)
        } catch (e: Exception) {
            w(TAG, "Failed to read cookies from CookieHandler", e)
        }

        // This maps Set-Cookie2 (RFC 2965) to Cookie just like CookieManager does.
        val cookies = StringBuilder()
        if (cookieHeaders.containsKey(HttpHeaders.COOKIE)) {
            val cookiesList = cookieHeaders.get(HttpHeaders.COOKIE)
            if (cookiesList != null) {
                for (cookie in cookiesList) {
                    cookies.append(cookie).append("; ")
                }
            }
        }
        return cookies.toString().trimEnd()
    }
}
