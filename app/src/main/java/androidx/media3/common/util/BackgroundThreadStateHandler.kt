package androidx.media3.common.util

import android.os.Looper
import com.google.common.base.Function
import com.google.common.base.Preconditions

class BackgroundThreadStateHandler<T : Any>(
    private var foregroundState: T,
    backgroundLooper: Looper,
    foregroundLooper: Looper,
    clock: Clock,
    onStateChanged: StateChangeListener<T>
) {
    
    fun interface StateChangeListener<T : Any> {
        fun onStateChanged(oldState: T, newState: T)
    }

    private val backgroundHandler: HandlerWrapper
    private val foregroundHandler: HandlerWrapper
    private val onStateChanged: StateChangeListener<T>

    private var backgroundState: T
    private var pendingOperations = 0

    init {
        backgroundHandler = clock.createHandler(backgroundLooper,  null)
        foregroundHandler = clock.createHandler(foregroundLooper,  null)
        backgroundState = foregroundState
        this.onStateChanged = onStateChanged
    }

    fun get(): T? {
        val myLooper = Looper.myLooper()
        if (myLooper == foregroundHandler.looper) {
            return foregroundState
        }
        Preconditions.checkState(myLooper == backgroundHandler.looper)
        return backgroundState
    }

    fun updateStateAsync(
        placeholderState: (T)->T, backgroundStateUpdate: (T)->T
    ) {
        Preconditions.checkState(Looper.myLooper() == foregroundHandler.looper)
        pendingOperations++
        runInBackground(
            Runnable {
                backgroundState = backgroundStateUpdate(backgroundState)
                val newState: T = backgroundState
                runInForeground(
                    Runnable {
                        if (--pendingOperations == 0) {
                            updateStateInForeground(newState)
                        }
                    })
            })
        updateStateInForeground(placeholderState(foregroundState))
    }

    fun setStateInBackground(newState: T) {
        backgroundState = newState
        runInForeground(
            Runnable {
                if (pendingOperations == 0) {
                    updateStateInForeground(newState)
                }
            })
    }

    fun runInBackground(runnable: Runnable) {
        if (!backgroundHandler.looper.getThread().isAlive()) {
            // Avoid sending messages on dead thread.
            return
        }
        backgroundHandler.post(runnable)
    }

    private fun runInForeground(runnable: Runnable) {
        if (!foregroundHandler.looper.getThread().isAlive()) {
            // Avoid sending messages on dead thread.
            return
        }
        foregroundHandler.post(runnable)
    }

    private fun updateStateInForeground(newState: T) {
        val oldState: T = foregroundState
        foregroundState = newState
        if (oldState != newState) {
            onStateChanged.onStateChanged(oldState, newState)
        }
    }
}
