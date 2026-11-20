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

    
    
    @JvmOverloads
    constructor(
        context: Context,
        userAgent: String? = null,
        preferGooglePlayServices: Boolean = false
    ) {
        cronetEngine = CronetUtil.buildCronetEngine(context, userAgent, preferGooglePlayServices)
    }

    
    constructor(cronetEngine: CronetEngine?) {
        this.cronetEngine = cronetEngine
    }

    
    fun getCronetEngine(): CronetEngine? {
        return cronetEngine
    }
}
