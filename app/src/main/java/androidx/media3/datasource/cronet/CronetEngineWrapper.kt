package androidx.media3.datasource.cronet

import android.content.Context
import org.chromium.net.CronetEngine

@Deprecated(
    """Use {@link CronetEngine} directly. See the <a
      href="https://developer.android.com/guide/topics/connectivity/cronet/start">Android developer
      guide</a> to learn how to instantiate a {@link CronetEngine} for use by your application. You
      can also use {@link CronetUtil#buildCronetEngine} to build a {@link CronetEngine} suitable
      for use with {@link CronetDataSource}."""
)
class CronetEngineWrapper {
    private val cronetEngine: CronetEngine?

    /**
     * Creates a wrapper for a [CronetEngine] built using the most suitable [ ]. When natively bundled Cronet and GMSCore Cronet are both available, `preferGMSCoreCronet` determines which is preferred.
     * 
     * @param context A context.
     * @param userAgent A default user agent, or `null` to use a default user agent of the
     * [CronetEngine].
     * @param preferGooglePlayServices Whether Cronet from Google Play Services should be preferred
     * over Cronet Embedded, if both are available.
     */
    /**
     * Creates a wrapper for a [CronetEngine] built using the most suitable [ ]. When natively bundled Cronet and GMSCore Cronet are both available, the
     * natively bundled provider is preferred.
     * 
     * @param context A context.
     */
    @JvmOverloads
    constructor(
        context: Context,
        userAgent: String? = null,
        preferGooglePlayServices: Boolean = false
    ) {
        cronetEngine = CronetUtil.buildCronetEngine(context, userAgent, preferGooglePlayServices)
    }

    /**
     * Creates a wrapper for an existing [CronetEngine].
     * 
     * @param cronetEngine The CronetEngine to wrap.
     */
    constructor(cronetEngine: CronetEngine?) {
        this.cronetEngine = cronetEngine
    }

    /**
     * Returns the wrapped [CronetEngine].
     * 
     * @return The CronetEngine, or null if no CronetEngine is available.
     */
    fun getCronetEngine(): CronetEngine? {
        return cronetEngine
    }
}
