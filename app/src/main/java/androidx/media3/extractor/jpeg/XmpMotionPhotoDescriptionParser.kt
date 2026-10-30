package androidx.media3.extractor.jpeg

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.common.util.XmlPullParserUtil
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

internal object XmpMotionPhotoDescriptionParser {
    @Throws(IOException::class)
    fun parse(xmpString: String?): MotionPhotoDescription? {
        try {
            return parseInternal(xmpString)
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "Ignoring unexpected XMP metadata")
            return null
        } catch (e: ParserException) {
            Log.w(TAG, "Ignoring unexpected XMP metadata")
            return null
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Ignoring unexpected XMP metadata")
            return null
        }
    }

    fun isMotionPhotoXmp(xmpString: String?): Boolean {
        if (xmpString == null) {
            return false
        }
        for (attributeName in MOTION_PHOTO_ATTRIBUTE_NAMES) {
            if (xmpString.contains(attributeName + "=\"1\"")) {
                return true
            }
        }
        return false
    }

    private const val TAG = "MotionPhotoXmpParser"

    private val MOTION_PHOTO_ATTRIBUTE_NAMES: Array<String> = arrayOf(
        "Camera:MotionPhoto",  // Motion Photo V1
        "GCamera:MotionPhoto",  // Motion Photo V1 (legacy element naming)
        "Camera:MicroVideo",  // Micro Video V1b
        "GCamera:MicroVideo",  // Micro Video V1b (legacy element naming)
    )
    private val DESCRIPTION_MOTION_PHOTO_PRESENTATION_TIMESTAMP_ATTRIBUTE_NAMES: Array<String> =
        arrayOf(
            "Camera:MotionPhotoPresentationTimestampUs",  // Motion Photo V1
            "GCamera:MotionPhotoPresentationTimestampUs",  // Motion Photo V1 (legacy element naming)
            "Camera:MicroVideoPresentationTimestampUs",  // Micro Video V1b
            "GCamera:MicroVideoPresentationTimestampUs",  // Micro Video V1b (legacy element naming)
        )
    private val DESCRIPTION_MICRO_VIDEO_OFFSET_ATTRIBUTE_NAMES: Array<String> = arrayOf(
        "Camera:MicroVideoOffset",  // Micro Video V1b
        "GCamera:MicroVideoOffset",  // Micro Video V1b (legacy element naming)
    )

    @Throws(XmlPullParserException::class, IOException::class)
    private fun parseInternal(xmpString: String?): MotionPhotoDescription? {
        val xmlPullParserFactory = XmlPullParserFactory.newInstance()
        val xpp = xmlPullParserFactory.newPullParser()
        xpp.setInput(StringReader(xmpString))
        xpp.next()
        if (!XmlPullParserUtil.isStartTag(xpp, "x:xmpmeta")) {
            throw ParserException.createForMalformedContainer(
                "Couldn't find xmp metadata",  null
            )
        }
        var motionPhotoPresentationTimestampUs = C.TIME_UNSET
        var containerItems: MutableList<MotionPhotoDescription.ContainerItem> = mutableListOf()
        do {
            xpp.next()
            if (XmlPullParserUtil.isStartTag(xpp, "rdf:Description")) {
                if (!parseMotionPhotoFlagFromDescription(xpp)) {
                    // The motion photo flag is not set, so the file should not be treated as a motion photo.
                    return null
                }
                motionPhotoPresentationTimestampUs =
                    parseMotionPhotoPresentationTimestampUsFromDescription(xpp)
                containerItems = parseMicroVideoOffsetFromDescription(xpp)
            } else if (XmlPullParserUtil.isStartTag(xpp, "Container:Directory")) {
                containerItems = parseMotionPhotoV1Directory(xpp, "Container", "Item")
            } else if (XmlPullParserUtil.isStartTag(xpp, "GContainer:Directory")) {
                containerItems = parseMotionPhotoV1Directory(xpp, "GContainer", "GContainerItem")
            }
        } while (!XmlPullParserUtil.isEndTag(xpp, "x:xmpmeta"))
        if (containerItems.isEmpty()) {
            // No motion photo information was parsed.
            return null
        }
        return MotionPhotoDescription(motionPhotoPresentationTimestampUs, containerItems)
    }

    private fun parseMotionPhotoFlagFromDescription(xpp: XmlPullParser): Boolean {
        for (attributeName in MOTION_PHOTO_ATTRIBUTE_NAMES) {
            val attributeValue: String? = XmlPullParserUtil.getAttributeValue(xpp, attributeName)
            if (attributeValue != null) {
                val motionPhotoFlag = attributeValue.toInt()
                return motionPhotoFlag == 1
            }
        }
        return false
    }

    private fun parseMotionPhotoPresentationTimestampUsFromDescription(xpp: XmlPullParser): Long {
        for (attributeName in DESCRIPTION_MOTION_PHOTO_PRESENTATION_TIMESTAMP_ATTRIBUTE_NAMES) {
            val attributeValue: String? = XmlPullParserUtil.getAttributeValue(xpp, attributeName)
            if (attributeValue != null) {
                val presentationTimestampUs = attributeValue.toLong()
                return if (presentationTimestampUs == -1L) C.TIME_UNSET else presentationTimestampUs
            }
        }
        return C.TIME_UNSET
    }

    private fun parseMicroVideoOffsetFromDescription(xpp: XmlPullParser): MutableList<MotionPhotoDescription.ContainerItem> {
        // We store a new Motion Photo item list based on the MicroVideo offset, so that the same
        // representation is used for both specifications.
        for (attributeName in DESCRIPTION_MICRO_VIDEO_OFFSET_ATTRIBUTE_NAMES) {
            val attributeValue: String? = XmlPullParserUtil.getAttributeValue(xpp, attributeName)
            if (attributeValue != null) {
                val microVideoOffset = attributeValue.toLong()
                return mutableListOf(
                    MotionPhotoDescription.ContainerItem(
                        MimeTypes.IMAGE_JPEG, "Primary",  0,  0
                    ),
                    MotionPhotoDescription.ContainerItem(
                        MimeTypes.VIDEO_MP4,
                        "MotionPhoto",  
                        microVideoOffset,  
                        0
                    )
                )
            }
        }
        return mutableListOf()
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun parseMotionPhotoV1Directory(
        xpp: XmlPullParser, containerNamespacePrefix: String?, itemNamespacePrefix: String?
    ): MutableList<MotionPhotoDescription.ContainerItem> {
        val containerItems=mutableListOf<MotionPhotoDescription.ContainerItem>()
        val itemTagName = containerNamespacePrefix + ":Item"
        val directoryTagName = containerNamespacePrefix + ":Directory"
        do {
            xpp.next()
            if (XmlPullParserUtil.isStartTag(xpp, itemTagName)) {
                val mimeAttributeName = itemNamespacePrefix + ":Mime"
                val semanticAttributeName = itemNamespacePrefix + ":Semantic"
                val lengthAttributeName = itemNamespacePrefix + ":Length"
                val paddinghAttributeName = itemNamespacePrefix + ":Padding"
                val mime: String? = XmlPullParserUtil.getAttributeValue(xpp, mimeAttributeName)
                val semantic: String? =
                    XmlPullParserUtil.getAttributeValue(xpp, semanticAttributeName)
                val length: String? = XmlPullParserUtil.getAttributeValue(xpp, lengthAttributeName)
                val padding: String? =
                    XmlPullParserUtil.getAttributeValue(xpp, paddinghAttributeName)
                if (mime == null || semantic == null) {
                    // Required values are missing.
                    return mutableListOf()
                }
                containerItems.add(
                    MotionPhotoDescription.ContainerItem(
                        mime,
                        semantic,
                        if (length != null) length.toLong() else 0,
                        if (padding != null) padding.toLong() else 0
                    )
                )
            }
        } while (!XmlPullParserUtil.isEndTag(xpp, directoryTagName))
        return containerItems
    }
}
