package androidx.media3.exoplayer.upstream.contentsteering

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import androidx.media3.common.C
import androidx.media3.common.ParserException

import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest.PathwayClone
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest.UriReplacement
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

class SteeringManifestParser : ParsingLoadable.Parser<SteeringManifest?> {
    @Throws(IOException::class)
    override fun parse(uri: Uri?, inputStream: InputStream?): SteeringManifest {
        JsonReader(InputStreamReader(inputStream)).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw ParserException.createForMalformedSteeringManifest(
                    "Steering manifest JSON should be an object at root",  null
                )
            }
            var version = 1 // Default version == 1.
            var timeToLiveMs = C.TIME_UNSET
            var reloadUri: Uri? = null
            val pathwayPriority = mutableListOf<String?>()
            val pathwayClones = mutableListOf<PathwayClone?>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == STEERING_MANIFEST_JSON_NAME_VERSION
                    && reader.peek() == JsonToken.NUMBER
                ) {
                    version = reader.nextInt()
                } else if (name == STEERING_MANIFEST_JSON_NAME_TTL
                    && reader.peek() == JsonToken.NUMBER
                ) {
                    timeToLiveMs = reader.nextInt() * 1000L
                } else if (name == STEERING_MANIFEST_JSON_NAME_RELOAD_URI
                    && reader.peek() == JsonToken.STRING
                ) {
                    reloadUri = Uri.parse(reader.nextString())
                } else if (name == STEERING_MANIFEST_JSON_NAME_PATHWAY_PRIORITY
                    && reader.peek() == JsonToken.BEGIN_ARRAY
                ) {
                    parsePathwayPriorityArray(reader, pathwayPriority)
                } else if (name == STEERING_MANIFEST_JSON_NAME_PATHWAY_CLONES
                    && reader.peek() == JsonToken.BEGIN_ARRAY
                ) {
                    parsePathwayClonesArray(reader, pathwayClones)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            val pathwayPriorityList = pathwayPriority
            if (pathwayPriorityList.isEmpty()) {
                throw ParserException.createForMalformedSteeringManifest(
                    "PATHWAY-PRIORITY field is missing",  null
                )
            }
            return SteeringManifest(
                version, timeToLiveMs, reloadUri, pathwayPriorityList, pathwayClones
            )
        }
    }

    private fun interface StringConverter<T> {
        fun convert(string: String?): T?
    }

    companion object {
        private const val STEERING_MANIFEST_JSON_NAME_VERSION = "VERSION"
        private const val STEERING_MANIFEST_JSON_NAME_TTL = "TTL"
        private const val STEERING_MANIFEST_JSON_NAME_RELOAD_URI = "RELOAD-URI"
        private const val STEERING_MANIFEST_JSON_NAME_PATHWAY_PRIORITY = "PATHWAY-PRIORITY"
        private const val STEERING_MANIFEST_JSON_NAME_PATHWAY_CLONES = "PATHWAY-CLONES"
        private const val STEERING_MANIFEST_JSON_NAME_BASE_ID = "BASE-ID"
        private const val STEERING_MANIFEST_JSON_NAME_ID = "ID"
        private const val STEERING_MANIFEST_JSON_NAME_URI_REPLACEMENT = "URI-REPLACEMENT"
        private const val STEERING_MANIFEST_JSON_NAME_HOST = "HOST"
        private const val STEERING_MANIFEST_JSON_NAME_PARAMS = "PARAMS"
        private const val STEERING_MANIFEST_JSON_NAME_PER_VARIANT_URIS = "PER-VARIANT-URIS"
        private const val STEERING_MANIFEST_JSON_NAME_PER_RENDITION_URIS = "PER-RENDITION-URIS"

        @Throws(IOException::class)
        private fun parsePathwayClone(reader: JsonReader): PathwayClone {
            reader.beginObject()
            var baseId: String? = null
            var id: String? = null
            var uriReplacement: UriReplacement? = null
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == STEERING_MANIFEST_JSON_NAME_BASE_ID
                    && reader.peek() == JsonToken.STRING
                ) {
                    baseId = reader.nextString()
                } else if (name == STEERING_MANIFEST_JSON_NAME_ID
                    && reader.peek() == JsonToken.STRING
                ) {
                    id = reader.nextString()
                } else if (name == STEERING_MANIFEST_JSON_NAME_URI_REPLACEMENT
                    && reader.peek() == JsonToken.BEGIN_OBJECT
                ) {
                    uriReplacement = parseUriReplacement(reader)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            if (baseId == null) {
                throw ParserException.createForMalformedSteeringManifest(
                    "BASE-ID field is missing in a PATHWAY-CLONE object",  null
                )
            }
            if (id == null) {
                throw ParserException.createForMalformedSteeringManifest(
                    "ID field is missing in a PATHWAY-CLONE object",  null
                )
            }
            if (uriReplacement == null) {
                throw ParserException.createForMalformedSteeringManifest(
                    "URI-REPLACEMENT field is missing in a PATHWAY-CLONE object",  null
                )
            }
            return PathwayClone(baseId, id, uriReplacement)
        }

        @Throws(IOException::class)
        private fun parseUriReplacement(reader: JsonReader): UriReplacement {
            reader.beginObject()
            var host: String? = null
            val params: MutableMap<String?, String?> = HashMap<String?, String?>()
            val perVariantUris: MutableMap<String?, Uri?> = HashMap<String?, Uri?>()
            val perRenditionUris: MutableMap<String?, Uri?> = HashMap<String?, Uri?>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == STEERING_MANIFEST_JSON_NAME_HOST && reader.peek() == JsonToken.STRING) {
                    host = reader.nextString()
                    if (host.isEmpty()) {
                        throw ParserException.createForMalformedSteeringManifest(
                            "The HOST string is present but empty",  null
                        )
                    }
                } else if (name == STEERING_MANIFEST_JSON_NAME_PARAMS
                    && reader.peek() == JsonToken.BEGIN_OBJECT
                ) {
                    parseMap<String?>(
                        reader,
                        SteeringManifestParser.StringConverter { string: String? -> string },
                        params
                    )
                } else if (name == STEERING_MANIFEST_JSON_NAME_PER_VARIANT_URIS
                    && reader.peek() == JsonToken.BEGIN_OBJECT
                ) {
                    parseMap<Uri?>(
                        reader,
                        SteeringManifestParser.StringConverter { uriString: String? ->
                            Uri.parse(uriString)
                        },
                        perVariantUris
                    )
                } else if (name == STEERING_MANIFEST_JSON_NAME_PER_RENDITION_URIS
                    && reader.peek() == JsonToken.BEGIN_OBJECT
                ) {
                    parseMap<Uri?>(
                        reader,
                        SteeringManifestParser.StringConverter { uriString: String? ->
                            Uri.parse(uriString)
                        },
                        perRenditionUris
                    )
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            return UriReplacement(host, params, perVariantUris, perRenditionUris)
        }

        @Throws(IOException::class)
        private fun parsePathwayClonesArray(
            reader: JsonReader, pathwayClones: MutableList<PathwayClone?>
        ) {
            reader.beginArray()
            var hasElement = false
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    val pathwayClone: PathwayClone = parsePathwayClone(reader)
                    pathwayClones.add(pathwayClone)
                    hasElement = true
                } else {
                    reader.skipValue()
                }
            }
            reader.endArray()
            if (!hasElement) {
                throw ParserException.createForMalformedSteeringManifest(
                    "The PATHWAY-CLONES array is present but empty",  null
                )
            }
        }

        @Throws(IOException::class)
        private fun parsePathwayPriorityArray(
            reader: JsonReader, pathwayPriority: MutableList<String?>
        ) {
            val pathwayIdSet = HashSet<String?>()
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.STRING) {
                    val pathwayId = reader.nextString()
                    if (!pathwayIdSet.add(pathwayId)) {
                        throw ParserException.createForMalformedSteeringManifest(
                            ("The pathway ID ("
                                    + pathwayId
                                    + ") appears more than once in the PATHWAY-PRIORITY array"),  
                            null
                        )
                    }
                    pathwayPriority.add(pathwayId)
                } else {
                    reader.skipValue()
                }
            }
            reader.endArray()
            if (pathwayIdSet.isEmpty()) {
                throw ParserException.createForMalformedSteeringManifest(
                    "The PATHWAY-PRIORITY array is present but empty",  null
                )
            }
        }

        @Throws(IOException::class)
        private fun <T> parseMap(
            reader: JsonReader, stringConverter: StringConverter<T?>, map: MutableMap<String?, T?>
        ) {
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (reader.peek() == JsonToken.STRING) {
                    val value = reader.nextString()
                    map[name] = stringConverter.convert(value)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
    }
}
