package com.chatscroll.app

import com.chatscroll.app.data.FileConversationRepository
import com.chatscroll.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReaderFeatureCombinationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun reparseRestoreCopySearchPureModeCleanupAndBackupWorkTogether() {
        val repository = FileConversationRepository(temporary.newFolder("library"))
        fun source(version: Int) = listOf(
            "# Fixture", "", "**User**:", "", "Stable question", "", "---", "", "**Assistant**:", "",
            "> hidden reasoning", "", "**Tool**: `synthetic`", "Output:", "```text", "hidden tool", "```", "",
            "Visible answer $version", "", "| Model | Input | Output | Cost |", "| --- | --- | --- | --- |",
            "| Sample | Text | Image | Local |"
        ).joinToString("\n")
        for (version in 1..5) {
            repository.save(conversationFromSource(source(version), "Fixture", "rikkahub").copy(id = "combined"), source(version))
        }
        repository.rename("combined", "Kept title")
        repository.setPinned("combined", true)
        repository.toggleBookmark("combined", 0)
        repository.updatePosition("combined", 0, 9, 0)
        val preview = repository.historyPreview("combined")!!
        val restored = repository.restorePrevious("combined", preview.token)!!
        assertEquals("Kept title", restored.title)
        assertTrue(restored.pinned)
        assertEquals(listOf(0), restored.bookmarks)
        assertEquals(9, restored.scrollOffset)
        assertEquals(source(4), repository.readSource("combined"))
        val message = restored.messages[1]
        assertTrue(readerVisibleMessageText(message).contains("Visible answer 4"))
        assertFalse(readerVisibleMessageText(message).contains("hidden"))
        assertTrue(readerMessageText(message).contains("hidden tool"))
        val rows = readerRows(restored.messages)
        val normal = readerVisibleRows(rows, emptySet())
        val pure = readerVisibleRows(rows, emptySet(), pureReading = true)
        assertEquals(normal.map { it.key }, pure.map { it.key })
        assertEquals(readerSearchMatches(normal, "Visible answer 4"), readerSearchMatches(pure, "Visible answer 4"))
        assertEquals(1, repository.search("Visible answer 4").single().messageIndex)
        val table = rows.first { readerTableColumnCount(it.text) == 4 }
        assertEquals(560f, readerTableWidthDp(4, 320f), 0f)
        assertEquals(table.text, readerMarkdownSections(table.text).joinToString("") { it.markdown })
        val cleanup = repository.cleanupPreview()
        val before = repository.storageUsage().totalBytes
        val result = repository.cleanStorage(cleanup.token)
        assertEquals(before - result.freedBytes, repository.storageUsage().totalBytes)
        // Cleanup must retain the undo target (version 5), not just the current version 4.
        val reverse = repository.historyPreview("combined")!!
        assertTrue(repository.restorePrevious("combined", reverse.token)!!.messages[1].content.contains("Visible answer 5"))
        val archive = ByteArrayOutputStream()
        assertEquals(1, repository.exportBackup(archive))
        val destination = FileConversationRepository(temporary.newFolder("restored"))
        assertEquals(1, destination.restoreBackup(ByteArrayInputStream(archive.toByteArray())))
        assertEquals(source(5), destination.readSource("combined"))
        assertEquals(listOf(0), destination.load("combined")!!.bookmarks)
        assertTrue(destination.search("Visible answer 5").isNotEmpty())
    }
}
