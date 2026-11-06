package com.chatscroll.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ConversationFoldersTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun repository(name: String = "library") = FileConversationRepository(temporary.newFolder(name))
    private fun conversation(id: String = "chat", text: String = "answer") = Conversation(
        id = id, title = "Title", importedAt = 42L,
        messages = listOf(ChatMessage("user", "question"), ChatMessage("assistant", text))
    )
    private fun files(repository: FileConversationRepository): Map<String, String> = repository.directory.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(repository.directory).invariantSeparatorsPath to
            Base64.getEncoder().encodeToString(it.readBytes()) }
    private fun recordFiles(repository: FileConversationRepository) = files(repository).filterKeys {
        !ConversationFolders.recognizes(it)
    }
    private fun body(repository: FileConversationRepository, id: String = "chat"): File {
        val fields = json.parseToJsonElement(File(repository.directory, "$id.meta.json").readText()).jsonObject
        return File(repository.directory, "$id.revisions/${fields.getValue("_revision").jsonPrimitive.content}.json")
    }
    private fun expectInvalid(action: () -> Unit) {
        try { action(); fail("Expected invalid argument") } catch (_: IllegalArgumentException) { }
    }
    private fun expectIo(action: () -> Unit) {
        try { action(); fail("Expected storage failure") } catch (_: IOException) { }
    }
    private fun backup(repository: FileConversationRepository) = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
    private fun restore(repository: FileConversationRepository, bytes: ByteArray) = repository.restoreBackup(ByteArrayInputStream(bytes))

    @Test fun namesAreTrimmedUniqueAndIdsSurviveRename() {
        val repository = repository()
        val folder = repository.createFolder("  Work  ")
        assertEquals("Work", folder.name)
        assertTrue(FileConversationRepository.ID_PATTERN.matches(folder.id))
        assertTrue(folder.createdAt > 0)
        expectInvalid { repository.createFolder("work") }
        expectInvalid { repository.createFolder(" \n ") }
        expectInvalid { repository.createFolder("x".repeat(61)) }
        repository.createFolder("x".repeat(60))
        val personal = repository.createFolder("Personal")
        expectInvalid { repository.renameFolder(personal.id, "WORK") }
        expectInvalid { repository.renameFolder("../invalid", "name") }
        expectInvalid { repository.renameFolder("missing", "name") }
        repository.renameFolder(folder.id, "  Renamed  ")
        assertEquals(folder.copy(name = "Renamed"), repository.folders().first())
    }

    @Test fun folderMovesAndDeletionAreSingleCatalogChangesWithoutRecordWrites() {
        val repository = repository()
        repository.save(conversation(), "original")
        repository.save(conversation("second"), "other")
        val folder = repository.createFolder("Work")
        val original = recordFiles(repository)
        repository.moveToFolder(listOf("chat", "second", "chat"), folder.id)
        assertEquals(original, recordFiles(repository))
        assertTrue(repository.list().all { it.folderId == folder.id })
        assertEquals(folder.id, repository.load("chat")!!.folderId)
        assertEquals(folder.id, repository.portableConversation("chat").folderId)
        assertTrue(repository.search("answer").all { it.summary.folderId == folder.id })
        repository.moveToFolder(listOf("chat"), null)
        assertNull(repository.load("chat")!!.folderId)
        assertEquals(folder.id, repository.load("second")!!.folderId)
        repository.deleteFolder(folder.id)
        assertTrue(repository.folders().isEmpty())
        assertTrue(repository.list().all { it.folderId == null })
        assertEquals(original, recordFiles(repository))
        assertEquals(2, repository.list().size)
        assertEquals("original", repository.readSource("chat"))
    }

    @Test fun invalidBatchAndFailedCatalogCommitMoveNoConversations() {
        val repository = repository()
        repository.save(conversation())
        repository.save(conversation("second"))
        val folder = repository.createFolder("Work")
        val before = files(repository)
        expectIo { repository.moveToFolder(listOf("chat", "missing"), folder.id) }
        expectInvalid { repository.moveToFolder(listOf("chat", "../escape"), folder.id) }
        expectInvalid { repository.moveToFolder(listOf("chat"), "missing") }
        assertEquals(before, files(repository))
        val obstacle = File(repository.directory, ConversationFolders.FILE_NAME + ".tmp")
        obstacle.mkdir()
        File(obstacle, "occupied").writeText("block atomic write")
        expectIo { repository.moveToFolder(listOf("chat", "second"), folder.id) }
        expectIo { repository.deleteFolder(folder.id) }
        assertTrue(repository.list().all { it.folderId == null })
        assertEquals(listOf(folder), repository.folders())
    }

    @Test fun sourceSizeCountsOriginalUtf8IncludingBase64AndIsStableAcrossMetadataEdits() {
        val repository = repository()
        val image = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val source = "# 中文 🙂\r\n![]($image)\n"
        repository.save(conversation().copy(messages = listOf(ChatMessage("assistant", "picture", images = listOf(image)))), source)
        val expected = source.toByteArray(Charsets.UTF_8).size.toLong()
        assertNotEquals(source.length.toLong(), expected)
        assertEquals(expected, repository.load("chat")!!.sourceSizeBytes)
        val body = body(repository)
        val originalBody = body.readBytes()
        val folder = repository.createFolder("Work")
        repository.moveToFolder(listOf("chat"), folder.id)
        repository.rename("chat", "Renamed")
        repository.setSeen("chat", false)
        repository.setPinned("chat", true)
        repository.updatePosition("chat", 0, 17, 2)
        repository.toggleBookmark("chat", 0)
        repository.updateSummary(repository.list().single().copy(folderId = null, sourceSizeBytes = 1))
        assertEquals(expected, repository.list().single().sourceSizeBytes)
        assertEquals(folder.id, repository.load("chat")!!.folderId)
        assertArrayEquals(originalBody, body.readBytes())
        assertTrue(repository.storageUsage().totalBytes > expected)
    }

    @Test fun legacySizeMigratesOnlyFromAvailableOriginalSourceWithoutBodyRewrite() {
        val repository = repository()
        val original = conversation().copy(hasSource = true, contentHash = "old")
        val legacy = File(repository.directory, "chat.json")
        val oldFields = json.parseToJsonElement(json.encodeToString(original)).jsonObject
            .filterKeys { it != "sourceSizeBytes" && it != "folderId" }
        legacy.writeText(json.encodeToString(JsonObject(oldFields)))
        val before = legacy.readBytes()
        File(repository.directory, "chat.source.md").writeText("原始🙂")
        assertEquals("原始🙂".toByteArray(Charsets.UTF_8).size.toLong(), repository.list().single().sourceSizeBytes)
        assertArrayEquals(before, legacy.readBytes())
        val absent = conversation("absent").copy(contentHash = "old")
        File(repository.directory, "absent.json").writeText(json.encodeToString(absent))
        assertNull(repository.load("absent")!!.sourceSizeBytes)
        val lostSource = conversation("lost").copy(hasSource = true, contentHash = "old")
        File(repository.directory, "lost.json").writeText(json.encodeToString(lostSource))
        assertNull(repository.list().single { it.id == "lost" }.sourceSizeBytes)
        repository.rename("lost", "Still unknown")
        assertNull(repository.load("lost")!!.sourceSizeBytes)
        repository.save(conversation("empty"), "")
        assertEquals(0L, repository.load("empty")!!.sourceSizeBytes)
        repository.save(conversation("new").copy(sourceSizeBytes = 123))
        assertNull(repository.load("new")!!.sourceSizeBytes)
    }

    @Test fun restoreAndReparseKeepCurrentMembershipAndCorrespondingSourceSize() {
        val repository = repository()
        val first = "old 原始🙂"
        val second = "new original with different bytes"
        repository.save(conversation(), first)
        val oldBody = body(repository)
        val folder = repository.createFolder("Work")
        repository.moveToFolder(listOf("chat"), folder.id)
        val stale = repository.load("chat")!!
        repository.save(stale.copy(messages = listOf(ChatMessage("assistant", "reparsed")), folderId = null), second)
        val secondBody = body(repository)
        assertEquals(folder.id, repository.load("chat")!!.folderId)
        val bodies = listOf(oldBody, secondBody).associateWith { it.readBytes() }
        val restored = repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!
        assertEquals(folder.id, restored.folderId)
        assertEquals(first.toByteArray(Charsets.UTF_8).size.toLong(), restored.sourceSizeBytes)
        assertEquals(second.toByteArray(Charsets.UTF_8).size.toLong(),
            repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!.sourceSizeBytes)
        bodies.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        repository.save(repository.load("chat")!!.copy(folderId = null))
        assertEquals(folder.id, repository.load("chat")!!.folderId)
        assertEquals(second.toByteArray(Charsets.UTF_8).size.toLong(), repository.list().single().sourceSizeBytes)
    }

    @Test fun staleSaveSummaryAndHistoryCannotResurrectDeletedFolder() {
        val repository = repository()
        repository.save(conversation(), "first")
        val folder = repository.createFolder("Work")
        repository.moveToFolder(listOf("chat"), folder.id)
        val stale = repository.load("chat")!!
        repository.save(stale.copy(messages = listOf(ChatMessage("assistant", "second"))), "second")
        val preview = repository.historyPreview("chat")!!
        repository.deleteFolder(folder.id)
        try { repository.restorePrevious("chat", preview.token); fail("Expected stale preview") }
        catch (_: ConversationStorageConflictException) { }
        repository.updateSummary(stale.toSummary())
        assertNull(repository.load("chat")!!.folderId)
        repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)
        assertNull(repository.load("chat")!!.folderId)
        repository.save(stale)
        assertNull(repository.load("chat")!!.folderId)
        assertTrue(repository.folders().isEmpty())
        repository.save(conversation("new").copy(folderId = folder.id))
        assertNull(repository.load("new")!!.folderId)
    }

    @Test fun catalogChecksumRecoversAndCleanupProtectsCatalogAndUnknownFiles() {
        val repository = repository()
        (1..4).forEach { repository.save(conversation(text = "revision $it"), "source $it") }
        val folder = repository.createFolder("Work")
        repository.moveToFolder(listOf("chat"), folder.id)
        repository.renameFolder(folder.id, "Renamed") // Both checkpoints now carry the assignment.
        val catalog = File(repository.directory, ConversationFolders.FILE_NAME)
        catalog.writeText(catalog.readText().replace("Renamed", "Tampered"))
        assertEquals(folder.id, repository.load("chat")!!.folderId)
        assertEquals("Work", repository.folders().single().name)
        val keep = File(repository.directory, "unknown.recovery").apply { writeText("keep unknown reference") }
        val before = listOf(catalog, File(catalog.path + ".bak"), keep).associateWith { it.readBytes() }
        val preview = repository.cleanupPreview()
        assertTrue(preview.revisionCount > 0)
        assertEquals(1, preview.unknownFileCount)
        repository.cleanStorage(preview.token)
        before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        val diskBytes = repository.directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(diskBytes, repository.storageUsage().totalBytes)
        catalog.writeText("broken")
        File(catalog.path + ".bak").writeText("broken")
        expectIo { repository.folders() }
        expectIo { repository.list() }
        assertEquals(0, repository.cleanupPreview().revisionCount)
    }

    @Test fun formatTwoBackupIncludesEmptyFoldersAndRemapsNameAndIdCollisions() {
        val original = repository("original")
        original.save(conversation(), "原始🙂")
        val work = original.createFolder("Work")
        original.createFolder("Empty")
        original.moveToFolder(listOf("chat"), work.id)
        val archive = backup(original)
        val nameTarget = repository("nameTarget")
        val existingWork = nameTarget.createFolder("work")
        restore(nameTarget, archive)
        assertEquals(existingWork, nameTarget.folders().first())
        assertEquals("Work (2)", nameTarget.folders().single { it.id == nameTarget.load("chat")!!.folderId }.name)
        val manifest = json.parseToJsonElement(zipEntries(archive).getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
        assertEquals("2", manifest.getValue("format").jsonPrimitive.content)
        val target = repository("target")
        assertEquals(1, restore(target, archive))
        val existing = target.list().single()
        assertEquals(1, restore(target, archive)) // Same incoming IDs and names collide conservatively.
        assertEquals(4, target.folders().size)
        assertEquals(4, target.folders().map { it.id }.distinct().size)
        assertEquals(2, target.list().size)
        assertEquals(work.id, target.load(existing.id)!!.folderId)
        val restored = target.list().single { it.id != existing.id }
        assertNotEquals(work.id, restored.folderId)
        assertEquals("Work (2)", target.folders().single { it.id == restored.folderId }.name)
        assertEquals(2, target.folders().count { folder -> target.list().none { it.folderId == folder.id } })
        assertEquals("原始🙂".toByteArray(Charsets.UTF_8).size.toLong(), restored.sourceSizeBytes)
        val emptyOnly = repository("emptyOnly")
        emptyOnly.createFolder("Only empty")
        val emptyTarget = repository("emptyTarget")
        assertEquals(0, restore(emptyTarget, backup(emptyOnly)))
        assertEquals("Only empty", emptyTarget.folders().single().name)
    }

    @Test fun oldFormatOneWithoutNewFieldsRestoresUnclassifiedAndUnknownSize() {
        val original = repository("original")
        original.save(conversation())
        val entries = zipEntries(backup(original)).toMutableMap()
        val bodyPath = "conversations/chat.json"
        val oldBody = json.parseToJsonElement(entries.getValue(bodyPath).toString(Charsets.UTF_8)).jsonObject
            .filterKeys { it != "folderId" && it != "sourceSizeBytes" }
        val bytes = json.encodeToString(JsonObject(oldBody)).toByteArray(Charsets.UTF_8)
        entries[bodyPath] = bytes
        entries["manifest.json"] = """{"format":1,"records":[{"id":"chat","body":"$bodyPath","bodyHash":"${ConversationFingerprint.sha256(bytes)}"}]}""".toByteArray()
        val target = repository("target")
        target.createFolder("Existing")
        assertEquals(1, restore(target, zip(entries)))
        assertNull(target.list().single().folderId)
        assertNull(target.list().single().sourceSizeBytes)
        assertEquals("Existing", target.folders().single().name)
    }

    @Test fun invalidFolderManifestRejectsBeforeAnyLiveWrites() {
        val original = repository("original")
        original.save(conversation())
        val folder = original.createFolder("Work")
        original.moveToFolder(listOf("chat"), folder.id)
        val entries = zipEntries(backup(original))
        val target = repository("target")
        target.save(conversation("existing"))
        target.createFolder("Existing")
        val before = files(target)
        val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        val invalid = manifest.replace("\"chat\":\"${folder.id}\"", "\"chat\":\"missing\"")
        assertNotEquals(manifest, invalid)
        expectIo { restore(target, zip(entries + ("manifest.json" to invalid.toByteArray()))) }
        assertEquals(before, files(target))
        expectIo { restore(target, zip(entries + ("conversations/chat.json" to "bad".toByteArray()))) }
        assertEquals(before, files(target))
    }

    @Test fun failedCatalogCommitRollsBackRecordsFoldersAndRecoveryCheckpoint() {
        val original = repository("original")
        original.save(conversation("incoming"), "source")
        val folder = original.createFolder("Incoming")
        original.moveToFolder(listOf("incoming"), folder.id)
        val target = repository("target")
        target.save(conversation("existing"))
        target.createFolder("Existing")
        val obstacle = File(target.directory, ConversationFolders.FILE_NAME + ".tmp")
        obstacle.mkdir()
        File(obstacle, "occupied").writeText("injected commit failure")
        val before = files(target)
        expectIo { restore(target, backup(original)) }
        assertEquals(before, files(target))
        assertEquals(listOf("existing"), target.list().map { it.id })
        assertEquals(listOf("Existing"), target.folders().map { it.name })
    }

    @Test fun recordWriteFailureRollsBackWithoutCreatingImportedFolders() {
        val original = repository("original")
        original.save(conversation("first"))
        original.save(conversation("second"))
        val imported = original.createFolder("Incoming")
        original.moveToFolder(listOf("first", "second"), imported.id)
        val root = temporary.newFolder("faulty")
        var injected = false
        val faultyDirectory = object : File(root.path) {
            override fun listFiles(): Array<File>? {
                val snapshot = super.listFiles()
                if (!injected && File(this, "first.meta.json").isFile) {
                    injected = true
                    File(this, "second.meta.json").mkdir()
                    File(this, "second.meta.json/occupied").writeText("injected failure")
                }
                return snapshot
            }
        }
        val target = FileConversationRepository(faultyDirectory)
        target.createFolder("Existing")
        val before = files(target)
        expectIo { restore(target, backup(original)) }
        assertTrue(injected)
        assertEquals(before, files(target))
        assertTrue(target.list().isEmpty())
        assertEquals(listOf("Existing"), target.folders().map { it.name })
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
