/*
 * Copyright 2026 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.merxury.blocker.core.root

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Shared by all IFW mutations in the root process, not by individual UI selections. */
internal class IfwDirectoryTransaction {
    private val mutex = Mutex()

    suspend fun <T> mutate(
        setImmutable: suspend (Boolean) -> Unit,
        operation: suspend () -> T,
    ): T = mutex.withLock {
        // Once started, finish the mutation AND relock even if the caller cancels.
        // Process death/power loss cannot be covered by a Kotlin finally block.
        withContext(NonCancellable) {
            var failure: Throwable? = null
            try {
                setImmutable(false)
                operation()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    setImmutable(true)
                } catch (restoreError: Throwable) {
                    // Do not report success when the rules were saved but protection failed.
                    throw IOException(
                        "Failed to restore IFW directory immutable protection; check directory attributes",
                        restoreError,
                    ).apply { failure?.let(::addSuppressed) }
                }
            }
        }
    }
}
