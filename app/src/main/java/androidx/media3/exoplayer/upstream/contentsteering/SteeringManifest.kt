package androidx.media3.exoplayer.upstream.contentsteering

import android.net.Uri

import java.util.Objects

class SteeringManifest(
    val version: Int,
    val timeToLiveMs: Long,
    val reloadUri: Uri?,
    pathwayPriority: List<String?>,
    pathwayClones: List<PathwayClone?>
) {
    class PathwayClone(
        val baseId: String?,
        val id: String?,
        val uriReplacement: UriReplacement?
    ) {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is PathwayClone) {
                return false
            }
            val pathwayClone = o
            return baseId == pathwayClone.baseId
                    && id == pathwayClone.id
                    && uriReplacement == pathwayClone.uriReplacement
        }

        override fun hashCode(): Int {
            return Objects.hash(baseId, id, uriReplacement)
        }
    }

    class UriReplacement(
        val host: String?,
        params: MutableMap<String?, String?>,
        perVariantUris: MutableMap<String?, Uri?>,
        perRenditionUris: MutableMap<String?, Uri?>
    ) {
        val params: Map<String?, String?> = params.toMap()
        val perVariantUris: Map<String?, Uri?> = perVariantUris.toMap()
        val perRenditionUris: Map<String?, Uri?> = perRenditionUris.toMap()

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is UriReplacement) {
                return false
            }
            val uriReplacement = o
            return host == uriReplacement.host
                    && params == uriReplacement.params
                    && perVariantUris == uriReplacement.perVariantUris
                    && perRenditionUris == uriReplacement.perRenditionUris
        }

        override fun hashCode(): Int {
            return Objects.hash(host, params, perVariantUris, perRenditionUris)
        }
    }

    val pathwayPriority: List<String?> = pathwayPriority.toList()
    val pathwayClones: List<PathwayClone?> = pathwayClones.toList()

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is SteeringManifest) {
            return false
        }
        return version == o.version && timeToLiveMs == o.timeToLiveMs && reloadUri == o.reloadUri
                && pathwayPriority == o.pathwayPriority
                && pathwayClones == o.pathwayClones
    }

    override fun hashCode(): Int {
        return Objects.hash(version, timeToLiveMs, reloadUri, pathwayPriority, pathwayClones)
    }
}
