package androidx.media3.container

import androidx.annotation.RestrictTo
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import com.google.common.base.Preconditions
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.Comparable
import kotlin.Int

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class ReorderingBufferQueue(private val outputConsumer: OutputConsumer) {
    interface OutputConsumer {
        fun consume(presentationTimeUs: Long, buffer: ParsableByteArray?)
    }

    private val unusedParsableByteArrays: ArrayDeque<ParsableByteArray?>
    private val unusedBuffersWithTimestamp: ArrayDeque<BuffersWithTimestamp?>

    private val pendingBuffers: PriorityQueue<BuffersWithTimestamp?>

    private var reorderingQueueSize: Int
    private var lastQueuedBuffer: BuffersWithTimestamp? = null

    init {
        unusedParsableByteArrays = ArrayDeque<ParsableByteArray?>()
        unusedBuffersWithTimestamp = ArrayDeque<BuffersWithTimestamp?>()
        pendingBuffers = PriorityQueue<BuffersWithTimestamp?>()
        reorderingQueueSize = C.LENGTH_UNSET
    }

    var maxSize: Int
        get() = reorderingQueueSize
        set(reorderingQueueSize) {
            Preconditions.checkState(reorderingQueueSize >= 0)
            this.reorderingQueueSize = reorderingQueueSize
            flushQueueDownToSize(reorderingQueueSize)
        }

    fun add(presentationTimeUs: Long, buffer: ParsableByteArray) {
        if (presentationTimeUs == C.TIME_UNSET || reorderingQueueSize == 0 || (reorderingQueueSize != C.LENGTH_UNSET && pendingBuffers.size >= reorderingQueueSize && presentationTimeUs < pendingBuffers.peek()!!.presentationTimeUs)
        ) {
            outputConsumer.consume(presentationTimeUs, buffer)
            return
        }
        // Make a local copy of the buffer data so we can store it in the queue and allow the buffer
        // parameter to be safely re-used after this add() method returns.
        val bufferCopy = copy(buffer)
        if (lastQueuedBuffer != null && presentationTimeUs == lastQueuedBuffer!!.presentationTimeUs) {
            lastQueuedBuffer!!.nalBuffers.add(bufferCopy)
            return
        }
        val buffersWithTimestamp =
            (if (unusedBuffersWithTimestamp.isEmpty())
                BuffersWithTimestamp()
            else
                unusedBuffersWithTimestamp.pop())!!
        buffersWithTimestamp.init(presentationTimeUs, bufferCopy)
        pendingBuffers.add(buffersWithTimestamp)
        lastQueuedBuffer = buffersWithTimestamp
        if (reorderingQueueSize != C.LENGTH_UNSET) {
            flushQueueDownToSize(reorderingQueueSize)
        }
    }

    private fun copy(input: ParsableByteArray): ParsableByteArray {
        val result =
            (if (unusedParsableByteArrays.isEmpty())
                ParsableByteArray()
            else
                unusedParsableByteArrays.pop())!!
        result.reset(input.bytesLeft())
        System.arraycopy( 
            input.data,  
            input.getPosition(),  
            result.data,  
            0,  
            result.bytesLeft()
        )
        return result
    }

    fun clear() {
        pendingBuffers.clear()
    }

    fun flush() {
        flushQueueDownToSize(0)
    }

    private fun flushQueueDownToSize(targetSize: Int) {
        while (pendingBuffers.size > targetSize) {
            val buffersWithTimestamp: BuffersWithTimestamp = pendingBuffers.poll()!!
            for (i in buffersWithTimestamp.nalBuffers.indices) {
                outputConsumer.consume(
                    buffersWithTimestamp.presentationTimeUs, buffersWithTimestamp.nalBuffers[i]
                )
                unusedParsableByteArrays.push(buffersWithTimestamp.nalBuffers[i])
            }
            buffersWithTimestamp.nalBuffers.clear()
            if (lastQueuedBuffer != null
                && lastQueuedBuffer!!.presentationTimeUs == buffersWithTimestamp.presentationTimeUs
            ) {
                lastQueuedBuffer = null
            }
            unusedBuffersWithTimestamp.push(buffersWithTimestamp)
        }
    }

    private class BuffersWithTimestamp : Comparable<BuffersWithTimestamp> {
        val nalBuffers: MutableList<ParsableByteArray?>
        var presentationTimeUs: Long

        init {
            presentationTimeUs = C.TIME_UNSET
            nalBuffers = ArrayList()
        }

        fun init(presentationTimeUs: Long, nalBuffer: ParsableByteArray?) {
            Preconditions.checkArgument(presentationTimeUs != C.TIME_UNSET)
            Preconditions.checkState(this.nalBuffers.isEmpty())
            this.presentationTimeUs = presentationTimeUs
            this.nalBuffers.add(nalBuffer)
        }

        override fun compareTo(other: BuffersWithTimestamp): Int {
            return presentationTimeUs.compareTo(other.presentationTimeUs)
        }
    }
}
