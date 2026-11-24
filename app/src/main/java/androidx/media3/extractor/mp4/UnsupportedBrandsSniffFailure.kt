package androidx.media3.extractor.mp4

import androidx.media3.common.util.Util.toFourccString
import androidx.media3.extractor.SniffFailure
import com.google.common.primitives.ImmutableIntArray

class UnsupportedBrandsSniffFailure(
    
    val majorBrand: Int, compatibleBrands: IntArray?
) : SniffFailure {
    val compatibleBrands: ImmutableIntArray = if (compatibleBrands != null)
        ImmutableIntArray.copyOf(compatibleBrands)
    else
        ImmutableIntArray.of()

    override fun toString(): String {
        val compatibleBrandStrings: MutableList<String> =
            ArrayList(compatibleBrands.length())
        for (i in 0..<compatibleBrands.length()) {
            compatibleBrandStrings.add(toFourccString(compatibleBrands[i]))
        }
        return ("UnsupportedBrands{major="
                + toFourccString(majorBrand)
                + ", compatible="
                + compatibleBrandStrings
                + "}")
    }
}
