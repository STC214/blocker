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

package com.merxury.core.ifw

import android.content.ComponentName
import com.merxury.blocker.core.model.ComponentType
import com.merxury.blocker.core.testing.controller.FakeComponentTypeResolver
import com.merxury.blocker.core.testing.controller.FakeIfwFileSystem
import com.merxury.blocker.core.testing.controller.FakeRootAvailabilityChecker
import com.merxury.core.ifw.model.IfwComponentType
import com.merxury.core.ifw.xml.IfwXmlDeserializer
import com.merxury.core.ifw.xml.IfwXmlSerializer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IntentFirewallMutationTest {
    private val resolver = FakeComponentTypeResolver()
    private val storage = RecordingStorage()
    private val firewall = IntentFirewall(
        FakeRootAvailabilityChecker(true),
        resolver,
        storage,
        IfwXmlSerializer(),
        IfwXmlDeserializer(),
    )

    private fun component(pkg: String, name: String): ComponentName {
        resolver.setComponentType(pkg, "$pkg.$name", ComponentType.SERVICE)
        return ComponentName(pkg, "$pkg.$name")
    }

    @Test
    fun singleMultiAllAndDeselectAllUseUnifiedStorage() = runTest {
        val a = component("test.one", "A")
        val b = component("test.one", "B")
        val c = component("test.two", "C")
        firewall.addComponentFilter(a.packageName, a.className)
        firewall.addAllComponentFilters(listOf(b, c, b)) { item ->
            assertFalse(firewall.getComponentEnableState(item.packageName, item.className))
        }
        assertEquals(listOf("write:test.one", "write:test.one", "write:test.two"), storage.events)
        firewall.removeComponentFilter(a.packageName, a.className)
        assertTrue(storage.fileExists(a.packageName))
        firewall.removeAllComponentFilters(listOf(b, c)) { item ->
            assertTrue(firewall.getComponentEnableState(item.packageName, item.className))
        }
        assertTrue(storage.listRuleFiles().isEmpty())
        assertEquals(listOf("delete:test.one", "delete:test.two"), storage.events.takeLast(2))
    }

    @Test
    fun noSelectionDoesNotMutateAnything() = runTest {
        firewall.addAllComponentFilters(emptyList()) { error("Unexpected callback") }
        firewall.removeAllComponentFilters(emptyList()) { error("Unexpected callback") }
        assertTrue(storage.events.isEmpty())
    }

    @Test
    fun providersDoNotProduceEmptyIfwWrites() = runTest {
        val provider = ComponentName("test.one", "test.one.Provider")
        firewall.addAllComponentFilters(listOf(provider)) { error("Provider has no IFW support") }
        assertTrue(storage.events.isEmpty())
    }

    @Test
    fun writeFailureNeverNotifiesSuccess() = runTest {
        storage.failWrite = true
        val item = component("test.one", "A")
        var callbacks = 0
        assertFailsWith<IOException> {
            firewall.addAllComponentFilters(listOf(item)) { callbacks++ }
        }
        assertEquals(0, callbacks)
    }

    @Test
    fun deleteFailureNeverNotifiesSuccess() = runTest {
        val item = component("test.one", "A")
        firewall.addComponentFilter(item.packageName, item.className)
        storage.failDelete = true
        var callbacks = 0
        assertFailsWith<IOException> {
            firewall.removeAllComponentFilters(listOf(item)) { callbacks++ }
        }
        assertEquals(0, callbacks)
    }

    @Test
    fun overlappingSingleAndBatchSelectionsDoNotLoseUpdates() = runTest {
        val items = (0..9).map { component("test.one", "Service$it") }
        val first = launch { firewall.addAllComponentFilters(items.take(5)) {} }
        val others = items.drop(5).map { item ->
            launch { firewall.addComponentFilter(item.packageName, item.className) }
        }
        first.join()
        others.forEach { it.join() }
        assertEquals(10, firewall.getRules("test.one").componentFiltersFor(IfwComponentType.SERVICE).size)
    }

    @Test
    fun failureAfterCommitInvalidatesOldCache() = runTest {
        val item = component("test.one", "A")
        assertTrue(firewall.getRules(item.packageName).isEmpty())
        storage.failAfterWrite = true
        assertFailsWith<IOException> { firewall.addComponentFilter(item.packageName, item.className) }
        assertFalse(firewall.getComponentEnableState(item.packageName, item.className))
    }

    @Test
    fun mutationReloadsRulesChangedAfterQuery() = runTest {
        val a = component("test.one", "A")
        val b = component("test.one", "B")
        assertTrue(firewall.getRules(a.packageName).isEmpty())
        storage.writeRules(a.packageName, """<rules><service block="true"><component-filter name="${a.flattenToString()}" /></service></rules>""")
        firewall.addComponentFilter(b.packageName, b.className)
        assertEquals(2, firewall.getRules(a.packageName).componentFiltersFor(IfwComponentType.SERVICE).size)
    }

    @Test
    fun malformedRulesAreNeverOverwrittenByMutation() = runTest {
        val item = component("test.one", "A")
        storage.writeRules(item.packageName, "<rules><service")
        firewall.getRules(item.packageName)
        storage.events.clear()
        assertFailsWith<XmlPullParserException> { firewall.addComponentFilter(item.packageName, item.className) }
        assertTrue(storage.events.isEmpty())
        assertEquals("<rules><service", storage.readRules(item.packageName))
    }

    @Test
    fun readFailureStopsMutationWithoutSuccessCallback() = runTest {
        val item = component("test.one", "A")
        storage.failRead = true
        assertFailsWith<IOException> {
            firewall.addAllComponentFilters(listOf(item)) { error("Unexpected callback") }
        }
        assertTrue(storage.events.isEmpty())
    }

    @Test
    fun blockingDoesNotReuseLoggingOnlyOrConditionalRules() = runTest {
        val item = component("test.one", "A")
        val original = """<rules><service block="false" log="true"><component-filter name="${item.flattenToString()}" /></service><service block="true"><sender type="system" /><component-filter name="test.one/test.one.B" /></service></rules>"""
        storage.writeRules(item.packageName, original)
        val before = firewall.getRules(item.packageName)
        assertTrue(firewall.getComponentEnableState(item.packageName, item.className))
        firewall.addComponentFilter(item.packageName, item.className)
        val after = firewall.getRules(item.packageName)
        assertEquals(before.rules, after.rules.take(2))
        assertEquals(3, after.rules.size)
        assertFalse(firewall.getComponentEnableState(item.packageName, item.className))
    }

    @Test
    fun removingComponentPreservesIntentSelectorsAndRemovesDuplicatesAcrossRules() = runTest {
        val item = component("test.one", "A")
        storage.writeRules(item.packageName, """<rules><service block="true"><component-filter name="${item.flattenToString()}" /><intent-filter><action name="test.ACTION" /></intent-filter></service><service block="true"><component-filter name="${item.flattenToString()}" /><component-filter name="${item.flattenToString()}" /></service></rules>""")
        firewall.removeComponentFilter(item.packageName, item.className)
        val remaining = firewall.getRules(item.packageName)
        assertEquals(1, remaining.rules.size)
        assertEquals(listOf("test.ACTION"), remaining.rules.single().intentFilters.single().actions)
        assertTrue(remaining.componentFiltersFor(IfwComponentType.SERVICE).isEmpty())
    }

    private class RecordingStorage : IfwFileSystem {
        private val delegate = FakeIfwFileSystem()
        val events = mutableListOf<String>()
        var failWrite = false
        var failRead = false
        var failDelete = false
        var failAfterWrite = false

        override suspend fun readRules(packageName: String): String? {
            yield()
            if (failRead) throw IOException("read failed")
            return delegate.readRules(packageName)
        }

        override suspend fun writeRules(packageName: String, content: String) {
            yield()
            if (failWrite) throw IOException("write failed")
            delegate.writeRules(packageName, content)
            if (failAfterWrite) throw IOException("restore failed after commit")
            events += "write:$packageName"
        }

        override suspend fun deleteRules(packageName: String): Boolean {
            yield()
            if (failDelete) throw IOException("delete failed")
            events += "delete:$packageName"
            return delegate.deleteRules(packageName)
        }

        override suspend fun fileExists(packageName: String): Boolean = delegate.fileExists(packageName)
        override suspend fun listRuleFiles(): List<String> = delegate.listRuleFiles()
    }
}
