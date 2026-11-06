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
import java.net.URI
import java.util.Base64

class ConversationHistoryStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun repository(name: String = "library") = FileConversationRepository(temporary.newFolder(name))
    private fun conversation(text: String, messages: List<ChatMessage> = listOf(ChatMessage("assistant", text))) =
        Conversation(id = "chat", title = text, importedAt = 42L, messages = messages)
    private fun metadata(repository: FileConversationRepository) =
        json.parseToJsonElement(File(repository.directory, "chat.meta.json").readText()).jsonObject
    private fun body(repository: FileConversationRepository) =
        File(repository.directory, "chat.revisions/${metadata(repository)["_revision"]!!.jsonPrimitive.content}.json")
    private fun source(body: File) = File(body.path.removeSuffix(".json") + ".source.md")
    private fun files(repository: FileConversationRepository): Map<String, String> = repository.directory.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(repository.directory).invariantSeparatorsPath to
            Base64.getEncoder().encodeToString(it.readBytes()) }
    private fun corruptPair(file: File) {
        file.writeText("corrupt")
        File(file.path + ".bak").writeText("corrupt")
    }
    private fun expectIo(action: () -> Unit) {
        try { action(); fail("Expected an IOException") } catch (_: IOException) { }
    }
    private fun expectConflict(action: () -> Unit) {
        try { action(); fail("Expected a stale preview conflict") } catch (_: ConversationStorageConflictException) { }
    }
    private fun image(value: Int) = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(value.toByte()))

    @Test fun historyPreviewIsReadOnlyAndNoHistoryIsExplicit() {
        val root = File(temporary.root, "absent")
        val absent = FileConversationRepository(root)
        assertNull(absent.historyPreview("chat"))
        assertEquals(0, absent.cleanupPreview().fileCount)
        assertEquals(0L, absent.storageUsage().totalBytes)
        assertFalse(root.exists())
        val repository = repository()
        repository.save(conversation("first"), "first source")
        assertNull(repository.historyPreview("chat"))
        repository.save(conversation("second").copy(parserVersion = 3), "second source")
        val before = files(repository)
        val preview = repository.historyPreview("chat")!!
        assertEquals("second", preview.title)
        assertEquals(1, preview.messageCount)
        assertEquals(1, preview.parserVersion)
        assertEquals(preview.token, preview.fingerprint)
        repository.cleanupPreview()
        repository.storageUsage()
        assertEquals(before, files(repository))
    }

    @Test fun restorePreservesLatestMetadataMapsUniqueMessagesAndCanBeReversed() {
        val repository = repository()
        val same = ChatMessage("user", "same", blocks = listOf(MessageBlock("text", "same")))
        val oldMessages = listOf(ChatMessage("assistant", "old"), same)
        repository.save(conversation("old title", oldMessages).copy(sourceFormat = "chatbox"), "old source")
        val oldBody = body(repository)
        repository.save(conversation("new title", listOf(same, ChatMessage("assistant", "new"))).copy(parserVersion = 3), "new source")
        val newBody = body(repository)
        repository.rename("chat", "My renamed chat")
        val pinnedAt = repository.setPinned("chat", true)!!.pinnedAt
        repository.updatePosition("chat", 0, 28, 2)
        repository.toggleBookmark("chat", 0)
        repository.toggleBookmark("chat", 1)
        repository.setSeen("chat", false)
        val bodies = listOf(oldBody, newBody, source(oldBody), source(newBody)).associateWith { it.readText() }
        val restored = repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!
        assertEquals(oldMessages, restored.messages)
        assertEquals("My renamed chat", restored.title)
        assertTrue(restored.pinned)
        assertEquals(pinnedAt, restored.pinnedAt)
        assertFalse(restored.seen)
        assertEquals(42L, restored.importedAt)
        assertEquals(listOf(1), restored.bookmarks)
        assertEquals(1, restored.scrollIndex)
        assertEquals(28, restored.scrollOffset)
        assertEquals(2, restored.scrollBlock)
        assertEquals(1, restored.parserVersion)
        assertEquals("chatbox", restored.sourceFormat)
        assertEquals("old source", repository.readSource("chat"))
        val again = repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!
        assertEquals("new", again.messages.last().content)
        assertEquals("new source", repository.readSource("chat"))
        assertEquals(listOf(0), again.bookmarks)
        assertEquals(0, again.scrollIndex)
        assertEquals("My renamed chat", again.title)
        bodies.forEach { (file, text) -> assertEquals(text, file.readText()) }
        assertEquals(1, repository.search("new").size)
        assertTrue(repository.search("old").isEmpty())
    }

    @Test fun ambiguousOrChangedMessagesResetBookmarksAndPosition() {
        val repository = repository()
        val duplicate = ChatMessage("assistant", "duplicate")
        repository.save(conversation("old", listOf(duplicate, duplicate)))
        repository.save(conversation("new", listOf(duplicate, ChatMessage("assistant", "different"))))
        repository.updatePosition("chat", 0, 10, 3)
        repository.toggleBookmark("chat", 0)
        val restored = repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!
        assertTrue(restored.bookmarks.isEmpty())
        assertNull(restored.scrollIndex)
        assertNull(restored.scrollOffset)
        assertEquals(0, restored.scrollBlock)
    }

    @Test fun legacyPreviewDoesNotMigrateAndLegacyPreviousIsRestorable() {
        val repository = repository()
        val legacy = conversation("legacy").copy(contentHash = "old hash", hasSource = true)
        val legacyFile = File(repository.directory, "chat.json")
        legacyFile.writeText(json.encodeToString(legacy))
        File(repository.directory, "chat.source.md").writeText("legacy source")
        val before = files(repository)
        assertNull(repository.historyPreview("chat"))
        repository.cleanupPreview()
        repository.storageUsage()
        assertEquals(before, files(repository))
        repository.save(conversation("new"), "new source")
        val restored = repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!
        assertEquals("legacy", restored.messages.single().content)
        assertTrue(restored.contentHash.startsWith("v2:"))
        assertEquals("legacy source", repository.readSource("chat"))
        assertEquals(before.getValue("chat.json"), files(repository).getValue("chat.json"))
        assertEquals("new", repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!.messages.single().content)
    }

    @Test fun missingHistoryAndCorruptSourceRejectWithoutMutation() {
        for (damage in listOf("body", "source", "asset")) {
            val repository = repository(damage)
            repository.save(conversation("old", listOf(ChatMessage("assistant", "old", images = listOf(image(1))))), "old source")
            val previousBody = body(repository)
            val asset = File(URI(repository.load("chat")!!.messages.single().images.single()))
            repository.save(conversation("new"), "new source")
            val preview = repository.historyPreview("chat")!!
            when (damage) {
                "body" -> { previousBody.delete(); File(previousBody.path + ".bak").delete() }
                "source" -> corruptPair(source(previousBody))
                else -> corruptPair(asset)
            }
            val before = files(repository)
            expectIo { repository.historyPreview("chat") }
            expectConflict { repository.restorePrevious("chat", preview.token) }
            assertEquals(before, files(repository))
            assertEquals("new source", repository.readSource("chat"))
        }
    }

    @Test fun damagedCurrentSourceCannotBeReplacedByRestoreAndDeletedPreviewConflicts() {
        val repository = repository()
        repository.save(conversation("old"), "old source")
        repository.save(conversation("new"), "new source")
        val preview = repository.historyPreview("chat")!!
        corruptPair(source(body(repository)))
        val before = files(repository)
        expectIo { repository.historyPreview("chat") }
        expectConflict { repository.restorePrevious("chat", preview.token) }
        assertEquals(before, files(repository))
        repository.delete("chat")
        val afterDelete = files(repository)
        assertNull(repository.historyPreview("chat"))
        expectConflict { repository.restorePrevious("chat", preview.token) }
        assertEquals(afterDelete, files(repository))
    }

    @Test fun metadataEditsAndConcurrentSaveInvalidateHistoryConfirmationWithoutWrites() {
        val repository = repository()
        repository.save(conversation("old"), "old source")
        repository.save(conversation("new"), "new source")
        val secondInstance = FileConversationRepository(repository.directory)
        val mutations = listOf<() -> Unit>(
            { secondInstance.rename("chat", "renamed"); Unit },
            { secondInstance.toggleBookmark("chat", 0); Unit },
            { secondInstance.updatePosition("chat", 0, 8); Unit },
            { secondInstance.save(conversation("newest"), "newest source") }
        )
        mutations.forEach { mutate ->
            val preview = repository.historyPreview("chat")!!
            mutate()
            val before = files(repository)
            expectConflict { repository.restorePrevious("chat", preview.token) }
            assertEquals(before, files(repository))
        }
    }

    @Test fun previousSourceChecksumSurvivesRotatingMetadataBackup() {
        val repository = repository()
        repository.save(conversation("old"), "old source")
        val oldSource = source(body(repository))
        repository.save(conversation("new"), "new source")
        repository.rename("chat", "renamed")
        repository.toggleBookmark("chat", 0)
        corruptPair(oldSource)
        val before = files(repository)
        expectIo { repository.historyPreview("chat") }
        assertEquals(before, files(repository))
    }

    @Test fun existingSourcefulHistoryUsesMetadataBackupWithoutRewritingIt() {
        val repository = repository()
        repository.save(conversation("old"), "old source")
        repository.save(conversation("new"), "new source")
        // Reproduce the old schema: only the current source hash and revision pointers were persisted.
        val file = File(repository.directory, "chat.meta.json")
        val fields = metadata(repository).filterKeys { it != "_previousSourceHash" && it != "_checksum" }
        file.writeText(json.encodeToString(JsonObject(fields)))
        val before = files(repository)
        assertNotNull(repository.historyPreview("chat"))
        assertEquals(before, files(repository))
        repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)
        assertEquals("old source", repository.readSource("chat"))
    }

    @Test fun oldSchemaMetadataEditsRetainPreviousSourceChecksumBeforeBackupRotation() {
        for (firstEdit in listOf("position", "bookmark")) {
            val repository = repository(firstEdit)
            repository.save(conversation("old"), "old source")
            val previousBody = body(repository)
            repository.save(conversation("new"), "new source")
            val currentBody = body(repository)
            val metadataFile = File(repository.directory, "chat.meta.json")
            val oldFields = metadata(repository).filterKeys { it != "_previousSourceHash" && it != "_checksum" }
            metadataFile.writeText(json.encodeToString(JsonObject(oldFields)))
            val rawFiles = listOf(previousBody, source(previousBody), currentBody, source(currentBody))
                .associateWith { it.readText() }
            val expectedHash = ConversationFingerprint.sha256("old source".toByteArray())
            if (firstEdit == "position") repository.updatePosition("chat", 0, 12)
            else repository.toggleBookmark("chat", 0)
            assertEquals(expectedHash, metadata(repository)["_previousSourceHash"]!!.jsonPrimitive.content)
            // The rollback checkpoint also retains proof if the primary commit becomes unreadable.
            val backup = json.parseToJsonElement(File(metadataFile.path + ".bak").readText()).jsonObject
            assertEquals(expectedHash, backup["_previousSourceHash"]!!.jsonPrimitive.content)
            if (firstEdit == "position") repository.toggleBookmark("chat", 0)
            else repository.updatePosition("chat", 0, 12)
            val beforePreview = files(repository)
            val preview = repository.historyPreview("chat")!!
            assertEquals(beforePreview, files(repository))
            assertEquals("old", repository.restorePrevious("chat", preview.token)!!.messages.single().content)
            assertEquals("old source", repository.readSource("chat"))
            rawFiles.forEach { (file, content) -> assertEquals(content, file.readText()) }
        }
    }

    @Test fun previewUsesAtomicFallbackWithoutRepairingCorruptPrimary() {
        val repository = repository()
        repository.save(conversation("old"), "old source")
        val old = body(repository)
        repository.save(conversation("new"), "new source")
        old.writeText("broken primary")
        source(old).writeText("broken source primary")
        val before = files(repository)
        assertNotNull(repository.historyPreview("chat"))
        repository.cleanupPreview()
        assertEquals(before, files(repository))
        assertEquals("old", repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!.messages.single().content)
        assertEquals("old source", repository.readSource("chat"))
    }

    @Test fun cleanupProtectsCurrentPreviousMetadataBackupAssetsAndUnknownFiles() {
        val repository = repository()
        val revisions = (1..4).map { number ->
            repository.save(conversation("revision $number", listOf(ChatMessage("assistant", "revision $number", images = listOf(image(number))))), "source $number")
            body(repository)
        }
        val unknown = File(repository.directory, "unrelated.notes.json").apply { writeText("unrelated file") }
        val unknownRevision = File(revisions.last().parentFile, "notes.json").apply { writeText("keep me") }
        val unknownAsset = File(repository.directory, "chat.assets/keep.blob").apply { writeText("keep this") }
        val before = files(repository)
        val preview = repository.cleanupPreview()
        assertEquals(1, preview.revisionCount)
        assertEquals(4, preview.fileCount)
        assertEquals(3, preview.protectedRevisionCount)
        assertTrue(preview.unknownFileCount >= 3)
        assertEquals(before, files(repository))
        val sizeBefore = repository.storageUsage().totalBytes
        val result = repository.cleanStorage(preview.token)
        assertEquals(preview.bytes, result.freedBytes)
        assertEquals(sizeBefore - result.freedBytes, repository.storageUsage().totalBytes)
        assertFalse(revisions.first().exists())
        assertFalse(source(revisions.first()).exists())
        revisions.drop(1).forEach { assertTrue(it.isFile); assertTrue(source(it).isFile) }
        assertEquals(8, File(repository.directory, "chat.assets").listFiles()!!.count { it.name != "keep.blob" })
        assertEquals("unrelated file", unknown.readText())
        assertEquals("keep me", unknownRevision.readText())
        assertEquals("keep this", unknownAsset.readText())
        assertEquals("revision 3", repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!.messages.single().content)
        assertEquals("source 3", repository.readSource("chat"))
        assertEquals("revision 4", repository.restorePrevious("chat", repository.historyPreview("chat")!!.token)!!.messages.single().content)
        val archive = ByteArrayOutputStream().also { assertEquals(1, repository.exportBackup(it)) }.toByteArray()
        val target = repository("restored")
        assertEquals(1, target.restoreBackup(ByteArrayInputStream(archive)))
        assertEquals("source 4", target.readSource("chat"))
        assertArrayEquals(byteArrayOf(4), File(URI(target.load("chat")!!.messages.single().images.single())).readBytes())
    }

    @Test fun cleanupStaleAfterSaveRestoreAndBookmarkDeletesNothing() {
        for (mutation in listOf("save", "restore", "bookmark")) {
            val repository = repository(mutation)
            (1..4).forEach { repository.save(conversation("revision $it"), "source $it") }
            val preview = repository.cleanupPreview()
            assertTrue(preview.fileCount > 0)
            val other = FileConversationRepository(repository.directory)
            when (mutation) {
                "save" -> other.save(conversation("new"), "new source")
                "restore" -> other.restorePrevious("chat", other.historyPreview("chat")!!.token)
                else -> other.toggleBookmark("chat", 0)
            }
            val before = files(repository)
            expectConflict { repository.cleanStorage(preview.token) }
            assertEquals(before, files(repository))
        }
    }

    @Test fun corruptActivePointerOrCheckpointSkipsWholeRecordAndLeavesAssets() {
        for (damage in listOf("body", "metadata", "checkpoint")) {
            val repository = repository(damage)
            (1..4).forEach { repository.save(conversation("revision $it"), "source $it") }
            when (damage) {
                "body" -> corruptPair(body(repository))
                "metadata" -> corruptPair(File(repository.directory, "chat.meta.json"))
                else -> File(repository.directory, "chat.meta.json.tmp").writeText("unknown checkpoint")
            }
            val before = files(repository)
            val preview = repository.cleanupPreview()
            assertEquals(0, preview.fileCount)
            assertEquals(1, preview.skippedRecordCount)
            assertTrue(preview.warnings.isNotEmpty())
            assertEquals(0, repository.cleanStorage(preview.token).deletedFileCount)
            assertEquals(before, files(repository))
        }
    }

    @Test fun validCheckpointProtectsOlderRevisionAndUnverifiedPairsSurvive() {
        val repository = repository()
        repository.save(conversation("first"), "first source")
        val first = body(repository)
        val checkpoint = File(repository.directory, "chat.meta.json").readBytes()
        (2..4).forEach { repository.save(conversation("revision $it"), "source $it") }
        val pending = File(repository.directory, "chat.meta.json.recover.tmp").apply { writeBytes(checkpoint) }
        assertEquals(0, repository.cleanupPreview().revisionCount)
        pending.delete()
        // An unrecognized/mismatched immutable source pair is never a deletion candidate.
        File(source(first).path + ".bak").writeText("unexpected backup")
        val before = files(repository)
        val preview = repository.cleanupPreview()
        assertEquals(0, preview.revisionCount)
        repository.cleanStorage(preview.token)
        assertEquals(before, files(repository))
    }

    @Test fun storageCategoriesAreDisjointAndDeletedRecordsAreNotCleaned() {
        val repository = repository()
        (1..4).forEach { repository.save(conversation("revision $it", listOf(ChatMessage("assistant", "text $it", images = listOf(image(it))))), "source $it") }
        File(repository.directory, "notes.txt").writeText("unrecognized")
        val before = files(repository)
        val usage = repository.storageUsage()
        assertTrue(usage.currentBytes > 0)
        assertTrue(usage.sourceBytes > 0)
        assertTrue(usage.assetsBytes > 0)
        assertTrue(usage.historyBytes > 0)
        assertTrue(usage.indexBytes > 0)
        assertTrue(usage.otherBytes > 0)
        assertEquals(repository.directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }, usage.totalBytes)
        assertEquals(before, files(repository))
        File(repository.directory, "chat.deleted").writeText("deleted")
        val deletedFiles = files(repository)
        assertNull(repository.historyPreview("chat"))
        val preview = repository.cleanupPreview()
        assertEquals(0, preview.revisionCount)
        repository.cleanStorage(preview.token)
        assertEquals(deletedFiles, files(repository))
    }
}
