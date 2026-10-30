package androidx.media3.common.text

import android.os.Bundle
import android.text.Spannable
import android.text.Spanned
import androidx.annotation.IntDef
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions

internal object CustomSpanBundler {
    private const val UNKNOWN = -1
    private const val RUBY = 1
    private const val TEXT_EMPHASIS = 2
    private const val HORIZONTAL_TEXT_IN_VERTICAL_CONTEXT = 3
    private const val VOICE = 4

    private val FIELD_START_INDEX = intToStringMaxRadix(0)
    private val FIELD_END_INDEX = intToStringMaxRadix(1)
    private val FIELD_FLAGS = intToStringMaxRadix(2)
    private val FIELD_TYPE = intToStringMaxRadix(3)
    private val FIELD_PARAMS = intToStringMaxRadix(4)

    // Intentionally using ArrayList for putParcelableArrayList.
    fun bundleCustomSpans(text: Spanned): ArrayList<Bundle> {
        val bundledCustomSpans = ArrayList<Bundle>()
        for (span in text.getSpans(0, text.length, RubySpan::class.java)) {
            val bundle =
                spanToBundle(text, span,  RUBY,  span.toBundle())
            bundledCustomSpans.add(bundle)
        }
        for (span in text.getSpans(
            0,
            text.length,
            TextEmphasisSpan::class.java
        )) {
            val bundle =
                spanToBundle(
                    text,
                    span,  
                    TEXT_EMPHASIS,  
                    span.toBundle()
                )
            bundledCustomSpans.add(bundle)
        }
        for (span in text.getSpans(
            0,
            text.length,
            HorizontalTextInVerticalContextSpan::class.java
        )) {
            val bundle =
                spanToBundle(
                    text,
                    span,  
                    HORIZONTAL_TEXT_IN_VERTICAL_CONTEXT,  
                    null
                )
            bundledCustomSpans.add(bundle)
        }
        for (span in text.getSpans(0, text.length, VoiceSpan::class.java)) {
            val bundle =
                spanToBundle(text, span,  VOICE,  span.toBundle())
            bundledCustomSpans.add(bundle)
        }
        return bundledCustomSpans
    }

    fun unbundleAndApplyCustomSpan(customSpanBundle: Bundle, text: Spannable) {
        val start = customSpanBundle.getInt(FIELD_START_INDEX)
        val end = customSpanBundle.getInt(FIELD_END_INDEX)
        val flags = customSpanBundle.getInt(FIELD_FLAGS)
        val customSpanType = customSpanBundle.getInt(FIELD_TYPE, UNKNOWN)
        val span = customSpanBundle.getBundle(FIELD_PARAMS)
        when (customSpanType) {
            RUBY -> text.setSpan(
                RubySpan.fromBundle(span!!),
                start,
                end,
                flags
            )

            TEXT_EMPHASIS -> text.setSpan(
                TextEmphasisSpan.fromBundle(span!!), start, end, flags
            )

            HORIZONTAL_TEXT_IN_VERTICAL_CONTEXT -> text.setSpan(
                HorizontalTextInVerticalContextSpan(),
                start,
                end,
                flags
            )

            VOICE -> text.setSpan(
                VoiceSpan.fromBundle(span!!),
                start,
                end,
                flags
            )

            else -> {}
        }
    }

    private fun spanToBundle(
        spanned: Spanned, span: Any?, spanType: @CustomSpanType Int, params: Bundle?
    ): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_START_INDEX, spanned.getSpanStart(span))
        bundle.putInt(FIELD_END_INDEX, spanned.getSpanEnd(span))
        bundle.putInt(FIELD_FLAGS, spanned.getSpanFlags(span))
        bundle.putInt(FIELD_TYPE, spanType)
        if (params != null) {
            bundle.putBundle(FIELD_PARAMS, params)
        }
        return bundle
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [UNKNOWN, RUBY, TEXT_EMPHASIS, HORIZONTAL_TEXT_IN_VERTICAL_CONTEXT, VOICE])
    private annotation class CustomSpanType
}
