
package androidx.media3.extractor.text

import androidx.media3.decoder.Decoder

interface SubtitleDecoder

    : Decoder<SubtitleInputBuffer, SubtitleOutputBuffer, SubtitleDecoderException> {
    
    fun setPositionUs(positionUs: Long)
}
