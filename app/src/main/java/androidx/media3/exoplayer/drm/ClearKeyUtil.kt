
package androidx.media3.exoplayer.drm

import android.os.Build
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Util.fromUtf8Bytes
import androidx.media3.common.util.Util.getUtf8Bytes
import org.json.JSONException
import org.json.JSONObject

internal object ClearKeyUtil {
    private const val TAG = "ClearKeyUtil"

    fun adjustRequestData(request: ByteArray): ByteArray {
        if (Build.VERSION.SDK_INT >= 27) {
            return request
        }
        // Prior to O-MR1 the ClearKey CDM encoded the values in the "kids" array using Base64 encoding
        // rather than Base64Url encoding. See [Internal: b/64388098]. We know the exact request format
        // from the platform's InitDataParser.cpp. Since there aren't any "+" or "/" symbols elsewhere
        // in the request, it's safe to fix the encoding by replacement through the whole request.
        val requestString = fromUtf8Bytes(request)
        return getUtf8Bytes(base64ToBase64Url(requestString))
    }

    fun adjustResponseData(response: ByteArray): ByteArray{
        if (Build.VERSION.SDK_INT >= 27) {
            return response
        }
        // Prior to O-MR1 the ClearKey CDM expected Base64 encoding rather than Base64Url encoding for
        // the "k" and "kid" strings. See [Internal: b/64388098]. We know that the ClearKey CDM only
        // looks at the k, kid and kty parameters in each key, so can ignore the rest of the response.
        try {
            val responseJson = JSONObject(fromUtf8Bytes(response))
            val adjustedResponseBuilder = StringBuilder("{\"keys\":[")
            val keysArray = responseJson.getJSONArray("keys")
            for (i in 0..<keysArray.length()) {
                if (i != 0) {
                    adjustedResponseBuilder.append(",")
                }
                val key = keysArray.getJSONObject(i)
                adjustedResponseBuilder.append("{\"k\":\"")
                adjustedResponseBuilder.append(base64UrlToBase64(key.getString("k")))
                adjustedResponseBuilder.append("\",\"kid\":\"")
                adjustedResponseBuilder.append(base64UrlToBase64(key.getString("kid")))
                adjustedResponseBuilder.append("\",\"kty\":\"")
                adjustedResponseBuilder.append(key.getString("kty"))
                adjustedResponseBuilder.append("\"}")
            }
            adjustedResponseBuilder.append("]}")
            return getUtf8Bytes(adjustedResponseBuilder.toString())
        } catch (e: JSONException) {
            e(TAG, "Failed to adjust response data: " + fromUtf8Bytes(response), e)
            return response
        }
    }

    private fun base64ToBase64Url(base64: String): String {
        return base64.replace('+', '-').replace('/', '_')
    }

    private fun base64UrlToBase64(base64Url: String): String {
        return base64Url.replace('-', '+').replace('_', '/')
    }
}
