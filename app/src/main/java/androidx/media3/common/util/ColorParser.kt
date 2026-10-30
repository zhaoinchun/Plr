package androidx.media3.common.util

import android.graphics.Color
import android.text.TextUtils
import androidx.annotation.ColorInt
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import java.util.regex.Pattern

object ColorParser {
    private const val RGB = "rgb"
    private const val RGBA = "rgba"
    private val RGB_PATTERN: Pattern =
        Pattern.compile("^rgb\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)$")
    private val RGBA_PATTERN_INT_ALPHA: Pattern =
        Pattern.compile("^rgba\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)$")
    private val RGBA_PATTERN_FLOAT_ALPHA: Pattern =
        Pattern.compile("^rgba\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d*\\.?\\d*?)\\)$")

    private val COLOR_MAP = mutableMapOf<String, Int>()

    @ColorInt
    fun parseTtmlColor(colorExpression: String?): Int {
        return parseColorInternal(colorExpression, false)
    }

    @ColorInt
    fun parseCssColor(colorExpression: String?): Int {
        return parseColorInternal(colorExpression, true)
    }

    @ColorInt
    private fun parseColorInternal(colorExpression: String?, alphaHasFloatFormat: Boolean): Int {
        var colorExpression = colorExpression
        Preconditions.checkArgument(!TextUtils.isEmpty(colorExpression))
        colorExpression = colorExpression!!.replace(" ", "")
        if (colorExpression[0] == '#') {
            // Parse using Long to avoid failure when colorExpression is greater than #7FFFFFFF.
            var color = colorExpression.substring(1).toLong(16).toInt()
            if (colorExpression.length == 7) {
                // Set the alpha value
                color = color or -0x1000000
            } else if (colorExpression.length == 9) {
                // We have #RRGGBBAA, but we need #AARRGGBB
                color = ((color and 0xFF) shl 24) or (color ushr 8)
            } else {
                throw IllegalArgumentException()
            }
            return color
        } else if (colorExpression.startsWith(RGBA)) {
            val matcher =
                (if (alphaHasFloatFormat) RGBA_PATTERN_FLOAT_ALPHA else RGBA_PATTERN_INT_ALPHA)
                    .matcher(colorExpression)
            if (matcher.matches()) {
                val alpha = if (alphaHasFloatFormat) (255 * matcher.group(4)!!.toFloat()).toInt()
                else matcher.group(4)!!.toInt(10)
                return Color.argb(
                    alpha,
                    matcher.group(1)!!.toInt(10),
                    matcher.group(2)!!.toInt(10),
                    matcher.group(3)!!.toInt(10)
                )
            }
        } else if (colorExpression.startsWith(RGB)) {
            val matcher = RGB_PATTERN.matcher(colorExpression)
            if (matcher.matches()) {
                return Color.rgb(
                    matcher.group(1)!!.toInt(10),
                    matcher.group(2)!!.toInt(10),
                    matcher.group(3)!!.toInt(10)
                )
            }
        } else {
            // we use our own color map
            val color = COLOR_MAP[Ascii.toLowerCase(colorExpression)]
            if (color != null) {
                return color
            }
        }
        throw IllegalArgumentException()
    }

    init {
        COLOR_MAP["aliceblue"] = -0xf0701
        COLOR_MAP["antiquewhite"] = -0x51429
        COLOR_MAP["aqua"] = -0xff0001
        COLOR_MAP["aquamarine"] = -0x80002c
        COLOR_MAP["azure"] = -0xf0001
        COLOR_MAP["beige"] = -0xa0a24
        COLOR_MAP["bisque"] = -0x1b3c
        COLOR_MAP["black"] = -0x1000000
        COLOR_MAP["blanchedalmond"] = -0x1433
        COLOR_MAP["blue"] = -0xffff01
        COLOR_MAP["blueviolet"] = -0x75d41e
        COLOR_MAP["brown"] = -0x5ad5d6
        COLOR_MAP["burlywood"] = -0x214779
        COLOR_MAP["cadetblue"] = -0xa06160
        COLOR_MAP["chartreuse"] = -0x800100
        COLOR_MAP["chocolate"] = -0x2d96e2
        COLOR_MAP["coral"] = -0x80b0
        COLOR_MAP["cornflowerblue"] = -0x9b6a13
        COLOR_MAP["cornsilk"] = -0x724
        COLOR_MAP["crimson"] = -0x23ebc4
        COLOR_MAP["cyan"] = -0xff0001
        COLOR_MAP["darkblue"] = -0xffff75
        COLOR_MAP["darkcyan"] = -0xff7475
        COLOR_MAP["darkgoldenrod"] = -0x4779f5
        COLOR_MAP["darkgray"] = -0x565657
        COLOR_MAP["darkgreen"] = -0xff9c00
        COLOR_MAP["darkgrey"] = -0x565657
        COLOR_MAP["darkkhaki"] = -0x424895
        COLOR_MAP["darkmagenta"] = -0x74ff75
        COLOR_MAP["darkolivegreen"] = -0xaa94d1
        COLOR_MAP["darkorange"] = -0x7400
        COLOR_MAP["darkorchid"] = -0x66cd34
        COLOR_MAP["darkred"] = -0x750000
        COLOR_MAP["darksalmon"] = -0x166986
        COLOR_MAP["darkseagreen"] = -0x704371
        COLOR_MAP["darkslateblue"] = -0xb7c275
        COLOR_MAP["darkslategray"] = -0xd0b0b1
        COLOR_MAP["darkslategrey"] = -0xd0b0b1
        COLOR_MAP["darkturquoise"] = -0xff312f
        COLOR_MAP["darkviolet"] = -0x6bff2d
        COLOR_MAP["deeppink"] = -0xeb6d
        COLOR_MAP["deepskyblue"] = -0xff4001
        COLOR_MAP["dimgray"] = -0x969697
        COLOR_MAP["dimgrey"] = -0x969697
        COLOR_MAP["dodgerblue"] = -0xe16f01
        COLOR_MAP["firebrick"] = -0x4dddde
        COLOR_MAP["floralwhite"] = -0x510
        COLOR_MAP["forestgreen"] = -0xdd74de
        COLOR_MAP["fuchsia"] = -0xff01
        COLOR_MAP["gainsboro"] = -0x232324
        COLOR_MAP["ghostwhite"] = -0x70701
        COLOR_MAP["gold"] = -0x2900
        COLOR_MAP["goldenrod"] = -0x255ae0
        COLOR_MAP["gray"] = -0x7f7f80
        COLOR_MAP["green"] = -0xff8000
        COLOR_MAP["greenyellow"] = -0x5200d1
        COLOR_MAP["grey"] = -0x7f7f80
        COLOR_MAP["honeydew"] = -0xf0010
        COLOR_MAP["hotpink"] = -0x964c
        COLOR_MAP["indianred"] = -0x32a3a4
        COLOR_MAP["indigo"] = -0xb4ff7e
        COLOR_MAP["ivory"] = -0x10
        COLOR_MAP["khaki"] = -0xf1974
        COLOR_MAP["lavender"] = -0x191906
        COLOR_MAP["lavenderblush"] = -0xf0b
        COLOR_MAP["lawngreen"] = -0x830400
        COLOR_MAP["lemonchiffon"] = -0x533
        COLOR_MAP["lightblue"] = -0x52271a
        COLOR_MAP["lightcoral"] = -0xf7f80
        COLOR_MAP["lightcyan"] = -0x1f0001
        COLOR_MAP["lightgoldenrodyellow"] = -0x5052e
        COLOR_MAP["lightgray"] = -0x2c2c2d
        COLOR_MAP["lightgreen"] = -0x6f1170
        COLOR_MAP["lightgrey"] = -0x2c2c2d
        COLOR_MAP["lightpink"] = -0x493f
        COLOR_MAP["lightsalmon"] = -0x5f86
        COLOR_MAP["lightseagreen"] = -0xdf4d56
        COLOR_MAP["lightskyblue"] = -0x783106
        COLOR_MAP["lightslategray"] = -0x887767
        COLOR_MAP["lightslategrey"] = -0x887767
        COLOR_MAP["lightsteelblue"] = -0x4f3b22
        COLOR_MAP["lightyellow"] = -0x20
        COLOR_MAP["lime"] = -0xff0100
        COLOR_MAP["limegreen"] = -0xcd32ce
        COLOR_MAP["linen"] = -0x50f1a
        COLOR_MAP["magenta"] = -0xff01
        COLOR_MAP["maroon"] = -0x800000
        COLOR_MAP["mediumaquamarine"] = -0x993256
        COLOR_MAP["mediumblue"] = -0xffff33
        COLOR_MAP["mediumorchid"] = -0x45aa2d
        COLOR_MAP["mediumpurple"] = -0x6c8f25
        COLOR_MAP["mediumseagreen"] = -0xc34c8f
        COLOR_MAP["mediumslateblue"] = -0x849712
        COLOR_MAP["mediumspringgreen"] = -0xff0566
        COLOR_MAP["mediumturquoise"] = -0xb72e34
        COLOR_MAP["mediumvioletred"] = -0x38ea7b
        COLOR_MAP["midnightblue"] = -0xe6e690
        COLOR_MAP["mintcream"] = -0xa0006
        COLOR_MAP["mistyrose"] = -0x1b1f
        COLOR_MAP["moccasin"] = -0x1b4b
        COLOR_MAP["navajowhite"] = -0x2153
        COLOR_MAP["navy"] = -0xffff80
        COLOR_MAP["oldlace"] = -0x20a1a
        COLOR_MAP["olive"] = -0x7f8000
        COLOR_MAP["olivedrab"] = -0x9471dd
        COLOR_MAP["orange"] = -0x5b00
        COLOR_MAP["orangered"] = -0xbb00
        COLOR_MAP["orchid"] = -0x258f2a
        COLOR_MAP["palegoldenrod"] = -0x111756
        COLOR_MAP["palegreen"] = -0x670468
        COLOR_MAP["paleturquoise"] = -0x501112
        COLOR_MAP["palevioletred"] = -0x248f6d
        COLOR_MAP["papayawhip"] = -0x102b
        COLOR_MAP["peachpuff"] = -0x2547
        COLOR_MAP["peru"] = -0x327ac1
        COLOR_MAP["pink"] = -0x3f35
        COLOR_MAP["plum"] = -0x225f23
        COLOR_MAP["powderblue"] = -0x4f1f1a
        COLOR_MAP["purple"] = -0x7fff80
        COLOR_MAP["rebeccapurple"] = -0x99cc67
        COLOR_MAP["red"] = -0x10000
        COLOR_MAP["rosybrown"] = -0x437071
        COLOR_MAP["royalblue"] = -0xbe961f
        COLOR_MAP["saddlebrown"] = -0x74baed
        COLOR_MAP["salmon"] = -0x57f8e
        COLOR_MAP["sandybrown"] = -0xb5ba0
        COLOR_MAP["seagreen"] = -0xd174a9
        COLOR_MAP["seashell"] = -0xa12
        COLOR_MAP["sienna"] = -0x5fadd3
        COLOR_MAP["silver"] = -0x3f3f40
        COLOR_MAP["skyblue"] = -0x783115
        COLOR_MAP["slateblue"] = -0x95a533
        COLOR_MAP["slategray"] = -0x8f7f70
        COLOR_MAP["slategrey"] = -0x8f7f70
        COLOR_MAP["snow"] = -0x506
        COLOR_MAP["springgreen"] = -0xff0081
        COLOR_MAP["steelblue"] = -0xb97d4c
        COLOR_MAP["tan"] = -0x2d4b74
        COLOR_MAP["teal"] = -0xff7f80
        COLOR_MAP["thistle"] = -0x274028
        COLOR_MAP["tomato"] = -0x9cb9
        COLOR_MAP["transparent"] = 0x00000000
        COLOR_MAP["turquoise"] = -0xbf1f30
        COLOR_MAP["violet"] = -0x117d12
        COLOR_MAP["wheat"] = -0xa214d
        COLOR_MAP["white"] = -0x1
        COLOR_MAP["whitesmoke"] = -0xa0a0b
        COLOR_MAP["yellow"] = -0x100
        COLOR_MAP["yellowgreen"] = -0x6532ce
    }
}
