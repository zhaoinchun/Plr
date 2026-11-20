package androidx.media3.datasource.cronet

import android.content.Context
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.util.Collections
import kotlin.math.min

object CronetUtil {
    private const val TAG = "CronetUtil"

    
    fun buildCronetEngine(context: Context): CronetEngine? {
        return buildCronetEngine(
            context,  /* userAgent= */
            null,  /* preferGooglePlayServices= */
            false
        )
    }

    
    
    fun buildCronetEngine(
        context: Context, userAgent: String?, preferGooglePlayServices: Boolean
    ): CronetEngine? {
        val cronetProviders: MutableList<CronetProvider> =
            ArrayList<CronetProvider>(CronetProvider.getAllProviders(context))
        // Remove disabled and fallback Cronet providers from list.
        for (i in cronetProviders.indices.reversed()) {
            if (!cronetProviders.get(i).isEnabled()
                || CronetProvider.PROVIDER_NAME_FALLBACK == cronetProviders.get(i).getName()
            ) {
                cronetProviders.removeAt(i)
            }
        }
        // Sort remaining providers by type and version.
        val providerComparator =
            CronetProviderComparator(preferGooglePlayServices)
        Collections.sort<CronetProvider?>(cronetProviders, providerComparator)
        for (i in cronetProviders.indices) {
            val providerName: String? = cronetProviders.get(i).getName()
            try {
                val cronetEngineBuilder: CronetEngine.Builder =
                    cronetProviders.get(i).createBuilder()
                if (userAgent != null) {
                    cronetEngineBuilder.setUserAgent(userAgent)
                }
                val cronetEngine: CronetEngine? = cronetEngineBuilder.build()
                Log.d(TAG, "CronetEngine built using " + providerName)
                return cronetEngine
            } catch (e: SecurityException) {
                Log.w(
                    TAG,
                    "Failed to build CronetEngine. Please check that the process has "
                            + "android.permission.ACCESS_NETWORK_STATE."
                )
            } catch (e: UnsatisfiedLinkError) {
                Log.w(
                    TAG,
                    "Failed to link Cronet binaries. Please check that native Cronet binaries are"
                            + "bundled into your app."
                )
            }
        }
        Log.w(TAG, "CronetEngine could not be built.")
        return null
    }

    private class CronetProviderComparator(private val preferGooglePlayServices: Boolean) :
        Comparator<CronetProvider> {
        override fun compare(providerLeft: CronetProvider, providerRight: CronetProvider): Int {
            val providerComparison = getPriority(providerLeft) - getPriority(providerRight)
            if (providerComparison != 0) {
                return providerComparison
            }
            return -compareVersionStrings(providerLeft.getVersion(), providerRight.getVersion())
        }

        
        fun getPriority(provider: CronetProvider): Int {
            val providerName: String? = provider.getName()
            if (CronetProvider.PROVIDER_NAME_APP_PACKAGED == providerName) {
                return 1
            } else if (GOOGLE_PLAY_SERVICES_PROVIDER_NAME == providerName) {
                return if (preferGooglePlayServices) 0 else 2
            } else {
                return 3
            }
        }

        companion object {
            /*
     * Copy of com.google.android.gms.net.CronetProviderInstaller.PROVIDER_NAME. We have our own
     * copy because GMSCore CronetProvider classes are unavailable in some (internal to Google)
     * build configurations.
     */
            private const val GOOGLE_PLAY_SERVICES_PROVIDER_NAME =
                "Google-Play-Services-Cronet-Provider"

            
            private fun compareVersionStrings(
                versionLeft: String?, versionRight: String?
            ): Int {
                if (versionLeft == null || versionRight == null) {
                    return 0
                }
                val versionStringsLeft: Array<String> = Util.split(versionLeft, "\\.")
                val versionStringsRight: Array<String> = Util.split(versionRight, "\\.")
                val minLength = min(versionStringsLeft.size, versionStringsRight.size)
                for (i in 0..<minLength) {
                    if (versionStringsLeft[i] != versionStringsRight[i]) {
                        try {
                            val versionIntLeft = versionStringsLeft[i]!!.toInt()
                            val versionIntRight = versionStringsRight[i]!!.toInt()
                            return versionIntLeft - versionIntRight
                        } catch (e: NumberFormatException) {
                            return 0
                        }
                    }
                }
                return 0
            }
        }
    }
}
