package androidx.media3.common.util

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

object XmlPullParserUtil {
    @Throws(XmlPullParserException::class)
    fun isEndTag(xpp: XmlPullParser, name: String?): Boolean {
        return isEndTag(xpp) && xpp.getName() == name
    }

    @Throws(XmlPullParserException::class)
    fun isEndTag(xpp: XmlPullParser): Boolean {
        return xpp.getEventType() == XmlPullParser.END_TAG
    }

    @Throws(XmlPullParserException::class)
    fun isStartTag(xpp: XmlPullParser, name: String?): Boolean {
        return isStartTag(xpp) && xpp.getName() == name
    }

    @Throws(XmlPullParserException::class)
    fun isStartTag(xpp: XmlPullParser): Boolean {
        return xpp.getEventType() == XmlPullParser.START_TAG
    }

    @Throws(XmlPullParserException::class)
    fun isStartTagIgnorePrefix(xpp: XmlPullParser, name: String?): Boolean {
        return isStartTag(xpp) && stripPrefix(xpp.getName()) == name
    }

    fun getAttributeValue(xpp: XmlPullParser, attributeName: String?): String? {
        val attributeCount = xpp.getAttributeCount()
        for (i in 0..<attributeCount) {
            if (xpp.getAttributeName(i) == attributeName) {
                return xpp.getAttributeValue(i)
            }
        }
        return null
    }

    fun getAttributeValueIgnorePrefix(xpp: XmlPullParser, attributeName: String?): String? {
        val attributeCount = xpp.getAttributeCount()
        for (i in 0..<attributeCount) {
            if (stripPrefix(xpp.getAttributeName(i)) == attributeName) {
                return xpp.getAttributeValue(i)
            }
        }
        return null
    }

    private fun stripPrefix(name: String): String {
        val prefixSeparatorIndex = name.indexOf(':')
        return if (prefixSeparatorIndex == -1) name else name.substring(prefixSeparatorIndex + 1)
    }
}
