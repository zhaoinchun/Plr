
package androidx.media3.exoplayer.drm

import android.net.Uri
import android.text.TextUtils
import androidx.media3.common.C

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.common.base.Preconditions
import com.google.common.net.HttpHeaders
import com.google.common.net.MediaType
import com.google.common.primitives.Bytes
import java.nio.charset.StandardCharsets
import java.util.UUID

class HttpMediaDrmCallback(
    defaultLicenseUrl: String?,
    forceDefaultLicenseUrl: Boolean,
    dataSourceFactory: DataSource.Factory
) : MediaDrmCallback {
    private val dataSourceFactory= dataSourceFactory
    private val defaultLicenseUrl = defaultLicenseUrl
    private val forceDefaultLicenseUrl= forceDefaultLicenseUrl
    private val keyRequestProperties=mutableMapOf<String, String>()

    constructor(defaultLicenseUrl: String?, dataSourceFactory: DataSource.Factory) : this(
        defaultLicenseUrl,
        false,
        dataSourceFactory
    )

    init {
        Preconditions.checkArgument(!(forceDefaultLicenseUrl && TextUtils.isEmpty(defaultLicenseUrl)))
    }

    fun setKeyRequestProperty(name: String, value: String) {
        synchronized(keyRequestProperties) {
            keyRequestProperties.put(name, value)
        }
    }

    fun clearKeyRequestProperty(name: String) {
        synchronized(keyRequestProperties) {
            keyRequestProperties.remove(name)
        }
    }

    fun clearAllKeyRequestProperties() {
        synchronized(keyRequestProperties) {
            keyRequestProperties.clear()
        }
    }

    // Wrapping into a RuntimeException is recommended by the JSONException docs:
    // https://developer.android.com/reference/org/json/JSONException
    @Throws(MediaDrmCallbackException::class)
    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest
    ): MediaDrmCallback.Response {
        val httpBody =
            Bytes.concat(
                "{\"signedRequest\":\"".toByteArray(StandardCharsets.UTF_8),
                request.data,
                "\"}".toByteArray(StandardCharsets.UTF_8)
            )
        return DrmUtil.executePost(
            dataSourceFactory.createDataSource(),
            request.defaultUrl,
            httpBody,
            mapOf(
                HttpHeaders.CONTENT_TYPE to MediaType.JSON_UTF_8.toString(),
                HttpHeaders.CONTENT_LENGTH to httpBody.size.toString()
            )
        )
    }

    @Throws(MediaDrmCallbackException::class)
    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest
    ): MediaDrmCallback.Response {
        var url: String? = request.licenseServerUrl
        if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
            url = defaultLicenseUrl
        }
        if (TextUtils.isEmpty(url)) {
            throw MediaDrmCallbackException(
                DataSpec.Builder().setUri(Uri.EMPTY).build(),
                Uri.EMPTY,  
                mutableMapOf(),  
                0,  
                IllegalStateException("No license URL")
            )
        }
        val requestProperties: MutableMap<String, String> = HashMap<String, String>()
        // Add standard request properties for supported schemes.
        val contentType =
            if (C.PLAYREADY_UUID == uuid)
                "text/xml"
            else
                (if (C.CLEARKEY_UUID == uuid) "application/json" else "application/octet-stream")
        requestProperties["Content-Type"] = contentType
        if (C.PLAYREADY_UUID == uuid) {
            requestProperties["SOAPAction"] = "http://schemas.microsoft.com/DRM/2007/03/protocols/AcquireLicense"
        }
        // Add additional request properties.
        synchronized(keyRequestProperties) {
            requestProperties.putAll(keyRequestProperties)
        }
        return DrmUtil.executePost(
            dataSourceFactory.createDataSource(),
            url!!,  
            request.data,
            requestProperties
        )
    }
}
