package androidx.media3.common

fun <E> List<E>.hasElement(predicate: (E) -> Boolean): Boolean {
    return find(predicate) != null
}

fun <E> Array<E>.hasElement(predicate: (E) -> Boolean): Boolean {
    return find(predicate) != null
}