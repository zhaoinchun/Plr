package androidx.media3.exoplayer.upstream

interface Allocator {
    interface AllocationNode {
        val allocation: Allocation
        fun next(): AllocationNode?
    }

    fun allocate(): Allocation
    fun release(allocation: Allocation)
    fun release(allocationNode: AllocationNode)
    fun trim()

    val totalBytesAllocated: Int
    val individualAllocationLength: Int
}
