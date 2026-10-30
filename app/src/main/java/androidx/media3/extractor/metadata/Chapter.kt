/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.extractor.metadata

import androidx.media3.common.C
import androidx.media3.common.Label
import androidx.media3.common.Metadata

import com.google.errorprone.annotations.CanIgnoreReturnValue

interface Chapter : Metadata.Entry {
    
    class Builder {
        private var startTimeMs: Long
        private var endTimeMs: Long
        private var isHidden = false
        private var title: Label? = null

        init {
            this.startTimeMs = C.TIME_UNSET
            this.endTimeMs = C.TIME_UNSET
        }

        fun setStartTimeMs(startTimeMs: Long): Builder {
            this.startTimeMs = startTimeMs
            return this
        }

        fun setEndTimeMs(endTimeMs: Long): Builder {
            this.endTimeMs = endTimeMs
            return this
        }

        fun setHidden(isHidden: Boolean): Builder {
            this.isHidden = isHidden
            return this
        }

        fun setTitle(title: Label?): Builder {
            this.title = title
            return this
        }

        fun build(): Chapter {
            return ChapterImpl(startTimeMs, endTimeMs, isHidden, title)
        }
    }

    val startTimeMs: Long

    val endTimeMs: Long

    val isHidden: Boolean

    val title: Label?
}
