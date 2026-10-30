package androidx.media3.datasource.cache

import androidx.media3.common.C
import androidx.media3.common.util.Util.unescapeFileName
import com.google.common.base.Preconditions
import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class SimpleCacheSpan
private constructor(
    key: String,
    position: Long,
    length: Long,
    lastTouchTimestamp: Long,
    file: File?
) : CacheSpan(key, position, length, lastTouchTimestamp, file) {

    fun copyWithFileAndLastTouchTimestamp(file: File?, lastTouchTimestamp: Long): SimpleCacheSpan {
        Preconditions.checkState(isCached)
        return SimpleCacheSpan(key, position, length, lastTouchTimestamp, file)
    }

    companion object {

        const val COMMON_SUFFIX: String = ".exo"

        private val SUFFIX = ".v3" + COMMON_SUFFIX
        private val CACHE_FILE_PATTERN_V1: Pattern =
            Pattern.compile("^(.+)\\.(\\d+)\\.(\\d+)\\.v1\\.exo$", Pattern.DOTALL)
        private val CACHE_FILE_PATTERN_V2: Pattern =
            Pattern.compile("^(.+)\\.(\\d+)\\.(\\d+)\\.v2\\.exo$", Pattern.DOTALL)
        private val CACHE_FILE_PATTERN_V3: Pattern =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)\\.v3\\.exo$", Pattern.DOTALL)

        fun getCacheFile(cacheDir: File?, id: Int, position: Long, timestamp: Long): File {
            return File(cacheDir, id.toString() + "." + position + "." + timestamp + SUFFIX)
        }

        fun createLookup(key: String, position: Long): SimpleCacheSpan {
            return SimpleCacheSpan(key, position, C.LENGTH_UNSET.toLong(), C.TIME_UNSET, null)
        }

        fun createHole(key: String, position: Long, length: Long): SimpleCacheSpan {
            return SimpleCacheSpan(key, position, length, C.TIME_UNSET, null)
        }

        fun createCacheEntry(
            file: File,
            length: Long,
            index: CachedContentIndex
        ): SimpleCacheSpan? {
            return createCacheEntry(file, length,  C.TIME_UNSET, index)
        }

        fun createCacheEntry(
            file: File, length: Long, lastTouchTimestamp: Long, index: CachedContentIndex
        ): SimpleCacheSpan? {
            var file = file
            var length = length
            var lastTouchTimestamp = lastTouchTimestamp
            var name = file.getName()
            if (!name.endsWith(SUFFIX)) {
                val upgradedFile: File? = upgradeFile(file, index)
                if (upgradedFile == null) {
                    return null
                }
                file = upgradedFile
                name = file.getName()
            }

            val matcher: Matcher = CACHE_FILE_PATTERN_V3.matcher(name)
            if (!matcher.matches()) {
                return null
            }

            val id = matcher.group(1)!!.toInt()
            val key = index.getKeyForId(id) ?: return null

            if (length == C.LENGTH_UNSET.toLong()) {
                length = file.length()
            }
            if (length == 0L) {
                return null
            }

            val position = matcher.group(2)!!.toLong()
            if (lastTouchTimestamp == C.TIME_UNSET) {
                lastTouchTimestamp = matcher.group(3)!!.toLong()
            }
            return SimpleCacheSpan(key, position, length, lastTouchTimestamp, file)
        }

        private fun upgradeFile(file: File, index: CachedContentIndex): File? {
            var key: String? = null
            val filename = file.getName()
            var matcher: Matcher = CACHE_FILE_PATTERN_V2.matcher(filename)
            if (matcher.matches()) {
                key = unescapeFileName(matcher.group(1)!!)
            } else {
                matcher = CACHE_FILE_PATTERN_V1.matcher(filename)
                if (matcher.matches()) {
                    key = matcher.group(1)!!
                }
            }

            if (key == null) {
                return null
            }

            val newCacheFile: File =
                getCacheFile(
                    file.parentFile!!,
                    index.assignIdForKey(key),
                    matcher.group(2)!!.toLong(),
                    matcher.group(3)!!.toLong()
                )
            if (!file.renameTo(newCacheFile)) {
                return null
            }
            return newCacheFile
        }
    }
}
