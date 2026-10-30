package androidx.media3.common

import android.util.Pair

interface ErrorMessageProvider<T : Throwable> {
    fun getErrorMessage(throwable: T): Pair<Int, String>
}
