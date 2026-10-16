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

import android.system.Os
import be.mygod.librootkotlinx.ParcelableBoolean
import be.mygod.librootkotlinx.RootCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Null content deletes a rule. Unlock, mutation and relock stay in ONE root request. */
@Parcelize
data class RootMutateIfwCommand(
    private val directory: String,
    private val packageName: String,
    private val content: String?,
) : RootCommand<ParcelableBoolean> {
    override suspend fun execute(): ParcelableBoolean = withContext(Dispatchers.IO) {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*"))) {
            "Invalid IFW package name"
        }
        val folder = File(directory).canonicalFile
        require(folder.path in setOf("/data/system/ifw", "/data/secure/system/ifw")) {
            "Unexpected IFW directory: $folder"
        }
        check(folder.isDirectory) { "IFW directory is missing: $folder" }
        ParcelableBoolean(
            transaction.mutate(setImmutable = { immutable ->
                setImmutable(folder, immutable)
            }) {
                val target = File(folder, "$packageName.xml")
                if (content == null) {
                    if (!target.exists()) {
                        false
                    } else {
                        if (!target.delete()) throw IOException("Failed to delete IFW rules: $target")
                        true
                    }
                } else {
                    // Never truncate the live XML: publish a complete, synced, readable file.
                    val temporary = File.createTempFile(".blocker-", ".tmp", folder)
                    try {
                        FileOutputStream(temporary).use { stream ->
                            stream.write(content.toByteArray(Charsets.UTF_8))
                            stream.fd.sync()
                        }
                        Os.chmod(temporary.path, "644".toInt(8))
                        Os.rename(temporary.path, target.path)
                    } finally {
                        if (temporary.exists() && !temporary.delete()) {
                            throw IOException("Failed to remove IFW temporary file: $temporary")
                        }
                    }
                    true
                }
            },
        )
    }

    private suspend fun setImmutable(folder: File, immutable: Boolean) {
        // Initial state is unknown. A mutable directory is valid, not an error.
        if (readImmutable(folder) == immutable) return
        // Absolute binary and argument arrays: no shell interpolation or recursive flags.
        val result = RootProcessCommand(
            listOf("/system/bin/chattr", if (immutable) "+i" else "-i", folder.path),
        ).execute()
        if (!result.isSuccess) {
            throw IOException("IFW chattr failed (${result.code}): ${result.err.joinToString()}")
        }
        if (readImmutable(folder) != immutable) {
            throw IOException("IFW immutable verification failed: expected immutable=$immutable")
        }
    }

    private suspend fun readImmutable(folder: File): Boolean {
        val attributes = RootProcessCommand(listOf("/system/bin/lsattr", "-d", folder.path)).execute()
        val flags = attributes.out.singleOrNull()?.trim()?.substringBefore(' ')
        if (!attributes.isSuccess || flags == null || !flags.matches(Regex("[a-zA-Z-]+"))) {
            throw IOException("Failed to read IFW attributes: ${attributes.out} ${attributes.err}")
        }
        return 'i' in flags
    }

    private companion object {
        val transaction = IfwDirectoryTransaction()
    }
}
