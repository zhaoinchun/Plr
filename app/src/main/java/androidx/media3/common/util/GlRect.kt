package androidx.media3.common.util

import com.google.common.base.Preconditions

class GlRect(left: Int, bottom: Int, right: Int, top: Int) {
    var left: Int
    var bottom: Int
    var right: Int
    var top: Int

    constructor(width: Int, height: Int) : this( 0,  0, width, height)

    init {
        Preconditions.checkArgument(left <= right && bottom <= top)
        this.left = left
        this.bottom = bottom
        this.right = right
        this.top = top
    }
}
