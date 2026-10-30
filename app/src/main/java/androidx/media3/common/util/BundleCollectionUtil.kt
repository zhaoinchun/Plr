package androidx.media3.common.util

import android.os.Bundle
import android.util.SparseArray
import com.google.common.base.Function

object BundleCollectionUtil {
    fun <T : Any> toBundleList(
        list: MutableList<T?>, toBundleFunc: Function<T?, Bundle?>
    ): List<Bundle?> {
        return list.map { toBundleFunc.apply(it) }
    }

    fun <T : Any> fromBundleList(
        fromBundleFunc: Function<Bundle, T>, bundleList: MutableList<Bundle>
    ): List<T> {
        return bundleList.map { fromBundleFunc.apply(it) }
    }

    // Intentionally using ArrayList for putParcelableArrayList.
    fun <T : Any> toBundleArrayList(
        items: Collection<T>, toBundleFunc: Function<T, Bundle?>
    ): ArrayList<Bundle?> {
        val arrayList = ArrayList<Bundle?>(items.size)
        for (item in items) {
            arrayList.add(toBundleFunc.apply(item))
        }
        return arrayList
    }

    fun <T : Any> fromBundleSparseArray(
        fromBundleFunc: Function<Bundle?, T?>, bundleSparseArray: SparseArray<Bundle?>
    ): SparseArray<T?> {
        val result = SparseArray<T?>(bundleSparseArray.size())
        for (i in 0..<bundleSparseArray.size()) {
            result.put(
                bundleSparseArray.keyAt(i),
                fromBundleFunc.apply(bundleSparseArray.valueAt(i))
            )
        }
        return result
    }

    fun <T : Any> toBundleSparseArray(
        items: SparseArray<T>, toBundleFunc: Function<T, Bundle>
    ): SparseArray<Bundle> {
        val sparseArray = SparseArray<Bundle>(items.size())
        for (i in 0..<items.size()) {
            sparseArray.put(items.keyAt(i), toBundleFunc.apply(items.valueAt(i)))
        }
        return sparseArray
    }

    fun stringMapToBundle(map: Map<String, String>): Bundle {
        val bundle = Bundle()
        for (entry in map.entries) {
            bundle.putString(entry.key, entry.value)
        }
        return bundle
    }

    fun bundleToStringHashMap(bundle: Bundle): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        if (bundle == Bundle.EMPTY) {
            return map
        }
        for (key in bundle.keySet()) {
            val value = bundle.getString(key)
            if (value != null) {
                map[key] = value
            }
        }
        return map
    }

    fun bundleToStringImmutableMap(bundle: Bundle): Map<String, String> {
        if (bundle == Bundle.EMPTY) {
            return mapOf()
        }
        val map = bundleToStringHashMap(bundle)
        return map.toMap()
    }

    fun getBundleWithDefault(bundle: Bundle, field: String?, defaultValue: Bundle): Bundle {
        val result = bundle.getBundle(field)
        return if (result != null) result else defaultValue
    }

    fun getIntegerArrayListWithDefault(
        bundle: Bundle, field: String?, defaultValue: ArrayList<Int?>
    ): ArrayList<Int?> {
        val result = bundle.getIntegerArrayList(field)
        return if (result != null) result else defaultValue
    }

    fun ensureClassLoader(bundle: Bundle?) {
        if (bundle != null) {
            bundle.setClassLoader(BundleCollectionUtil::class.java.getClassLoader())
        }
    }
}
