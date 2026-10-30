package androidx.media3.common

import android.view.View
import android.view.ViewGroup
import com.google.common.util.concurrent.ListenableFuture

interface ViewProvider {
    fun getView(viewGroup: ViewGroup): ListenableFuture<View>
}
