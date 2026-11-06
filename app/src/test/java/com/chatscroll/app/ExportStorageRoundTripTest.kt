package com.chatscroll.app

import com.chatscroll.app.data.FileConversationRepository
import com.chatscroll.app.ui.conversationFromSource
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Private corpus reaches persistence and portable backup; assertions never print its text. */
class ExportStorageRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun fiveExportsSurvivePersistenceSearchAndBackupRestore() {
        val configured = System.getProperty("chatscroll.corpusDir")
        assumeTrue("Private corpus disabled: configure chatscrollCorpusDir", configured != null)
        val names = listOf("CLAUDE（1）.md", "claude（2）.md", "claude（3）.md", "claude（4）.md", "claude（5）.md")
        val repository = FileConversationRepository(temporary.newFolder("original"))
        val expected = names.mapIndexed { index, name ->
            val file = File(configured!!, name)
            assertTrue("fixture ${index + 1} missing", file.isFile)
            val source = file.readText(Charsets.UTF_8)
            val conversation = conversationFromSource(source, "fixture-${index + 1}", "auto").copy(
                id = "fixture-${index + 1}", title = "fixture-${index + 1}", importedAt = index.toLong()
            )
            repository.save(conversation, source)
            val loaded = repository.load(conversation.id)!!
            assertTrue("fixture ${index + 1} messages changed", loaded.messages.map { it.copy(images = emptyList()) } ==
                conversation.messages.map { it.copy(images = emptyList()) })
            assertTrue("fixture ${index + 1} source changed", source == repository.readSource(conversation.id))
            assertTrue("fixture ${index + 1} hash mismatch", loaded.contentHash == conversation.contentHash)
            assertTrue("fixture ${index + 1} dedup mismatch", repository.findDuplicate(conversation.contentHash)?.id == conversation.id)
            assertTrue("fixture ${index + 1} title search", repository.search(conversation.title).any { it.summary.id == conversation.id })
            repository.toggleBookmark(conversation.id, 0)
            repository.updatePosition(conversation.id, 1, 15, 0)
            loaded to source
        }
        val backup = temporary.newFile("portable.zip")
        backup.outputStream().use { assertTrue("backup count", repository.exportBackup(it) == 5) }
        val restored = FileConversationRepository(temporary.newFolder("restored"))
        backup.inputStream().use { assertTrue("restore count", restored.restoreBackup(it) == 5) }
        expected.forEachIndexed { index, (original, source) ->
            val actual = restored.load(original.id)!!
            assertTrue("fixture ${index + 1} restored hash", actual.contentHash == original.contentHash)
            assertTrue("fixture ${index + 1} restored text", actual.messages.map { it.copy(images = emptyList()) } ==
                original.messages.map { it.copy(images = emptyList()) })
            assertTrue("fixture ${index + 1} restored source", restored.readSource(original.id) == source)
            assertTrue("fixture ${index + 1} restored bookmark", actual.bookmarks == listOf(0))
            assertTrue("fixture ${index + 1} restored position", actual.scrollIndex == 1 && actual.scrollOffset == 15)
        }
        assertTrue("recovery warnings", repository.issues().isEmpty() && restored.issues().isEmpty())
    }
}
