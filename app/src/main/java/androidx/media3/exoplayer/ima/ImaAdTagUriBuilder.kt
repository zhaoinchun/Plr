package androidx.media3.exoplayer.ima

import android.net.Uri
import androidx.media3.common.C
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.VideoOrientation

class ImaAdTagUriBuilder(private val uri: Uri) {
    private var preferredLinearOrientation: Int = IMA_ORIENTATION_UNSET

    fun setPreferredLinearOrientation(
        preferredLinearOrientation: Int
    ): ImaAdTagUriBuilder {
        this.preferredLinearOrientation = preferredLinearOrientation
        return this
    }

    
    fun build(): Uri? {
        val builder = Uri.Builder().scheme(C.CSAI_SCHEME).authority(IMA_AUTHORITY)
        builder.appendQueryParameter(URI, uri.toString())
        builder.appendQueryParameter(VIDEO_ORIENTATION_KEY, preferredLinearOrientation.toString())
        return builder.build()
    }

    companion object {
        /* package */
        const val IMA_AUTHORITY: String = "ima.google.com"
        private const val URI = "uri"
        private const val VIDEO_ORIENTATION_KEY = "videoOrientation"

        // Unset orientation.
        const val IMA_ORIENTATION_UNSET: Int = 0

        // Landscape orientation.
        const val IMA_ORIENTATION_LANDSCAPE: Int = 1

        // Portrait orientation.
        const val IMA_ORIENTATION_PORTRAIT: Int = 2

        // Square orientation.
        const val IMA_ORIENTATION_SQUARE: Int = 3

        /* package */
        fun createAdsRequest(imaFactory: ImaUtil.ImaFactory, uri: Uri): AdsRequest {
            val request: AdsRequest = imaFactory.createAdsRequest()
            val adTagUrl = uri.getQueryParameter(URI)
            if (adTagUrl != null) {
                request.setAdTagUrl(adTagUrl)
            }
            val videoOrientation = uri.getQueryParameter(VIDEO_ORIENTATION_KEY)
            if (videoOrientation != null) {
                request.setPreferredLinearOrientation(fromImaOrientation(videoOrientation.toInt()))
            }
            return request
        }

        
        private fun fromImaOrientation(imaOrientation: Int): VideoOrientation {
            when (imaOrientation) {
                IMA_ORIENTATION_LANDSCAPE -> return VideoOrientation.LANDSCAPE
                IMA_ORIENTATION_PORTRAIT -> return VideoOrientation.PORTRAIT
                IMA_ORIENTATION_SQUARE -> return VideoOrientation.SQUARE
                else -> return VideoOrientation.UNSET
            }
        }
    }
}
