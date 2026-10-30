package androidx.media3.common.text

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import androidx.annotation.ColorInt
import androidx.annotation.IntDef
import androidx.media3.common.text.CustomSpanBundler.bundleCustomSpans
import androidx.media3.common.text.CustomSpanBundler.unbundleAndApplyCustomSpan
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import org.checkerframework.dataflow.qual.Pure
import java.io.ByteArrayOutputStream
import java.util.Objects

class Cue private constructor(
    text: CharSequence?,
    textAlignment: Layout.Alignment?,
    multiRowAlignment: Layout.Alignment?,
    bitmap: Bitmap?,
    line: Float,
    @LineType lineType: @LineType Int,
    @AnchorType lineAnchor: @AnchorType Int,
    position: Float,
    @AnchorType positionAnchor: @AnchorType Int,
    @TextSizeType textSizeType: @TextSizeType Int,
    textSize: Float,
    size: Float,
    bitmapHeight: Float,
    windowColorSet: Boolean,
    windowColor: Int,
    @VerticalType verticalType: @VerticalType Int,
    shearDegrees: Float,
    zIndex: Int
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [TYPE_UNSET, ANCHOR_TYPE_START, ANCHOR_TYPE_MIDDLE, ANCHOR_TYPE_END])
    annotation class AnchorType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [TYPE_UNSET, LINE_TYPE_FRACTION, LINE_TYPE_NUMBER])
    annotation class LineType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [TYPE_UNSET, TEXT_SIZE_TYPE_FRACTIONAL, TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING, TEXT_SIZE_TYPE_ABSOLUTE
        ]
    )
    annotation class TextSizeType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [TYPE_UNSET, VERTICAL_TYPE_RL, VERTICAL_TYPE_LR
        ]
    )
    annotation class VerticalType

    val text: CharSequence?
    val textAlignment: Layout.Alignment?
    val multiRowAlignment: Layout.Alignment?
    val bitmap: Bitmap?

    val line: Float

    @LineType
    val lineType: @LineType Int

    @AnchorType
    val lineAnchor: @AnchorType Int

    val position: Float

    @AnchorType
    val positionAnchor: @AnchorType Int

    val size: Float

    val bitmapHeight: Float
    val windowColorSet: Boolean
    val windowColor: Int

    @TextSizeType
    val textSizeType: @TextSizeType Int
    val textSize: Float

    @VerticalType
    val verticalType: @VerticalType Int
    val shearDegrees: Float
    val zIndex: Int

    fun buildUpon(): Builder {
        return Cue.Builder(this)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val that = obj as Cue
        return TextUtils.equals(text, that.text)
                && textAlignment == that.textAlignment && multiRowAlignment == that.multiRowAlignment && (if (bitmap == null)
            that.bitmap == null
        else
            (that.bitmap != null && bitmap.sameAs(that.bitmap)))
                && line == that.line && lineType == that.lineType && lineAnchor == that.lineAnchor && position == that.position && positionAnchor == that.positionAnchor && size == that.size && bitmapHeight == that.bitmapHeight && windowColorSet == that.windowColorSet && windowColor == that.windowColor && textSizeType == that.textSizeType && textSize == that.textSize && verticalType == that.verticalType && shearDegrees == that.shearDegrees && zIndex == that.zIndex
    }

    override fun hashCode(): Int {
        return Objects.hash(
            text,
            textAlignment,
            multiRowAlignment,
            bitmap,
            line,
            lineType,
            lineAnchor,
            position,
            positionAnchor,
            size,
            bitmapHeight,
            windowColorSet,
            windowColor,
            textSizeType,
            textSize,
            verticalType,
            shearDegrees,
            zIndex
        )
    }

    class Builder {
        @get:Pure
        var text: CharSequence?
            private set

        @get:Pure
        var bitmap: Bitmap?
            private set

        @get:Pure
        var textAlignment: Layout.Alignment?
            private set
        private var multiRowAlignment: Layout.Alignment?

        @get:Pure
        var line: Float
            private set

        @get:LineType
        @get:Pure
        @LineType
        var lineType: @LineType Int
            private set

        @get:AnchorType
        @get:Pure
        @AnchorType
        var lineAnchor: @AnchorType Int
            private set

        @get:Pure
        var position: Float
            private set

        @get:AnchorType
        @get:Pure
        @AnchorType
        var positionAnchor: @AnchorType Int
            private set

        @get:TextSizeType
        @get:Pure
        @TextSizeType
        var textSizeType: @TextSizeType Int
            private set

        @get:Pure
        var textSize: Float
            private set

        @get:Pure
        var size: Float
            private set

        @get:Pure
        var bitmapHeight: Float
            private set
        var isWindowColorSet: Boolean
            private set

        @get:ColorInt
        @get:Pure
        @ColorInt
        var windowColor: Int
            private set

        @get:VerticalType
        @get:Pure
        @VerticalType
        var verticalType: @VerticalType Int
            private set
        private var shearDegrees = 0f

        @get:Pure
        var zIndex: Int = 0
            private set

        constructor() {
            text = null
            bitmap = null
            textAlignment = null
            multiRowAlignment = null
            line = DIMEN_UNSET
            lineType = TYPE_UNSET
            lineAnchor = TYPE_UNSET
            position = DIMEN_UNSET
            positionAnchor = TYPE_UNSET
            textSizeType = TYPE_UNSET
            textSize = DIMEN_UNSET
            size = DIMEN_UNSET
            bitmapHeight = DIMEN_UNSET
            this.isWindowColorSet = false
            windowColor = Color.BLACK
            verticalType = TYPE_UNSET
        }

        constructor(cue: Cue) {
            text = cue.text
            bitmap = cue.bitmap
            textAlignment = cue.textAlignment
            multiRowAlignment = cue.multiRowAlignment
            line = cue.line
            lineType = cue.lineType
            lineAnchor = cue.lineAnchor
            position = cue.position
            positionAnchor = cue.positionAnchor
            textSizeType = cue.textSizeType
            textSize = cue.textSize
            size = cue.size
            bitmapHeight = cue.bitmapHeight
            this.isWindowColorSet = cue.windowColorSet
            windowColor = cue.windowColor
            verticalType = cue.verticalType
            shearDegrees = cue.shearDegrees
            zIndex = cue.zIndex
        }

        fun setText(text: CharSequence?): Builder {
            this.text = text
            this.bitmap = null
            return this
        }

        fun setBitmap(bitmap: Bitmap?): Builder {
            this.bitmap = bitmap
            this.text = null
            return this
        }

        fun setTextAlignment(textAlignment: Layout.Alignment?): Builder {
            this.textAlignment = textAlignment
            return this
        }

        fun setMultiRowAlignment(multiRowAlignment: Layout.Alignment?): Builder {
            this.multiRowAlignment = multiRowAlignment
            return this
        }

        fun setLine(line: Float, @LineType lineType: @LineType Int): Builder {
            this.line = line
            this.lineType = lineType
            return this
        }

        fun setLineAnchor(@AnchorType lineAnchor: @AnchorType Int): Builder {
            this.lineAnchor = lineAnchor
            return this
        }

        fun setPosition(position: Float): Builder {
            this.position = position
            return this
        }

        fun setPositionAnchor(@AnchorType positionAnchor: @AnchorType Int): Builder {
            this.positionAnchor = positionAnchor
            return this
        }

        fun setTextSize(textSize: Float, @TextSizeType textSizeType: @TextSizeType Int): Builder {
            this.textSize = textSize
            this.textSizeType = textSizeType
            return this
        }

        fun setSize(size: Float): Builder {
            this.size = size
            return this
        }

        fun setBitmapHeight(bitmapHeight: Float): Builder {
            this.bitmapHeight = bitmapHeight
            return this
        }

        fun setWindowColor(@ColorInt windowColor: Int): Builder {
            this.windowColor = windowColor
            this.isWindowColorSet = true
            return this
        }

        fun clearWindowColor(): Builder {
            this.isWindowColorSet = false
            return this
        }

        fun setVerticalType(@VerticalType verticalType: @VerticalType Int): Builder {
            this.verticalType = verticalType
            return this
        }

        fun setShearDegrees(shearDegrees: Float): Builder {
            this.shearDegrees = shearDegrees
            return this
        }

        fun setZIndex(zIndex: Int): Builder {
            this.zIndex = zIndex
            return this
        }

        fun build(): Cue {
            return Cue(
                text,
                textAlignment,
                multiRowAlignment,
                bitmap,
                line,
                lineType,
                lineAnchor,
                position,
                positionAnchor,
                textSizeType,
                textSize,
                size,
                bitmapHeight,
                this.isWindowColorSet,
                windowColor,
                verticalType,
                shearDegrees,
                zIndex
            )
        }
    }

    init {
        // Exactly one of text or bitmap should be set.
        if (text == null) {
            bitmap!!
        } else {
            Preconditions.checkArgument(bitmap == null)
        }
        if (text is Spanned) {
            this.text = SpannedString.valueOf(text)
        } else if (text != null) {
            this.text = text.toString()
        } else {
            this.text = null
        }
        this.textAlignment = textAlignment
        this.multiRowAlignment = multiRowAlignment
        this.bitmap = bitmap
        this.line = line
        this.lineType = lineType
        this.lineAnchor = lineAnchor
        this.position = position
        this.positionAnchor = positionAnchor
        this.size = size
        this.bitmapHeight = bitmapHeight
        this.windowColorSet = windowColorSet
        this.windowColor = windowColor
        this.textSizeType = textSizeType
        this.textSize = textSize
        this.verticalType = verticalType
        this.shearDegrees = shearDegrees
        this.zIndex = zIndex
    }

    fun toSerializableBundle(): Bundle {
        val bundle = toBundleWithoutBitmap()
        if (bitmap != null) {
            val output = ByteArrayOutputStream()
            // The PNG format is lossless, and the quality parameter is ignored.
            Preconditions.checkState(
                bitmap.compress(
                    Bitmap.CompressFormat.PNG,  
                    0,
                    output
                )
            )
            bundle.putByteArray(FIELD_BITMAP_BYTES, output.toByteArray())
        }
        return bundle
    }

    fun toBinderBasedBundle(): Bundle {
        val bundle = toBundleWithoutBitmap()
        if (bitmap != null) {
            bundle.putParcelable(FIELD_BITMAP_PARCELABLE, bitmap)
        }
        return bundle
    }

    private fun toBundleWithoutBitmap(): Bundle {
        val bundle = Bundle()
        if (text != null) {
            bundle.putCharSequence(FIELD_TEXT, text)
            if (text is Spanned) {
                val customSpanBundles = bundleCustomSpans(text)
                if (!customSpanBundles.isEmpty()) {
                    bundle.putParcelableArrayList(FIELD_CUSTOM_SPANS, customSpanBundles)
                }
            }
        }
        bundle.putSerializable(FIELD_TEXT_ALIGNMENT, textAlignment)
        bundle.putSerializable(FIELD_MULTI_ROW_ALIGNMENT, multiRowAlignment)
        bundle.putFloat(FIELD_LINE, line)
        bundle.putInt(FIELD_LINE_TYPE, lineType)
        bundle.putInt(FIELD_LINE_ANCHOR, lineAnchor)
        bundle.putFloat(FIELD_POSITION, position)
        bundle.putInt(FIELD_POSITION_ANCHOR, positionAnchor)
        bundle.putInt(FIELD_TEXT_SIZE_TYPE, textSizeType)
        bundle.putFloat(FIELD_TEXT_SIZE, textSize)
        bundle.putFloat(FIELD_SIZE, size)
        bundle.putFloat(FIELD_BITMAP_HEIGHT, bitmapHeight)
        bundle.putBoolean(FIELD_WINDOW_COLOR_SET, windowColorSet)
        bundle.putInt(FIELD_WINDOW_COLOR, windowColor)
        bundle.putInt(FIELD_VERTICAL_TYPE, verticalType)
        bundle.putFloat(FIELD_SHEAR_DEGREES, shearDegrees)
        bundle.putInt(FIELD_Z_INDEX, zIndex)
        return bundle
    }

    companion object {
        const  val DIMEN_UNSET: Float = -Float.MAX_VALUE

        const  val TYPE_UNSET: Int = Int.MIN_VALUE

        const val ANCHOR_TYPE_START: Int = 0
        const val ANCHOR_TYPE_MIDDLE: Int = 1
        const val ANCHOR_TYPE_END: Int = 2

        const val LINE_TYPE_FRACTION: Int = 0
        const val LINE_TYPE_NUMBER: Int = 1

        const val TEXT_SIZE_TYPE_FRACTIONAL: Int = 0
        const val TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING: Int = 1
        const val TEXT_SIZE_TYPE_ABSOLUTE: Int = 2

        const val VERTICAL_TYPE_RL: Int = 1
        const val VERTICAL_TYPE_LR: Int = 2

        private val FIELD_TEXT = intToStringMaxRadix(0)
        private val FIELD_CUSTOM_SPANS = intToStringMaxRadix(17)
        private val FIELD_TEXT_ALIGNMENT = intToStringMaxRadix(1)
        private val FIELD_MULTI_ROW_ALIGNMENT = intToStringMaxRadix(2)
        private val FIELD_BITMAP_PARCELABLE = intToStringMaxRadix(3)
        private val FIELD_BITMAP_BYTES = intToStringMaxRadix(18)
        private val FIELD_LINE = intToStringMaxRadix(4)
        private val FIELD_LINE_TYPE = intToStringMaxRadix(5)
        private val FIELD_LINE_ANCHOR = intToStringMaxRadix(6)
        private val FIELD_POSITION = intToStringMaxRadix(7)
        private val FIELD_POSITION_ANCHOR = intToStringMaxRadix(8)
        private val FIELD_TEXT_SIZE_TYPE = intToStringMaxRadix(9)
        private val FIELD_TEXT_SIZE = intToStringMaxRadix(10)
        private val FIELD_SIZE = intToStringMaxRadix(11)
        private val FIELD_BITMAP_HEIGHT = intToStringMaxRadix(12)
        private val FIELD_WINDOW_COLOR = intToStringMaxRadix(13)
        private val FIELD_WINDOW_COLOR_SET = intToStringMaxRadix(14)
        private val FIELD_VERTICAL_TYPE = intToStringMaxRadix(15)
        private val FIELD_SHEAR_DEGREES = intToStringMaxRadix(16)
        private val FIELD_Z_INDEX = intToStringMaxRadix(19)

        fun fromBundle(bundle: Bundle): Cue {
            val builder = Builder()
            val text = bundle.getCharSequence(FIELD_TEXT)
            if (text != null) {
                builder.setText(text)
                val customSpanBundles = bundle.getParcelableArrayList<Bundle?>(FIELD_CUSTOM_SPANS)
                if (customSpanBundles != null) {
                    val textWithCustomSpans = SpannableString.valueOf(text)
                    for (customSpanBundle in customSpanBundles) {
                        unbundleAndApplyCustomSpan(customSpanBundle, textWithCustomSpans)
                    }
                    builder.setText(textWithCustomSpans)
                }
            }
            val textAlignment = bundle.getSerializable(FIELD_TEXT_ALIGNMENT) as Layout.Alignment?
            if (textAlignment != null) {
                builder.setTextAlignment(textAlignment)
            }
            val multiRowAlignment =
                bundle.getSerializable(FIELD_MULTI_ROW_ALIGNMENT) as Layout.Alignment?
            if (multiRowAlignment != null) {
                builder.setMultiRowAlignment(multiRowAlignment)
            }
            val bitmap = bundle.getParcelable<Bitmap?>(FIELD_BITMAP_PARCELABLE)
            if (bitmap != null) {
                builder.setBitmap(bitmap)
            } else {
                val bitmapBytes = bundle.getByteArray(FIELD_BITMAP_BYTES)
                if (bitmapBytes != null) {
                    builder.setBitmap(
                        BitmapFactory.decodeByteArray(
                            bitmapBytes,  
                            0,
                            bitmapBytes.size
                        )
                    )
                }
            }
            if (bundle.containsKey(FIELD_LINE) && bundle.containsKey(FIELD_LINE_TYPE)) {
                builder.setLine(bundle.getFloat(FIELD_LINE), bundle.getInt(FIELD_LINE_TYPE))
            }
            if (bundle.containsKey(FIELD_LINE_ANCHOR)) {
                builder.setLineAnchor(bundle.getInt(FIELD_LINE_ANCHOR))
            }
            if (bundle.containsKey(FIELD_POSITION)) {
                builder.setPosition(bundle.getFloat(FIELD_POSITION))
            }
            if (bundle.containsKey(FIELD_POSITION_ANCHOR)) {
                builder.setPositionAnchor(bundle.getInt(FIELD_POSITION_ANCHOR))
            }
            if (bundle.containsKey(FIELD_TEXT_SIZE) && bundle.containsKey(FIELD_TEXT_SIZE_TYPE)) {
                builder.setTextSize(
                    bundle.getFloat(FIELD_TEXT_SIZE),
                    bundle.getInt(FIELD_TEXT_SIZE_TYPE)
                )
            }
            if (bundle.containsKey(FIELD_SIZE)) {
                builder.setSize(bundle.getFloat(FIELD_SIZE))
            }
            if (bundle.containsKey(FIELD_BITMAP_HEIGHT)) {
                builder.setBitmapHeight(bundle.getFloat(FIELD_BITMAP_HEIGHT))
            }
            if (bundle.containsKey(FIELD_WINDOW_COLOR)) {
                builder.setWindowColor(bundle.getInt(FIELD_WINDOW_COLOR))
            }
            if (!bundle.getBoolean(FIELD_WINDOW_COLOR_SET,  false)) {
                builder.clearWindowColor()
            }
            if (bundle.containsKey(FIELD_VERTICAL_TYPE)) {
                builder.setVerticalType(bundle.getInt(FIELD_VERTICAL_TYPE))
            }
            if (bundle.containsKey(FIELD_SHEAR_DEGREES)) {
                builder.setShearDegrees(bundle.getFloat(FIELD_SHEAR_DEGREES))
            }
            if (bundle.containsKey(FIELD_Z_INDEX)) {
                builder.setZIndex(bundle.getInt(FIELD_Z_INDEX))
            }
            return builder.build()
        }
    }
}
