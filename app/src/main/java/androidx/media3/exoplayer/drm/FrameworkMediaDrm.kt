package androidx.media3.exoplayer.drm

import android.media.DeniedByServerException
import android.media.MediaCrypto
import android.media.MediaCryptoException
import android.media.MediaDrm
import android.media.MediaDrmException
import android.media.NotProvisionedException
import android.media.UnsupportedSchemeException
import android.media.metrics.LogSessionId
import android.os.Build
import android.os.PersistableBundle
import android.text.TextUtils
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.extractor.mp4.PsshAtomUtil
import androidx.media3.extractor.mp4.PsshAtomUtil.buildPsshAtom
import androidx.media3.extractor.mp4.PsshAtomUtil.parsePsshAtom
import androidx.media3.extractor.mp4.PsshAtomUtil.parseSchemeSpecificData
import androidx.media3.extractor.mp4.PsshAtomUtil.parseVersion
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

class FrameworkMediaDrm private constructor(uuid: UUID) : ExoMediaDrm {
    private val uuid: UUID
    private val mediaDrm: MediaDrm
    private var referenceCount: Int

    init {
        Preconditions.checkArgument(C.COMMON_PSSH_UUID != uuid, "Use C.CLEARKEY_UUID instead")
        this.uuid = uuid
        this.mediaDrm = MediaDrm(adjustUuid(uuid)!!)
        // Creators of an instance automatically acquire ownership of the created instance.
        referenceCount = 1
        if (C.WIDEVINE_UUID == uuid && needsForceWidevineL3Workaround()) {
            forceWidevineL3(mediaDrm)
        }
    }

    override fun setOnEventListener(listener: ExoMediaDrm.OnEventListener?) {
        mediaDrm.setOnEventListener(
            if (listener == null)
                null
            else
                MediaDrm.OnEventListener { mediaDrm: MediaDrm, sessionId: ByteArray?, event: Int,
                                           extra: Int, data: ByteArray? ->
                    listener.onEvent(
                        this@FrameworkMediaDrm,
                        sessionId,
                        event,
                        extra,
                        data
                    )
                })
    }

    override fun setOnKeyStatusChangeListener(
        listener: ExoMediaDrm.OnKeyStatusChangeListener?
    ) {
        mediaDrm.setOnKeyStatusChangeListener(
            if (listener == null)
                null
            else
                MediaDrm.OnKeyStatusChangeListener { mediaDrm: MediaDrm, sessionId: ByteArray,
                                                     keyInfo: List<MediaDrm.KeyStatus>, hasNewUsableKey: Boolean ->
                    val exoKeyInfo = keyInfo.map {
                        ExoMediaDrm.KeyStatus(it.statusCode, it.keyId)
                    }
                    listener.onKeyStatusChange(
                        this@FrameworkMediaDrm, sessionId, exoKeyInfo, hasNewUsableKey
                    )
                },
            null
        )
    }

    override fun setOnExpirationUpdateListener(listener: ExoMediaDrm.OnExpirationUpdateListener?) {
        mediaDrm.setOnExpirationUpdateListener(
            if (listener == null)
                null
            else
                MediaDrm.OnExpirationUpdateListener { mediaDrm: MediaDrm, sessionId: ByteArray, expirationTimeMs: Long ->
                    listener.onExpirationUpdate(
                        this@FrameworkMediaDrm,
                        sessionId,
                        expirationTimeMs
                    )
                },
            null
        )
    }

    @Throws(MediaDrmException::class)
    override fun openSession(): ByteArray {
        return mediaDrm.openSession()
    }

    override fun closeSession(sessionId: ByteArray) {
        mediaDrm.closeSession(sessionId)
    }

    override fun setPlayerIdForSession(sessionId: ByteArray, playerId: PlayerId) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Api31.setLogSessionIdOnMediaDrmSession(mediaDrm, sessionId, playerId)
            } catch (e: UnsupportedOperationException) {
                w(TAG, "setLogSessionId failed.")
            }
        }
    }

    @Throws(NotProvisionedException::class)
    override fun getKeyRequest(
        scope: ByteArray,
        schemeDatas: List<DrmInitData.SchemeData>?,
        keyType: Int,
        optionalParameters: HashMap<String, String>?
    ): ExoMediaDrm.KeyRequest {
        var schemeData: DrmInitData.SchemeData? = null
        var initData: ByteArray? = null
        var mimeType: String? = null
        if (schemeDatas != null) {
            schemeData = getSchemeData(uuid, schemeDatas)
            initData = adjustRequestInitData(
                uuid,
                schemeData!!.data!!
            )
            mimeType = adjustRequestMimeType(uuid, schemeData.mimeType)
        }
        val request =
            mediaDrm.getKeyRequest(scope, initData, mimeType, keyType, optionalParameters)

        val requestData = adjustRequestData(uuid, request.data)
        var licenseServerUrl = adjustLicenseServerUrl(request.getDefaultUrl())
        if (TextUtils.isEmpty(licenseServerUrl)
            && schemeData != null && !TextUtils.isEmpty(schemeData.licenseServerUrl)
        ) {
            licenseServerUrl = schemeData.licenseServerUrl!!
        }

        val requestType: @ExoMediaDrm.KeyRequest.RequestType Int = request.requestType
        return ExoMediaDrm.KeyRequest(requestData, licenseServerUrl, requestType)
    }

    private fun adjustLicenseServerUrl(licenseServerUrl: String): String {
        if (MOCK_LA_URL == licenseServerUrl) {
            return ""
        }
        if (Build.VERSION.SDK_INT >= 33 && "https://default.url" == licenseServerUrl) {
            // Work around b/247808112
            val pluginVersion = getPropertyString("version")
            if (pluginVersion == "1.2" || pluginVersion == "aidl-1") {
                return ""
            }
        }
        return licenseServerUrl
    }

    @Throws(NotProvisionedException::class, DeniedByServerException::class)
    override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray? {
        var response = response
        if (C.CLEARKEY_UUID == uuid) {
            response = ClearKeyUtil.adjustResponseData(response)
        }

        return mediaDrm.provideKeyResponse(scope, response)
    }

    override val provisionRequest: ExoMediaDrm.ProvisionRequest
        get() {
            val request = mediaDrm.provisionRequest
            return ExoMediaDrm.ProvisionRequest(request.data, request.getDefaultUrl())
        }

    @Throws(DeniedByServerException::class)
    override fun provideProvisionResponse(response: ByteArray) {
        mediaDrm.provideProvisionResponse(response)
    }

    override fun queryKeyStatus(sessionId: ByteArray): MutableMap<String, String> {
        return mediaDrm.queryKeyStatus(sessionId)
    }

    override fun requiresSecureDecoder(sessionId: ByteArray, mimeType: String): Boolean {
        var result: Boolean
        if (Build.VERSION.SDK_INT >= 31 && this.isMediaDrmRequiresSecureDecoderImplemented) {
            result =
                Api31.requiresSecureDecoder(
                    mediaDrm,
                    mimeType,
                    mediaDrm.getSecurityLevel(sessionId)
                )
        } else {
            var mediaCrypto: MediaCrypto? = null
            try {
                mediaCrypto = MediaCrypto(adjustUuid(uuid)!!, sessionId)
                result = mediaCrypto.requiresSecureDecoderComponent(mimeType)
            } catch (e: MediaCryptoException) {
                // This shouldn't happen, but if it does then assume that most DRM schemes need a secure
                // decoder but ClearKey doesn't (because ClearKey never uses secure decryption). Requesting
                // a secure decoder when it's not supported leads to playback failures:
                // https://github.com/androidx/media/issues/1732
                result = uuid != C.CLEARKEY_UUID
            } finally {
                mediaCrypto?.release()
            }
        }
        return result
    }

    @Synchronized
    override fun acquire() {
        Preconditions.checkState(referenceCount > 0)
        referenceCount++
    }

    @Synchronized
    override fun release() {
        if (--referenceCount == 0) {
            mediaDrm.release()
        }
    }

    override fun restoreKeys(sessionId: ByteArray, keySetId: ByteArray) {
        mediaDrm.restoreKeys(sessionId, keySetId)
    }

    @RequiresApi(29)
    override fun removeOfflineLicense(keySetId: ByteArray) {
        if (Build.VERSION.SDK_INT < 29) {
            throw UnsupportedOperationException()
        }
        mediaDrm.removeOfflineLicense(keySetId)
    }

    override val offlineLicenseKeySetIds: List<ByteArray>
        @RequiresApi(29)
        get() {
            if (Build.VERSION.SDK_INT < 29) {
                throw UnsupportedOperationException()
            }
            return mediaDrm.getOfflineLicenseKeySetIds()
        }

    override val metrics: PersistableBundle?
        get() {
            if (Build.VERSION.SDK_INT < 28) {
                return null
            }
            return mediaDrm.getMetrics()
        }

    override fun getPropertyString(propertyName: String): String {
        return mediaDrm.getPropertyString(propertyName)
    }

    override fun getPropertyByteArray(propertyName: String): ByteArray {
        return mediaDrm.getPropertyByteArray(propertyName)
    }

    override fun setPropertyString(propertyName: String, value: String) {
        mediaDrm.setPropertyString(propertyName, value)
    }

    override fun setPropertyByteArray(propertyName: String, value: ByteArray) {
        mediaDrm.setPropertyByteArray(propertyName, value)
    }

    @Throws(MediaCryptoException::class)
    override fun createCryptoConfig(sessionId: ByteArray): FrameworkCryptoConfig {
        return FrameworkCryptoConfig(adjustUuid(uuid), sessionId)
    }

    override val cryptoType: @C.CryptoType Int = C.CRYPTO_TYPE_FRAMEWORK

    @get:RequiresApi(31)
    private val isMediaDrmRequiresSecureDecoderImplemented: Boolean
        get() {
            if (uuid == C.WIDEVINE_UUID) {
                // All devices with SDK_INT >= 37 must have a Widevine plugin version of 17.* or higher:
                // b/359768062#comment5
                if (Build.VERSION.SDK_INT >= 37) {
                    return true
                }
                val pluginVersion = getPropertyString(MediaDrm.PROPERTY_VERSION)
                return !pluginVersion.startsWith("v5.") && !pluginVersion.startsWith("14.") && !pluginVersion.startsWith(
                    "15."
                ) && !pluginVersion.startsWith("16.0")
            } else {
                // Assume that ClearKey plugin always supports this method, and no non-Google plugin does. See
                // b/352419654#comment71.
                return uuid == C.CLEARKEY_UUID
            }
        }

    @RequiresApi(31)
    private object Api31 {
        fun requiresSecureDecoder(
            mediaDrm: MediaDrm, mimeType: String, securityLevel: Int
        ): Boolean {
            return mediaDrm.requiresSecureDecoder(mimeType, securityLevel)
        }

        fun setLogSessionIdOnMediaDrmSession(
            mediaDrm: MediaDrm, drmSessionId: ByteArray, playerId: PlayerId
        ) {
            val logSessionId = playerId.logSessionId
            if (logSessionId != LogSessionId.LOG_SESSION_ID_NONE) {
                val playbackComponent = mediaDrm.getPlaybackComponent(
                    drmSessionId
                )!!
                playbackComponent.setLogSessionId(logSessionId)
            }
        }
    }

    companion object {
        private const val TAG = "FrameworkMediaDrm"

        val DEFAULT_PROVIDER: ExoMediaDrm.Provider = ExoMediaDrm.Provider { uuid: UUID? ->
            try {
                return@Provider Companion.newInstance(uuid!!)
            } catch (e: UnsupportedDrmException) {
                e(TAG, "Failed to instantiate a FrameworkMediaDrm for uuid: $uuid.")
                return@Provider DummyExoMediaDrm()
            }
        }

        private const val CENC_SCHEME_MIME_TYPE = "cenc"
        private const val MOCK_LA_URL_VALUE = "https://x"
        private val MOCK_LA_URL = "<LA_URL>$MOCK_LA_URL_VALUE</LA_URL>"
        private const val UTF_16_BYTES_PER_CHARACTER = 2

        fun isCryptoSchemeSupported(uuid: UUID?): Boolean {
            return MediaDrm.isCryptoSchemeSupported(adjustUuid(uuid)!!)
        }

        @Throws(UnsupportedDrmException::class)
        fun newInstance(uuid: UUID): FrameworkMediaDrm {
            try {
                return FrameworkMediaDrm(uuid)
            } catch (e: UnsupportedSchemeException) {
                throw UnsupportedDrmException(
                    UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME,
                    e
                )
            } catch (e: Exception) {
                throw UnsupportedDrmException(
                    UnsupportedDrmException.REASON_INSTANTIATION_ERROR,
                    e
                )
            }
        }

        private fun getSchemeData(
            uuid: UUID?,
            schemeDatas: List<DrmInitData.SchemeData>
        ): DrmInitData.SchemeData {
            if (C.WIDEVINE_UUID != uuid) {
                // For non-Widevine CDMs always use the first scheme data.
                return schemeDatas[0]
            }

            if (Build.VERSION.SDK_INT >= 28 && schemeDatas.size > 1) {
                // For API level 28 and above, concatenate multiple PSSH scheme datas if possible.
                val firstSchemeData = schemeDatas[0]
                var concatenatedDataLength = 0
                var canConcatenateData = true
                for (i in schemeDatas.indices) {
                    val schemeData = schemeDatas[i]
                    val schemeDataData = schemeData.data!!
                    if (schemeData.mimeType == firstSchemeData.mimeType
                        && schemeData.licenseServerUrl == firstSchemeData.licenseServerUrl
                        && PsshAtomUtil.isPsshAtom(schemeDataData)
                    ) {
                        concatenatedDataLength += schemeDataData.size
                    } else {
                        canConcatenateData = false
                        break
                    }
                }
                if (canConcatenateData) {
                    val concatenatedData = ByteArray(concatenatedDataLength)
                    var concatenatedDataPosition = 0
                    for (i in schemeDatas.indices) {
                        val schemeData = schemeDatas[i]
                        val schemeDataData = schemeData.data!!
                        val schemeDataLength = schemeDataData.size
                        System.arraycopy(
                            schemeDataData,
                            0,
                            concatenatedData,
                            concatenatedDataPosition,
                            schemeDataLength
                        )
                        concatenatedDataPosition += schemeDataLength
                    }
                    return firstSchemeData.copyWithData(concatenatedData)
                }
            }

            // For API levels 23 - 27, prefer the first V1 PSSH box.
            for (i in schemeDatas.indices) {
                val schemeData = schemeDatas[i]
                val version = parseVersion(schemeData.data!!)
                if (version == 1) {
                    return schemeData
                }
            }

            // If all else fails, use the first scheme data.
            return schemeDatas[0]
        }

        private fun adjustUuid(uuid: UUID?): UUID? {
            return if (cdmRequiresCommonPsshUuid(uuid)) C.COMMON_PSSH_UUID else uuid
        }

        private fun adjustRequestInitData(uuid: UUID?, initData: ByteArray): ByteArray? {
            var initData = initData
            if (C.PLAYREADY_UUID == uuid) {
                var schemeSpecificData = parseSchemeSpecificData(initData, uuid)
                if (schemeSpecificData == null) {
                    // The init data is not contained in a pssh box.
                    schemeSpecificData = initData
                }
                initData =
                    buildPsshAtom(
                        C.PLAYREADY_UUID, addLaUrlAttributeIfMissing(schemeSpecificData)
                    )
            }
            if (cdmRequiresCommonPsshUuid(uuid)) {
                val psshAtom = parsePsshAtom(initData)
                if (psshAtom != null) {
                    initData =
                        PsshAtomUtil.buildPsshAtom(
                            C.COMMON_PSSH_UUID,
                            psshAtom.keyIds,
                            psshAtom.schemeData
                        )
                }
            }

            // Some Amazon devices require data to be extracted from the PSSH atom for PlayReady.
            if (enableWorkarounds()
                && (C.PLAYREADY_UUID == uuid
                        && "Amazon" == Build.MANUFACTURER
                        && ("AFTB" == Build.MODEL // Fire TV Gen 1
                        || "AFTS" == Build.MODEL // Fire TV Gen 2
                        || "AFTM" == Build.MODEL // Fire TV Stick Gen 1
                        || "AFTT" == Build.MODEL))
            ) { // Fire TV Stick Gen 2
                val psshData = parseSchemeSpecificData(initData, uuid)
                if (psshData != null) {
                    // Extraction succeeded, so return the extracted data.
                    return psshData
                }
            }
            return initData
        }

        private fun adjustRequestMimeType(uuid: UUID?, mimeType: String?): String? {
            // Prior to API level 26 the ClearKey CDM only accepted "cenc" as the scheme for MP4.
            if (Build.VERSION.SDK_INT < 26 && C.CLEARKEY_UUID == uuid
                && (MimeTypes.VIDEO_MP4 == mimeType || MimeTypes.AUDIO_MP4 == mimeType)
            ) {
                return CENC_SCHEME_MIME_TYPE
            }
            return mimeType
        }

        private fun adjustRequestData(uuid: UUID, requestData: ByteArray): ByteArray {
            if (C.CLEARKEY_UUID == uuid) {
                return ClearKeyUtil.adjustRequestData(requestData)
            }
            return requestData
        }

        private fun cdmRequiresCommonPsshUuid(uuid: UUID?): Boolean {
            // ClearKey had to be accessed using the Common PSSH UUID prior to API level 27.
            return Build.VERSION.SDK_INT < 27 && uuid == C.CLEARKEY_UUID
        }

        private fun forceWidevineL3(mediaDrm: MediaDrm) {
            mediaDrm.setPropertyString("securityLevel", "L3")
        }

        private fun needsForceWidevineL3Workaround(): Boolean {
            return enableWorkarounds() && "ASUS_Z00AD" == Build.MODEL
        }

        private fun addLaUrlAttributeIfMissing(data: ByteArray): ByteArray? {
            val byteArray = ParsableByteArray(data)
            // See https://docs.microsoft.com/en-us/playready/specifications/specifications for more
            // information about the init data format.
            val length = byteArray.readLittleEndianInt()
            val objectRecordCount = byteArray.readLittleEndianShort().toInt()
            val recordType = byteArray.readLittleEndianShort().toInt()
            if (objectRecordCount != 1 || recordType != 1) {
                i(TAG, "Unexpected record count or type. Skipping LA_URL workaround.")
                return data
            }
            val recordLength = byteArray.readLittleEndianShort().toInt()
            val xml = byteArray.readString(recordLength, StandardCharsets.UTF_16LE)
            if (xml.contains("<LA_URL>")) {
                // LA_URL already present. Do nothing.
                return data
            }
            // This PlayReady object record does not include an LA_URL. We add a mock value for it.
            val endOfDataTagIndex = xml.indexOf("</DATA>")
            if (endOfDataTagIndex == -1) {
                w(TAG, "Could not find the </DATA> tag. Skipping LA_URL workaround.")
            }
            val xmlWithMockLaUrl =
                (xml.substring(0, endOfDataTagIndex)
                        + MOCK_LA_URL
                        + xml.substring(endOfDataTagIndex))
            val extraBytes: Int = MOCK_LA_URL.length * UTF_16_BYTES_PER_CHARACTER
            val newData = ByteBuffer.allocate(length + extraBytes)
            newData.order(ByteOrder.LITTLE_ENDIAN)
            newData.putInt(length + extraBytes)
            newData.putShort(objectRecordCount.toShort())
            newData.putShort(recordType.toShort())
            newData.putShort((xmlWithMockLaUrl.length * UTF_16_BYTES_PER_CHARACTER).toShort())
            newData.put(xmlWithMockLaUrl.toByteArray(StandardCharsets.UTF_16LE))
            return newData.array()
        }
    }
}
