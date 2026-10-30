package androidx.media3.common.util

fun interface Consumer<T> {
    fun accept(t: T)
}
