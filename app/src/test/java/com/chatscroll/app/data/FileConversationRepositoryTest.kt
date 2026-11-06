package com.chatscroll.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileConversationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun repository(name: String = "library") = FileConversationRepository(temporary.newFolder(name))
    private fun conversation(id: String = "chat", text: String = "A searchable answer") = Conversation(
        id = id, title = "Original title", importedAt = 42L, seen = false,
        messages = listOf(ChatMessage("user", "Question"), ChatMessage("assistant", text))
    )
    private fun bodyFile(repository: FileConversationRepository, id: String): File {
        val metadata = json.parseToJsonElement(File(repository.directory, "$id.meta.json").readText()).jsonObject
        val revision = metadata["_revision"]!!.jsonPrimitive.content
        return File(repository.directory, "$id.revisions/$revision.json")
    }
    private fun assertIoFailure(action: () -> Unit) {
        try { action(); fail("Expected an IOException") } catch (_: IOException) { }
    }
    private fun image(vararg bytes: Int) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.map(Int::toByte).toByteArray())

    @Test fun oldJsonMigratesHashWithoutRewritingBodyAndMetadataWins() {
        val repository = repository()
        val old = conversation().copy(contentHash = "old-hash", pinned = false)
        val body = File(repository.directory, "chat.json")
        body.writeText(json.encodeToString(old))
        val originalBytes = body.readBytes()
        File(repository.directory, "chat.meta.json").writeText(json.encodeToString(old.toSummary().copy(
            title = "Renamed", pinned = true, pinnedAt = 123L, seen = true, scrollIndex = 1, scrollOffset = 7)))
        val summary = repository.list().single()
        assertTrue(summary.contentHash.startsWith("v2:"))
        assertEquals("Renamed", summary.title)
        assertArrayEquals(originalBytes, body.readBytes())
        val loaded = repository.load("chat")!!
        assertEquals("Renamed", loaded.title)
        assertTrue(loaded.pinned)
        assertTrue(loaded.seen)
        assertEquals(1, loaded.scrollIndex)
        assertEquals(7, loaded.scrollOffset)
        assertEquals(summary.contentHash, loaded.contentHash)
        assertEquals("chat", repository.findDuplicate(ConversationStore.contentHash(old.messages))?.id)
    }

    @Test fun atomicMetadataEditsPreservePositionBookmarksAndBody() {
        val repository = repository()
        repository.save(conversation())
        val body = bodyFile(repository, "chat")
        val bytes = body.readBytes()
        repository.updatePosition("chat", 1, 17, 3)
        repository.toggleBookmark("chat", 1)
        val renamed = repository.rename("chat", "Atomic rename")!!
        assertEquals(17, renamed.scrollOffset)
        assertEquals(listOf(1), renamed.bookmarks)
        val pinned = repository.setPinned("chat", true)!!
        assertNotNull(pinned.pinnedAt)
        assertEquals(pinned.pinnedAt, repository.setPinned("chat", true)!!.pinnedAt)
        assertFalse(repository.setSeen("chat", false)!!.seen)
        assertTrue(repository.setSeen("chat")!!.seen)
        val updated = repository.load("chat")!!
        assertEquals("Atomic rename", updated.title)
        assertEquals(1, updated.scrollIndex)
        assertEquals(17, updated.scrollOffset)
        assertEquals(3, updated.scrollBlock)
        assertEquals(listOf(1), updated.bookmarks)
        assertArrayEquals(bytes, body.readBytes())
        assertNull(repository.setPinned("chat", false)!!.pinnedAt)
        assertNull(repository.setSeen("missing"))
        assertNull(repository.rename("missing", "absent"))
        repository.delete("chat")
        assertNull(repository.setPinned("chat", true))
    }

    @Test fun corruptMetadataDoesNotHideHealthyRecordsAndRepairClearsIssues() {
        val repository = repository()
        repository.save(conversation("healthy"))
        repository.save(conversation("broken"))
        val meta = File(repository.directory, "broken.meta.json")
        val backup = File(meta.path + ".bak")
        val good = meta.readBytes()
        meta.writeText("bad")
        backup.writeText("bad")
        assertEquals(listOf("healthy"), repository.list().map { it.id })
        assertEquals(listOf("broken"), FileConversationRepository(repository.directory).issues())
        assertEquals("healthy", repository.findDuplicate(ConversationStore.contentHash(conversation().messages))!!.id)
        assertEquals(listOf("healthy"), repository.search("answer").map { it.summary.id })
        assertIoFailure { repository.exportBackup(ByteArrayOutputStream()) }
        backup.writeBytes(good)
        assertTrue(repository.issues().isEmpty())
        assertEquals(setOf("healthy", "broken"), repository.list().map { it.id }.toSet())
    }

    @Test fun failedSearchRecordIsReportedAcrossInstancesUntilRepairedOrDeleted() {
        val repository = repository()
        repository.save(conversation("healthy"))
        repository.save(conversation("broken"))
        val body = bodyFile(repository, "broken")
        val goodBody = body.readBytes()
        listOf(body, File(body.path + ".bak"), File(repository.directory, "broken.search.json"),
            File(repository.directory, "broken.search.json.bak")).forEach { it.writeText("broken") }
        assertEquals(listOf("healthy"), repository.search("answer").map { it.summary.id })
        assertEquals(listOf("broken"), FileConversationRepository(repository.directory).issues())
        File(body.path + ".bak").writeBytes(goodBody)
        assertTrue(repository.issues().isEmpty())
        assertEquals(2, repository.search("answer").size)
        File(repository.directory, "broken.meta.json").writeText("bad")
        File(repository.directory, "broken.meta.json.bak").writeText("bad")
        assertEquals(listOf("broken"), repository.issues())
        repository.delete("broken")
        assertTrue(repository.issues().isEmpty())
    }

    @Test fun metadataOnlyUpdatesRecoverWithoutBodyRewrite() {
        val repository = repository()
        repository.save(conversation())
        val body = bodyFile(repository, "chat")
        val original = body.readBytes()
        repository.updateSummary(repository.list().single().copy(title = "A rename", pinned = true, pinnedAt = 99))
        repository.updatePosition("chat", 1, 8, 2)
        repository.toggleBookmark("chat", 1)
        val loaded = repository.load("chat")!!
        assertEquals("A rename", loaded.title)
        assertTrue(loaded.pinned)
        assertEquals(listOf(1), loaded.bookmarks)
        assertEquals(2, loaded.scrollBlock)
        assertEquals(8, loaded.scrollOffset)
        assertArrayEquals(original, body.readBytes())
        File(repository.directory, "chat.meta.json").writeText("broken metadata")
        // .bak has the prior successful metadata update, including the position and rename.
        val recovered = repository.load("chat")!!
        assertEquals("A rename", recovered.title)
        assertEquals(1, recovered.scrollIndex)
        assertEquals(2, recovered.scrollBlock)
        assertArrayEquals(original, body.readBytes())
    }

    @Test fun atomicFilesRecoverBodyAndIgnoreUncommittedTemporary() {
        val repository = repository()
        repository.save(conversation())
        val body = bodyFile(repository, "chat")
        File(body.path + ".tmp").writeText("unfinished replacement")
        body.writeText("corrupted")
        assertEquals("A searchable answer", repository.load("chat")!!.messages[1].content)
        assertTrue(body.readText().contains("A searchable answer"))
        body.delete()
        assertNotNull(repository.load("chat"))
        body.writeText("broken")
        File(body.path + ".bak").writeText("also broken")
        assertIoFailure { repository.load("chat") }
    }

    @Test fun validJsonCorruptionIsDetectedAndRecoveredByChecksums() {
        val repository = repository()
        repository.save(conversation())
        val body = bodyFile(repository, "chat")
        body.writeText(body.readText().replace("A searchable answer", "Tampered answer"))
        assertEquals("A searchable answer", repository.load("chat")!!.messages.last().content)
        val meta = File(repository.directory, "chat.meta.json")
        meta.writeText(meta.readText().replace("Original title", "Tampered title"))
        assertEquals("Original title", repository.load("chat")!!.title)
    }

    @Test fun imagesAreSeparateAndHashIncludesEveryContentField() {
        val repository = repository()
        val message = ChatMessage("assistant", "reply", thinking = "thought", speaker = "Writer", time = "now",
            blocks = listOf(MessageBlock("text", "reply"), MessageBlock("tool", "result", "tool")), images = listOf(image(1, 2, 3)))
        val hash = ConversationStore.contentHash(listOf(message))
        val variants = listOf(
            message.copy(role = "user"), message.copy(content = "changed"), message.copy(thinking = null),
            message.copy(speaker = "Other"), message.copy(time = "later"),
            message.copy(blocks = message.blocks.reversed()),
            message.copy(blocks = listOf(MessageBlock("tool", "result", "different"))),
            message.copy(images = listOf(image(1, 2, 4)))
        )
        variants.forEach { assertNotEquals(hash, ConversationStore.contentHash(listOf(it))) }
        val original = conversation().copy(messages = listOf(message))
        repository.save(original)
        val body = bodyFile(repository, "chat").readText()
        assertFalse(body.contains("data:image"))
        assertTrue(body.contains("asset:"))
        val loaded = repository.load("chat")!!
        assertTrue(loaded.messages.single().images.single().startsWith("file:"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(URI(loaded.messages.single().images.single())).readBytes())
        assertEquals(hash, loaded.contentHash)
        assertEquals(hash, ConversationStore.contentHash(loaded.messages))
        repository.save(loaded.copy(title = "Title excluded"))
        assertEquals(hash, repository.load("chat")!!.contentHash)
        assertEquals("chat", repository.findDuplicate(hash)?.id)
        assertNull(repository.findDuplicate(ConversationStore.contentHash(listOf(variants.last()))))
    }

    @Test fun sourceIsSeparateRecoverableAndPreviousReparseRevisionRemains() {
        val repository = repository()
        repository.save(conversation(), "# Original private Markdown")
        val firstBody = bodyFile(repository, "chat")
        val firstSource = File(firstBody.path.removeSuffix(".json") + ".source.md")
        assertFalse(firstBody.readText().contains("Original private Markdown"))
        assertTrue(repository.load("chat")!!.hasSource)
        assertEquals("# Original private Markdown", repository.readSource("chat"))
        firstSource.writeText("tampered")
        assertEquals("# Original private Markdown", repository.readSource("chat"))
        repository.save(repository.load("chat")!!.copy(messages = listOf(ChatMessage("assistant", "reparsed")), parserVersion = 2), "# Second source")
        assertTrue(firstBody.isFile)
        assertEquals("# Original private Markdown", firstSource.readText())
        assertEquals("# Second source", repository.readSource("chat"))
        val currentSource = File(bodyFile(repository, "chat").path.removeSuffix(".json") + ".source.md")
        currentSource.delete()
        File(currentSource.path + ".bak").delete()
        assertIoFailure { repository.readSource("chat") }
        assertIoFailure { repository.save(repository.load("chat")!!) }
    }

    @Test fun deletionTombstonePreventsInterruptedCleanupResurrection() {
        val repository = repository()
        repository.save(conversation(), "source")
        File(repository.directory, "chat.deleted").writeText("deleted")
        assertNull(repository.load("chat"))
        assertTrue(repository.list().isEmpty())
        assertTrue(repository.search("answer").isEmpty())
        assertIoFailure { repository.save(conversation()) }
        repository.delete("chat")
        assertFalse(File(repository.directory, "chat.meta.json").exists())
        assertFalse(File(repository.directory, "chat.revisions").exists())
        assertTrue(File(repository.directory, "chat.deleted").exists())
        assertNull(FileConversationRepository(repository.directory).load("chat"))
    }

    @Test fun deleteFailureIsReported() {
        val repository = repository()
        repository.save(conversation())
        // A non-file at the commit destination causes a deterministic filesystem failure on every host.
        File(repository.directory, "chat.deleted").mkdir()
        File(repository.directory, "chat.deleted/occupied").writeText("cannot replace directory")
        assertIoFailure { repository.delete("chat") }
    }

    @Test fun searchUsesLightweightIndexAndReportsMessagePosition() {
        val repository = repository()
        repository.save(conversation(text = "Needle in answer").copy(messages = listOf(
            ChatMessage("user", "hello", thinking = "hidden-secret"),
            ChatMessage("assistant", "Needle in answer", blocks = listOf(MessageBlock("text", "additional visible text"), MessageBlock("tool", "private-tool")))
        )))
        val body = bodyFile(repository, "chat")
        body.writeText("body intentionally unavailable")
        File(body.path + ".bak").writeText("also unavailable")
        assertEquals(1, repository.search("needle").single().messageIndex)
        assertEquals(1, repository.search("additional").single().messageIndex)
        assertTrue(repository.search("hidden-secret").isEmpty())
        assertTrue(repository.search("private-tool").isEmpty())
        assertNull(repository.search("Original title").single().messageIndex)
        assertTrue(repository.search("   ").isEmpty())
        try {
            repository.search("needle") { throw CancellationException("cancel") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun corruptDerivedIndexRebuildsFromBody() {
        val repository = repository()
        repository.save(conversation())
        File(repository.directory, "chat.search.json").writeText("bad")
        File(repository.directory, "chat.search.json.bak").writeText("bad")
        assertEquals(1, repository.search("answer").single().messageIndex)
    }

    @Test fun backupRoundTripMergesCollisionsAndIsPortable() {
        val original = repository("original")
        original.save(conversation().copy(sourceFormat = "chatbox", parserVersion = 3,
            messages = listOf(ChatMessage("assistant", "picture", images = listOf(image(4, 5, 6))))), "# source")
        original.updatePosition("chat", 0, 33, 2)
        original.toggleBookmark("chat", 0)
        original.updateSummary(original.list().single().copy(title = "Renamed", pinned = true, pinnedAt = 123L))
        val bytes = ByteArrayOutputStream().also { assertEquals(1, original.exportBackup(it)) }.toByteArray()
        val zipEntries = zipEntries(bytes)
        val body = zipEntries.getValue("conversations/chat.json").toString(Charsets.UTF_8)
        assertFalse(body.contains("file:"))
        assertFalse(body.contains(original.directory.absolutePath))
        val target = repository("target")
        target.save(conversation(text = "existing record"))
        assertEquals(1, target.restoreBackup(ByteArrayInputStream(bytes)))
        assertEquals("existing record", target.load("chat")!!.messages.last().content)
        val imported = target.list().single { it.id != "chat" }
        assertEquals("Renamed", imported.title)
        assertEquals(listOf(0), imported.bookmarks)
        assertEquals(33, imported.scrollOffset)
        assertEquals(2, imported.scrollBlock)
        assertEquals("chatbox", imported.sourceFormat)
        assertEquals(3, imported.parserVersion)
        assertTrue(imported.pinned)
        assertEquals("# source", target.readSource(imported.id))
        assertArrayEquals(byteArrayOf(4, 5, 6), File(URI(target.load(imported.id)!!.messages.single().images.single())).readBytes())
    }

    @Test fun corruptArchiveRollsBackWholeImportAndLeavesExistingData() {
        val source = repository("source")
        source.save(conversation("first"))
        source.save(conversation("second"))
        val bytes = ByteArrayOutputStream().also { source.exportBackup(it) }.toByteArray()
        val entries = zipEntries(bytes).toMutableMap()
        entries["conversations/second.json"] = "corrupt second record".toByteArray()
        val target = repository("target")
        target.save(conversation("existing"))
        val before = target.list()
        assertIoFailure { target.restoreBackup(ByteArrayInputStream(zip(entries))) }
        assertEquals(before, target.list())
        assertFalse(target.directory.listFiles()!!.any { it.name.startsWith("first.") || it.name.startsWith("second.") || it.name.startsWith(".restore-") })
        assertIoFailure { target.restoreBackup(ByteArrayInputStream(bytes.copyOf(bytes.size - 30))) }
        assertEquals(before, target.list())
    }

    @Test fun restoreRejectsTraversalUnknownEntriesAndCorruptAssetsBeforeMutation() {
        val source = repository("source")
        source.save(conversation().copy(messages = listOf(ChatMessage("user", "image", images = listOf(image(9, 8, 7))))))
        val bytes = ByteArrayOutputStream().also { source.exportBackup(it) }.toByteArray()
        val entries = zipEntries(bytes)
        val target = repository("target")
        for (path in listOf("../outside", "/absolute", "C:/host", "unknown.txt", "assets/../escape")) {
            assertIoFailure { target.restoreBackup(ByteArrayInputStream(zip(entries + (path to "bad".toByteArray())))) }
            assertTrue(target.list().isEmpty())
        }
        val asset = entries.keys.single { it.startsWith("assets/") }
        assertIoFailure { target.restoreBackup(ByteArrayInputStream(zip(entries + (asset to byteArrayOf(0))))) }
        assertTrue(target.list().isEmpty())
    }

    @Test fun restoreWriteFailureRemovesAlreadyCommittedNewRecords() {
        val source = repository("source")
        source.save(conversation("first"))
        source.save(conversation("second"))
        val bytes = ByteArrayOutputStream().also { source.exportBackup(it) }.toByteArray()
        val root = temporary.newFolder("target")
        var injected = false
        // A File-based fault injection keeps production storage free of test hooks. At the second
        // allocation, obstruct its metadata destination after the directory snapshot was captured.
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
        assertIoFailure { target.restoreBackup(ByteArrayInputStream(bytes)) }
        assertTrue(injected)
        assertTrue(target.list().isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun refusesImportedLocalFilesAndUnsafeIds() {
        val repository = repository()
        val private = temporary.newFile("not-an-image").apply { writeText("private") }
        assertIoFailure { repository.save(conversation().copy(messages = listOf(ChatMessage("user", "x", images = listOf(private.toURI().toString()))))) }
        try { repository.load("../escape"); fail("Expected invalid ID") } catch (_: IllegalArgumentException) { }
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
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()
}
