package com.galaxy.assettrail.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface StateStore<T> {
    suspend fun read(): T?

    suspend fun write(value: T)

    suspend fun clear()
}

val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** A JSON file replaced atomically, so a crash mid-write never loses the check-in queue. */
class FileStateStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val json: Json = StoreJson,
) : StateStore<T> {

    override suspend fun read(): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(serializer, file.readText()) }
            .onFailure {
                // Keep the unreadable file for inspection instead of silently overwriting it.
                file.renameTo(File(file.parentFile, file.name + ".unreadable-" + System.currentTimeMillis()))
            }
            .getOrNull()
    }

    override suspend fun write(value: T) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(serializer, value))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }
}

class MemoryStateStore<T>(private var value: T? = null) : StateStore<T> {
    override suspend fun read(): T? = value

    override suspend fun write(value: T) {
        this.value = value
    }

    override suspend fun clear() {
        value = null
    }
}
