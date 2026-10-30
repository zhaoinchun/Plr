/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common.util

import androidx.annotation.IntDef

class StuckPlayerException
(
    
    val stuckType: @StuckType Int,
    
    val timeoutMs: Int
) : IllegalStateException(
    getMessage(
        stuckType,
        timeoutMs
    )
) {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STUCK_BUFFERING_NOT_LOADING, STUCK_BUFFERING_NO_PROGRESS, STUCK_PLAYING_NO_PROGRESS, STUCK_PLAYING_NOT_ENDING, STUCK_SUPPRESSED
        ]
    )
    annotation class StuckType

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as StuckPlayerException
        return this.stuckType == other.stuckType && timeoutMs == other.timeoutMs
    }

    override fun hashCode(): Int {
        var hashCode = 17
        hashCode = 31 * hashCode + stuckType
        hashCode = 31 * hashCode + timeoutMs
        return hashCode
    }

    companion object {
        
        const val STUCK_BUFFERING_NOT_LOADING: Int = 0

        const val STUCK_BUFFERING_NO_PROGRESS: Int = 1

        const val STUCK_PLAYING_NO_PROGRESS: Int = 2

        const val STUCK_PLAYING_NOT_ENDING: Int = 3

        const val STUCK_SUPPRESSED: Int = 4

        private fun getMessage(stuckType: @StuckType Int, timeoutMs: Int): String {
            when (stuckType) {
                STUCK_BUFFERING_NOT_LOADING -> return "Player stuck buffering and not loading for " + timeoutMs + " ms"
                STUCK_BUFFERING_NO_PROGRESS -> return "Player stuck buffering with no progress for " + timeoutMs + " ms"
                STUCK_PLAYING_NO_PROGRESS -> return "Player stuck playing with no progress for " + timeoutMs + " ms"
                STUCK_PLAYING_NOT_ENDING -> return "Player stuck playing without ending for " + timeoutMs + " ms"
                STUCK_SUPPRESSED -> return "Player stuck suppressed for " + timeoutMs + " ms"
                else -> throw IllegalStateException()
            }
        }
    }
}
