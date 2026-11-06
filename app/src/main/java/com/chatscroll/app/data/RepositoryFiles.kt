package com.chatscroll.app.data

import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Errors are intentionally surfaced: callers must not mistake damaged storage for an empty library. */
class ConversationStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Confirmation was based on an older repository state. Refresh the preview before trying again. */
class ConversationStorageConflictException : IOException("Storage changed; refresh the preview")

internal object RepositoryFiles {
    private val locks = ConcurrentHashMap<String, Any>()
    fun lock(directory: File): Any = locks.getOrPut(directory.canonicalPath) { Any() }

    fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw ConversationStorageException("Cannot create private storage directory")
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // Persist directory entries on filesystems that support directory fsync (Android/Linux).
        // Windows does not expose readable directory channels; atomic rename + file fsync still apply.
        try {
            FileChannel.open(to.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) { }
        catch (_: UnsupportedOperationException) { }
    }

    private fun flush(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    /** The old committed value remains in .bak; unfinished .tmp files are never accepted as commits. */
    fun write(file: File, bytes: ByteArray, previousBytes: ByteArray? = null) {
        ensureDirectory(file.parentFile!!)
        val temporary = File(file.path + ".tmp")
        val backup = File(file.path + ".bak")
        val backupTemporary = File(file.path + ".bak.tmp")
        try {
            flush(temporary, bytes)
            if (previousBytes != null) {
                if (file.exists() && !file.isFile) throw ConversationStorageException("Storage target is not a regular file")
                flush(backupTemporary, previousBytes)
            } else if (file.exists()) {
                if (!file.isFile) throw ConversationStorageException("Storage target is not a regular file")
                flush(backupTemporary, file.readBytes())
            } else {
                // A first save is recoverable too, even before a second version exists.
                flush(backupTemporary, bytes)
            }
            move(backupTemporary, backup)
            move(temporary, file)
        } catch (error: IOException) {
            throw ConversationStorageException("Cannot commit ${file.name}", error)
        } finally {
            temporary.delete()
            backupTemporary.delete()
        }
    }

    fun <T> read(file: File, maximumBytes: Long, repair: Boolean = true, decode: (ByteArray) -> T): T? {
        val backup = File(file.path + ".bak")
        var failure: Exception? = null
        for (candidate in listOf(file, backup)) {
            if (!candidate.exists()) continue
            try {
                if (!candidate.isFile || candidate.length() > maximumBytes) {
                    throw ConversationStorageException("Invalid storage file ${candidate.name}")
                }
                val bytes = candidate.readBytes()
                val value = decode(bytes)
                if (repair && candidate == backup) {
                    // Do not copy the corrupt primary over the healthy backup during repair.
                    val temporary = File(file.path + ".recover.tmp")
                    try {
                        flush(temporary, bytes)
                        move(temporary, file)
                    } finally {
                        temporary.delete()
                    }
                }
                return value
            } catch (error: Exception) {
                if (error !is IOException && error !is SerializationException && error !is IllegalArgumentException) throw error
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        if (failure != null) throw ConversationStorageException("Cannot recover ${file.name}", failure)
        return null
    }

    fun deleteTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            val children = file.listFiles() ?: throw ConversationStorageException("Cannot read storage directory")
            children.forEach(::deleteTree)
        }
        if (!file.delete()) throw ConversationStorageException("Cannot delete ${file.name}")
    }
}

internal fun searchExcerpt(content: String, needle: String): String? {
    val at = content.indexOf(needle, ignoreCase = true)
    if (at < 0) return null
    val start = (at - 20).coerceAtLeast(0)
    val end = (at + needle.length + 20).coerceAtMost(content.length)
    val core = content.substring(start, end).replace('\n', ' ').trim()
    return (if (start > 0) "…" else "") + core + (if (end < content.length) "…" else "")
}
