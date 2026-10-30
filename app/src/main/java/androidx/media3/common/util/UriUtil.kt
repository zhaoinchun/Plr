package androidx.media3.common.util

import android.net.Uri
import android.text.TextUtils
import com.google.common.base.Ascii
import kotlin.math.min

object UriUtil {
    private const val INDEX_COUNT = 4
    private const val SCHEME_COLON = 0
    private const val PATH = 1
    private const val QUERY = 2
    private const val FRAGMENT = 3

    fun resolveToUri(baseUri: String?, referenceUri: String?): Uri? {
        return Uri.parse(resolve(baseUri, referenceUri))
    }

    fun resolve(baseUri: String?, referenceUri: String?): String {
        var baseUri = baseUri
        var referenceUri = referenceUri
        val uri = StringBuilder()

        // Map null onto empty string, to make the following logic simpler.
        baseUri = if (baseUri == null) "" else baseUri
        referenceUri = if (referenceUri == null) "" else referenceUri

        val refIndices = getUriIndices(referenceUri)
        if (refIndices[SCHEME_COLON] != -1) {
            // The reference is absolute. The target Uri is the reference.
            uri.append(referenceUri)
            removeDotSegments(uri, refIndices[PATH], refIndices[QUERY])
            return uri.toString()
        }

        val baseIndices = getUriIndices(baseUri)
        if (refIndices[FRAGMENT] == 0) {
            // The reference is empty or contains just the fragment part, then the target Uri is the
            // concatenation of the base Uri without its fragment, and the reference.
            return uri.append(baseUri, 0, baseIndices[FRAGMENT]).append(referenceUri).toString()
        }

        if (refIndices[QUERY] == 0) {
            // The reference starts with the query part. The target is the base up to (but excluding) the
            // query, plus the reference.
            return uri.append(baseUri, 0, baseIndices[QUERY]).append(referenceUri).toString()
        }

        if (refIndices[PATH] != 0) {
            // The reference has authority. The target is the base scheme plus the reference.
            val baseLimit = baseIndices[SCHEME_COLON] + 1
            uri.append(baseUri, 0, baseLimit).append(referenceUri)
            return removeDotSegments(
                uri,
                baseLimit + refIndices[PATH],
                baseLimit + refIndices[QUERY]
            )
        }

        if (referenceUri.get(refIndices[PATH]) == '/') {
            // The reference path is rooted. The target is the base scheme and authority (if any), plus
            // the reference.
            uri.append(baseUri, 0, baseIndices[PATH]).append(referenceUri)
            return removeDotSegments(uri, baseIndices[PATH], baseIndices[PATH] + refIndices[QUERY])
        }

        // The target Uri is the concatenation of the base Uri up to (but excluding) the last segment,
        // and the reference. This can be split into 2 cases:
        if (baseIndices[SCHEME_COLON] + 2 < baseIndices[PATH]
            && baseIndices[PATH] == baseIndices[QUERY]
        ) {
            // Case 1: The base hier-part is just the authority, with an empty path. An additional '/' is
            // needed after the authority, before appending the reference.
            uri.append(baseUri, 0, baseIndices[PATH]).append('/').append(referenceUri)
            return removeDotSegments(
                uri,
                baseIndices[PATH],
                baseIndices[PATH] + refIndices[QUERY] + 1
            )
        } else {
            // Case 2: Otherwise, find the last '/' in the base hier-part and append the reference after
            // it. If base hier-part has no '/', it could only mean that it is completely empty or
            // contains only one segment, in which case the whole hier-part is excluded and the reference
            // is appended right after the base scheme colon without an added '/'.
            val lastSlashIndex = baseUri.lastIndexOf('/', baseIndices[QUERY] - 1)
            val baseLimit = if (lastSlashIndex == -1) baseIndices[PATH] else lastSlashIndex + 1
            uri.append(baseUri, 0, baseLimit).append(referenceUri)
            return removeDotSegments(uri, baseIndices[PATH], baseLimit + refIndices[QUERY])
        }
    }

    fun isAbsolute(uri: String?): Boolean {
        return uri != null && getUriIndices(uri)[SCHEME_COLON] != -1
    }

    fun removeQueryParameter(uri: Uri, queryParameterName: String?): Uri {
        val builder = uri.buildUpon()
        builder.clearQuery()
        for (key in uri.getQueryParameterNames()) {
            if (key != queryParameterName) {
                for (value in uri.getQueryParameters(key)) {
                    builder.appendQueryParameter(key, value)
                }
            }
        }
        return builder.build()
    }

    private fun removeDotSegments(uri: StringBuilder, offset: Int, limit: Int): String {
        var offset = offset
        var limit = limit
        if (offset >= limit) {
            // Nothing to do.
            return uri.toString()
        }
        if (uri.get(offset) == '/') {
            // If the path starts with a /, always retain it.
            offset++
        }
        // The first character of the current path segment.
        var segmentStart = offset
        var i = offset
        while (i <= limit) {
            val nextSegmentStart: Int
            if (i == limit) {
                nextSegmentStart = i
            } else if (uri[i] == '/') {
                nextSegmentStart = i + 1
            } else {
                i++
                continue
            }
            // We've encountered the end of a segment or the end of the path. If the final segment was
            // "." or "..", remove the appropriate segments of the path.
            if (i == segmentStart + 1 && uri.get(segmentStart) == '.') {
                // Given "abc/def/./ghi", remove "./" to get "abc/def/ghi".
                uri.delete(segmentStart, nextSegmentStart)
                limit -= nextSegmentStart - segmentStart
                i = segmentStart
            } else if (i == segmentStart + 2 && uri.get(segmentStart) == '.' && uri.get(segmentStart + 1) == '.') {
                // Given "abc/def/../ghi", remove "def/../" to get "abc/ghi".
                val prevSegmentStart = uri.lastIndexOf("/", segmentStart - 2) + 1
                val removeFrom = if (prevSegmentStart > offset) prevSegmentStart else offset
                uri.delete(removeFrom, nextSegmentStart)
                limit -= nextSegmentStart - removeFrom
                segmentStart = prevSegmentStart
                i = prevSegmentStart
            } else {
                i++
                segmentStart = i
            }
        }
        return uri.toString()
    }

    private fun getUriIndices(uriString: String?): IntArray {
        val indices = IntArray(INDEX_COUNT)
        if (TextUtils.isEmpty(uriString)) {
            indices[SCHEME_COLON] = -1
            return indices
        }

        // Determine outer structure from right to left.
        // Uri = scheme ":" hier-part [ "?" query ] [ "#" fragment ]
        val length = uriString!!.length
        var fragmentIndex = uriString.indexOf('#')
        if (fragmentIndex == -1) {
            fragmentIndex = length
        }
        var queryIndex = uriString.indexOf('?')
        if (queryIndex == -1 || queryIndex > fragmentIndex) {
            // '#' before '?': '?' is within the fragment.
            queryIndex = fragmentIndex
        }
        // Slashes are allowed only in hier-part so any colon after the first slash is part of the
        // hier-part, not the scheme colon separator.
        var schemeIndexLimit = uriString.indexOf('/')
        if (schemeIndexLimit == -1 || schemeIndexLimit > queryIndex) {
            schemeIndexLimit = queryIndex
        }
        var schemeIndex = uriString.indexOf(':')
        if (schemeIndex > schemeIndexLimit) {
            // '/' before ':'
            schemeIndex = -1
        }

        // Determine hier-part structure: hier-part = "//" authority path / path
        // This block can also cope with schemeIndex == -1.
        val hasAuthority =
            schemeIndex + 2 < queryIndex && uriString.get(schemeIndex + 1) == '/' && uriString.get(
                schemeIndex + 2
            ) == '/'
        var pathIndex: Int
        if (hasAuthority) {
            pathIndex = uriString.indexOf('/', schemeIndex + 3) // find first '/' after "://"
            if (pathIndex == -1 || pathIndex > queryIndex) {
                pathIndex = queryIndex
            }
        } else {
            pathIndex = schemeIndex + 1
        }

        indices[SCHEME_COLON] = schemeIndex
        indices[PATH] = pathIndex
        indices[QUERY] = queryIndex
        indices[FRAGMENT] = fragmentIndex
        return indices
    }

    fun getRelativePath(baseUri: Uri, targetUri: Uri): String {
        if (baseUri.isOpaque() || targetUri.isOpaque()) {
            return targetUri.toString()
        }

        val baseUriScheme = baseUri.getScheme()
        val targetUriScheme = targetUri.getScheme()
        val isSameScheme =
            if (baseUriScheme == null)
                targetUriScheme == null
            else
                targetUriScheme != null && Ascii.equalsIgnoreCase(baseUriScheme, targetUriScheme)
        if (!isSameScheme || baseUri.getAuthority() != targetUri.getAuthority()) {
            // Different schemes or authorities, cannot find relative path, return targetUri.
            return targetUri.toString()
        }

        val basePathSegments = baseUri.getPathSegments()
        val targetPathSegments = targetUri.getPathSegments()

        var commonPrefixCount = 0
        val minSize = min(basePathSegments.size, targetPathSegments.size)

        for (i in 0..<minSize) {
            if (basePathSegments[i] != targetPathSegments[i]) {
                break
            }
            commonPrefixCount++
        }

        val relativePath = StringBuilder()
        for (i in commonPrefixCount..<basePathSegments.size) {
            relativePath.append("../")
        }

        for (i in commonPrefixCount..<targetPathSegments.size) {
            relativePath.append(targetPathSegments[i])
            if (i < targetPathSegments.size - 1) {
                relativePath.append("/")
            }
        }

        return relativePath.toString()
    }
}
