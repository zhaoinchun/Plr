package androidx.media3.exoplayer.source

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.util.Consumer
import com.google.common.base.Preconditions
import kotlin.math.min
import androidx.core.util.size

internal class SpannedData<V>  constructor(private val removeCallback: Consumer<V> = Consumer { value: V -> }) {
    private var memoizedReadIndex: Int= C.INDEX_UNSET
    private val spans: SparseArray<V> = SparseArray<V>()

    fun get(key: Int): V? {
        if (memoizedReadIndex == C.INDEX_UNSET) {
            memoizedReadIndex = 0
        }
        while (memoizedReadIndex > 0 && key < spans.keyAt(memoizedReadIndex)) {
            memoizedReadIndex--
        }
        while (memoizedReadIndex < spans.size - 1 && key >= spans.keyAt(memoizedReadIndex + 1)) {
            memoizedReadIndex++
        }
        return spans.valueAt(memoizedReadIndex)
    }

    fun appendSpan(startKey: Int, value: V) {
        if (memoizedReadIndex == C.INDEX_UNSET) {
            Preconditions.checkState(spans.size == 0)
            memoizedReadIndex = 0
        }

        if (spans.size > 0) {
            val lastStartKey = spans.keyAt(spans.size - 1)
            Preconditions.checkArgument(startKey >= lastStartKey)
            if (lastStartKey == startKey) {
                removeCallback.accept(spans.valueAt(spans.size - 1))
            }
        }
        spans.append(startKey, value)
    }

    val endValue: V
        get() = spans.valueAt(spans.size - 1)

    fun discardTo(discardToKey: Int) {
        var i = 0
        while (i < spans.size - 1 && discardToKey >= spans.keyAt(i + 1)) {
            removeCallback.accept(spans.valueAt(i))
            spans.removeAt(i)
            if (memoizedReadIndex > 0) {
                memoizedReadIndex--
            }
            i++
        }
    }

    fun discardFrom(discardFromKey: Int) {
        var i = spans.size - 1
        while (i >= 0 && discardFromKey < spans.keyAt(i)) {
            removeCallback.accept(spans.valueAt(i))
            spans.removeAt(i)
            i--
        }
        memoizedReadIndex =
            if (spans.size > 0) min(memoizedReadIndex, spans.size - 1) else C.INDEX_UNSET
    }

    fun clear() {
        for (i in 0..<spans.size) {
            removeCallback.accept(spans.valueAt(i))
        }
        memoizedReadIndex = C.INDEX_UNSET
        spans.clear()
    }

    val isEmpty: Boolean
        get() = spans.size == 0
}
