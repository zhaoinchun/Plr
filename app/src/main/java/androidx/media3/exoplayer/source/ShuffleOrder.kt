package androidx.media3.exoplayer.source

import androidx.media3.common.C
import java.util.Arrays
import java.util.Random

interface ShuffleOrder {
    class DefaultShuffleOrder private constructor(
        private val shuffled: IntArray,
        private val random: Random
    ) : ShuffleOrder {
        private val indexInShuffled: IntArray = IntArray(shuffled.size) { i ->
            shuffled[i]
        }

        constructor(length: Int) : this(length, Random())

        constructor(length: Int, randomSeed: Long) : this(length, Random(randomSeed))

        constructor(shuffledIndices: IntArray, randomSeed: Long) : this(
            shuffledIndices.copyOf(), Random(randomSeed)
        )

        private constructor(length: Int, random: Random) : this(
            createShuffledList(length, random),
            random
        )

        override val length = shuffled.size

        override fun getNextIndex(index: Int): Int {
            var shuffledIndex = indexInShuffled[index]
            return if (++shuffledIndex < shuffled.size) shuffled[shuffledIndex] else C.INDEX_UNSET
        }

        override fun getPreviousIndex(index: Int): Int {
            var shuffledIndex = indexInShuffled[index]
            return if (--shuffledIndex >= 0) shuffled[shuffledIndex] else C.INDEX_UNSET
        }

        override val lastIndex =
            if (shuffled.isNotEmpty()) shuffled[shuffled.size - 1] else C.INDEX_UNSET

        override val firstIndex = if (shuffled.isNotEmpty()) shuffled[0] else C.INDEX_UNSET

        override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
            val insertionPoints = IntArray(insertionCount)
            val insertionValues = IntArray(insertionCount)
            for (i in 0..<insertionCount) {
                insertionPoints[i] = random.nextInt(shuffled.size + 1)
                val swapIndex = random.nextInt(i + 1)
                insertionValues[i] = insertionValues[swapIndex]
                insertionValues[swapIndex] = i + insertionIndex
            }
            Arrays.sort(insertionPoints)
            val newShuffled = IntArray(shuffled.size + insertionCount)
            var indexInOldShuffled = 0
            var indexInInsertionList = 0
            for (i in 0..<shuffled.size + insertionCount) {
                if (indexInInsertionList < insertionCount
                    && indexInOldShuffled == insertionPoints[indexInInsertionList]
                ) {
                    newShuffled[i] = insertionValues[indexInInsertionList++]
                } else {
                    newShuffled[i] = shuffled[indexInOldShuffled++]
                    if (newShuffled[i] >= insertionIndex) {
                        newShuffled[i] += insertionCount
                    }
                }
            }
            return DefaultShuffleOrder(newShuffled, Random(random.nextLong()))
        }

        override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
            val numberOfElementsToRemove = indexToExclusive - indexFrom
            val newShuffled = IntArray(shuffled.size - numberOfElementsToRemove)
            var foundElementsCount = 0
            for (i in shuffled.indices) {
                if (shuffled[i] in indexFrom..<indexToExclusive) {
                    foundElementsCount++
                } else {
                    newShuffled[i - foundElementsCount] =
                        if (shuffled[i] >= indexFrom) shuffled[i] - numberOfElementsToRemove else shuffled[i]
                }
            }
            return DefaultShuffleOrder(newShuffled, Random(random.nextLong()))
        }

        override fun cloneAndClear(): ShuffleOrder {
            return DefaultShuffleOrder(0, Random(random.nextLong()))
        }

        companion object {
            private fun createShuffledList(length: Int, random: Random): IntArray {
                val shuffled = IntArray(length)
                for (i in 0..<length) {
                    val swapIndex = random.nextInt(i + 1)
                    shuffled[i] = shuffled[swapIndex]
                    shuffled[swapIndex] = i
                }
                return shuffled
            }
        }
    }

    class UnshuffledShuffleOrder(override val length: Int) : ShuffleOrder {

        override fun getNextIndex(index: Int): Int {
            var index = index
            return if (++index < length) index else C.INDEX_UNSET
        }

        override fun getPreviousIndex(index: Int): Int {
            var index = index
            return if (--index >= 0) index else C.INDEX_UNSET
        }

        override val lastIndex = if (length > 0) length - 1 else C.INDEX_UNSET
        override val firstIndex = if (length > 0) 0 else C.INDEX_UNSET

        override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
            return UnshuffledShuffleOrder(length + insertionCount)
        }

        override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
            return UnshuffledShuffleOrder(length - indexToExclusive + indexFrom)
        }

        override fun cloneAndClear(): ShuffleOrder {
            return UnshuffledShuffleOrder(0)
        }
    }

    val length: Int

    fun getNextIndex(index: Int): Int
    fun getPreviousIndex(index: Int): Int

    val lastIndex: Int
    val firstIndex: Int

    fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder
    fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        return this
    }

    fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder {
        return cloneAndClear().cloneAndInsert(0, insertionCount)
    }

    fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder

    fun cloneAndClear(): ShuffleOrder
}
