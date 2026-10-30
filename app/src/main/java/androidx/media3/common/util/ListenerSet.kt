package androidx.media3.common.util

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import com.google.common.base.Preconditions
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet

class ListenerSet<T : Any> private constructor(
    private val listeners: CopyOnWriteArraySet<ListenerHolder<T>>,
    looper: Looper?,
    private val thread: Thread?,
    private val clock: Clock?,
    private val iterationFinishedEvent: IterationFinishedEvent<T>?,
    throwsWhenUsingWrongThread: Boolean
) {
    
    fun interface Event<T> {
        fun invoke(listener: T)
    }

    fun interface IterationFinishedEvent<T> {
        fun invoke(listener: T, eventFlags: FlagSet)
    }

    private val iterationFinishedHandler: HandlerWrapper?
    private val flushingEvents: ArrayDeque<Runnable?>
    private val queuedEvents: ArrayDeque<Runnable?>
    private val releasedLock: Any

    @GuardedBy("releasedLock")
    private var released = false

    private var throwsWhenUsingWrongThread: Boolean

    constructor(looper: Looper) : this(looper.getThread())

    constructor(thread: Thread?) : this( 
        CopyOnWriteArraySet<ListenerHolder<T>>(),  
        null,  
        thread,  
        null,  
        null,  
        true
    )

    constructor(
        looper: Looper,
        clock: Clock?,
        iterationFinishedEvent: IterationFinishedEvent<T>?
    ) : this( 
        CopyOnWriteArraySet<ListenerHolder<T>>(),
        looper,  
        looper.getThread(),
        clock,
        iterationFinishedEvent,  
        true
    )

    init {
        releasedLock = Any()
        flushingEvents = ArrayDeque<Runnable?>()
        queuedEvents = ArrayDeque<Runnable?>()
        if (looper != null && clock != null && iterationFinishedEvent != null) {
            // It's safe to use "this" because we don't send a message before exiting the constructor.
            val handler = clock.createHandler(
                looper,
                Handler.Callback { message: Message? -> this.handleMessage(message) })
            this.iterationFinishedHandler = handler
        } else {
            this.iterationFinishedHandler = null
        }
        this.throwsWhenUsingWrongThread = throwsWhenUsingWrongThread
    }

    fun copy(
        looper: Looper, iterationFinishedEvent: IterationFinishedEvent<T>?
    ): ListenerSet<T> {
        return copy(looper, clock, iterationFinishedEvent)
    }

    fun copy(looper: Looper): ListenerSet<T> {
        return copy(looper, clock, iterationFinishedEvent)
    }

    fun copy(clock: Clock?): ListenerSet<T> {
        if (iterationFinishedHandler != null) {
            return copy(iterationFinishedHandler.looper, clock, iterationFinishedEvent)
        }
        return ListenerSet<T>(
            listeners,  
            null,
            thread,
            clock,  
            null,
            throwsWhenUsingWrongThread
        )
    }

    fun copy(
        looper: Looper,
        clock: Clock?,
        iterationFinishedEvent: IterationFinishedEvent<T>?
    ): ListenerSet<T> {
        Preconditions.checkState(clock != null || iterationFinishedEvent == null)
        return ListenerSet<T>(
            listeners,
            looper,
            looper.getThread(),
            clock,
            iterationFinishedEvent,
            throwsWhenUsingWrongThread
        )
    }

    val isRunningOnCorrectThread: Boolean
        
        get() = Thread.currentThread() === thread

    fun add(listener: T) {
        synchronized(releasedLock) {
            if (released) {
                return
            }
            listeners.add(ListenerSet.ListenerHolder<T>(listener))
        }
    }

    fun remove(listener: T?) {
        verifyCurrentThread()
        for (listenerHolder in listeners) {
            if (listenerHolder.listener == listener) {
                listenerHolder.release(iterationFinishedEvent)
                listeners.remove(listenerHolder)
            }
        }
    }

    fun clear() {
        verifyCurrentThread()
        for (listenerHolder in listeners) {
            listenerHolder.release(iterationFinishedEvent)
        }
        listeners.clear()
    }

    fun size(): Int {
        verifyCurrentThread()
        return listeners.size
    }

    fun queueEvent(event: (T)-> Unit) {
        queueEvent( C.INDEX_UNSET, event)
    }

    fun queueEvent(eventFlag: Int, event: (T)-> Unit) {
        verifyCurrentThread()
        val listenerSnapshot = CopyOnWriteArraySet<ListenerHolder<T>>(listeners)
        queuedEvents.add(
            Runnable {
                for (holder in listenerSnapshot) {
                    holder.invoke(eventFlag, event)
                }
            })
    }

    fun flushEvents() {
        verifyCurrentThread()
        if (queuedEvents.isEmpty()) {
            return
        }
        if (iterationFinishedEvent != null
            && !iterationFinishedHandler!!.hasMessages(MSG_ITERATION_FINISHED)
        ) {
            iterationFinishedHandler.sendMessageAtFrontOfQueue(
                iterationFinishedHandler.obtainMessage(MSG_ITERATION_FINISHED)
            )
        }
        val recursiveFlushInProgress = !flushingEvents.isEmpty()
        flushingEvents.addAll(queuedEvents)
        queuedEvents.clear()
        if (recursiveFlushInProgress) {
            // Recursive call to flush. Let the outer call handle the flush queue.
            return
        }
        while (!flushingEvents.isEmpty()) {
            flushingEvents.peekFirst()!!.run()
            flushingEvents.removeFirst()
        }
    }

    fun sendEvent(event: (T)-> Unit) {
        sendEvent( C.INDEX_UNSET, event)
    }

    fun sendEvent(eventFlag: Int, event: (T)-> Unit) {
        queueEvent(eventFlag, event)
        flushEvents()
    }

    fun release() {
        verifyCurrentThread()
        synchronized(releasedLock) {
            released = true
        }
        for (listenerHolder in listeners) {
            listenerHolder.release(iterationFinishedEvent)
        }
        listeners.clear()
    }

    @Deprecated("Do not use this method and ensure all calls are made from the correct thread.")
    fun setThrowsWhenUsingWrongThread(throwsWhenUsingWrongThread: Boolean) {
        this.throwsWhenUsingWrongThread = throwsWhenUsingWrongThread
    }

    private fun handleMessage(message: Message?): Boolean {
        val event = iterationFinishedEvent!!
        for (holder in listeners) {
            holder.iterationFinished(event)
            if (iterationFinishedHandler!!.hasMessages(MSG_ITERATION_FINISHED)) {
                // The invocation above triggered new events (and thus scheduled a new message). We need
                // to stop here because this new message will take care of informing every listener about
                // the new update (including the ones already called here).
                break
            }
        }
        return true
    }

    private fun verifyCurrentThread() {
        if (!throwsWhenUsingWrongThread) {
            return
        }
        Preconditions.checkState(this.isRunningOnCorrectThread)
    }

    private class ListenerHolder<T : Any>(val listener: T) {
        private var flagsBuilder: FlagSet.Builder
        private var needsIterationFinishedEvent = false
        private var released = false

        init {
            this.flagsBuilder = FlagSet.Builder()
        }

        fun release(event: IterationFinishedEvent<T>?) {
            released = true
            if (event != null && needsIterationFinishedEvent) {
                needsIterationFinishedEvent = false
                event.invoke(listener, flagsBuilder.build())
            }
        }

        fun invoke(eventFlag: Int, event: (T)-> Unit) {
            if (!released) {
                if (eventFlag != C.INDEX_UNSET) {
                    flagsBuilder.add(eventFlag)
                }
                needsIterationFinishedEvent = true
                event.invoke(listener)
            }
        }

        fun iterationFinished(event: IterationFinishedEvent<T>) {
            if (!released && needsIterationFinishedEvent) {
                // Reset flags before invoking the listener to ensure we keep all new flags that are set by
                // recursive events triggered from this callback.
                val flagsToNotify = flagsBuilder.build()
                flagsBuilder = FlagSet.Builder()
                needsIterationFinishedEvent = false
                event.invoke(listener, flagsToNotify)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null || javaClass != other.javaClass) {
                return false
            }
            return listener == (other as ListenerHolder<*>).listener
        }

        override fun hashCode(): Int {
            return listener.hashCode()
        }
    }

    companion object {
        private const val MSG_ITERATION_FINISHED = 1
    }
}
