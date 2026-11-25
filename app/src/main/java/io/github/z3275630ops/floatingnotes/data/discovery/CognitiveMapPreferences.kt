package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.backup.BackupValidationException
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

internal class CognitiveMapPreferences(private val filesDir: File) {
    private val renamesFile = File(filesDir, "cognitive_map_renames.json")

    fun loadRenames(): Map<String, String> = synchronized(writeLock) { readRenames() }

    fun renameCluster(clusterId: String, customName: String): Map<String, String> = synchronized(writeLock) {
        val current = readRenames()
        val name = customName.trim()
        val updated = if (name.isBlank()) current - clusterId else current + (clusterId to name)
        if (updated != current) writeRenames(updated)
        updated
    }

    fun clearRename(clusterId: String): Map<String, String> = renameCluster(clusterId, "")

    private fun readRenames(): Map<String, String> {
        val text = try {
            Files.readAllBytes(renamesFile.toPath()).toString(Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return emptyMap()
        }
        return try {
            MiniJson.parseObject(text).mapNotNull { (id, value) ->
                (value as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { id to it }
            }.toMap()
        } catch (_: BackupValidationException) {
            emptyMap()
        }
    }

    private fun writeRenames(renames: Map<String, String>) {
        Files.createDirectories(filesDir.toPath())
        val temporary = File.createTempFile("cognitive_map_renames.", ".tmp", filesDir)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(MiniJson.encode(renames.toSortedMap()).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // A provider that cannot replace atomically must leave the previous file intact.
            Files.move(
                temporary.toPath(), renamesFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        // Separate instances must serialize their read-modify-write operations in this process.
        val writeLock = Any()
    }
}
