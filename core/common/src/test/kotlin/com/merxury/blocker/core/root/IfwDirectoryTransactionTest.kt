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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IfwDirectoryTransactionTest {
    private val transaction = IfwDirectoryTransaction()

    @Test
    fun successUnlocksWritesThenLocks() = runTest {
        val events = mutableListOf<String>()
        val result = transaction.mutate({ events += "immutable=$it" }) {
            events += "write"
            42
        }
        assertEquals(42, result)
        assertEquals(listOf("immutable=false", "write", "immutable=true"), events)
    }

    @Test
    fun writeFailureStillLocksAndPreservesError() = runTest {
        val events = mutableListOf<Boolean>()
        val error = IOException("write failed")
        val actual = assertFailsWith<IOException> {
            transaction.mutate({ events += it }) { throw error }
        }
        assertEquals(error.message, actual.message)
        assertEquals(listOf(false, true), events)
    }

    @Test
    fun unlockFailureSkipsWriteButAttemptsLock() = runTest {
        val events = mutableListOf<Boolean>()
        assertFailsWith<IOException> {
            transaction.mutate({
                events += it
                if (!it) throw IOException("unlock failed")
            }) { error("Must not write after failed unlock") }
        }
        assertEquals(listOf(false, true), events)
    }

    @Test
    fun restoreFailureIsVisibleAndPreservesWriteFailure() = runTest {
        val writeError = IOException("write failed")
        val restoreError = IOException("lock failed")
        val error = assertFailsWith<IOException> {
            transaction.mutate({ if (it) throw restoreError }) { throw writeError }
        }
        // Coroutine stack-trace recovery may wrap/copy exceptions on JVM.
        val original = generateSequence<Throwable>(error) { it.cause }
            .first { it.suppressed.isNotEmpty() }
        assertEquals(restoreError.message, original.cause?.message)
        assertEquals(writeError.message, original.suppressed.single().message)
    }

    @Test
    fun restoreFailureAfterSuccessfulWriteIsNotSuccess() = runTest {
        assertFailsWith<IOException> {
            transaction.mutate({ if (it) throw IOException("lock failed") }) { true }
        }
    }

    @Test
    fun initiallyMutableAndImmutableBothFinishImmutable() = runTest {
        for (initialState in listOf(false, true)) {
            var immutable = initialState
            transaction.mutate({ immutable = it }) { assertTrue(!immutable) }
            assertTrue(immutable)
        }
    }

    @Test
    fun cancellationDuringWriteStillCompletesAndLocks() = runTest {
        val events = mutableListOf<String>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            transaction.mutate({ events += "immutable=$it" }) {
                finish.await()
                events += "write"
            }
        }
        runCurrent()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertEquals(listOf("immutable=false", "write", "immutable=true"), events)
    }

    @Test
    fun concurrentMutationsNeverOverlapUnlockWindows() = runTest {
        var locked = true
        val events = mutableListOf<Int>()
        val firstFinished = CompletableDeferred<Unit>()
        val jobs = (0..9).map { index ->
            launch {
                transaction.mutate({ locked = it }) {
                    assertTrue(!locked)
                    if (index == 0) firstFinished.await()
                    events += index
                }
            }
        }
        runCurrent()
        assertTrue(events.isEmpty())
        firstFinished.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals((0..9).toList(), events)
        assertTrue(locked)
    }
}
