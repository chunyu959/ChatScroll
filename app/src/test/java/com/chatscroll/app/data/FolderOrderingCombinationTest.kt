package com.chatscroll.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

class FolderOrderingCombinationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun folderSearchRenameSizeAndBackupShareOneOrdering() {
        val repository = FileConversationRepository(temporary.newFolder("library"))
        fun add(id: String, name: String, source: String?) {
            repository.save(Conversation(id = id, title = name, importedAt = id.last().digitToInt().toLong(),
                messages = listOf(ChatMessage("assistant", "searchable content"))), source)
        }
        add("c1", "Note 10", "x".repeat(40))
        add("c2", "Note 2", "中文")
        add("c3", "Note 1", null)
        val folder = repository.createFolder("Notes")
        repository.createFolder("Empty")
        repository.moveToFolder(listOf("c1", "c2", "c3"), folder.id)
        fun ordered(mode: String) = repository.list().filter { ConversationOrdering.inFolder(it.folderId, folder.id) }
            .sortedWith(ConversationOrdering.comparator(mode, Locale.US)).map { it.id }
        assertEquals(listOf("c3", "c2", "c1"), ordered("name_asc"))
        assertEquals(listOf("c2", "c1", "c3"), ordered("size_asc"))
        assertEquals(listOf("c1", "c2", "c3"), ordered("size_desc"))
        repository.rename("c1", "Note 0")
        assertEquals(listOf("c1", "c3", "c2"), ordered("name_asc"))
        repository.setPinned("c2", true)
        assertEquals("c2", ordered("name_asc").first())
        val hits = repository.search("searchable").filter { ConversationOrdering.inFolder(it.summary.folderId, folder.id) }
            .sortedWith(Comparator { a, b -> ConversationOrdering.comparator("name_asc", Locale.US).compare(a.summary, b.summary) })
        assertEquals(ordered("name_asc"), hits.map { it.summary.id })
        val before = repository.list().associate { it.id to Pair(it.folderId, it.sourceSizeBytes) }
        val archive = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val restored = FileConversationRepository(temporary.newFolder("restored"))
        assertEquals(3, restored.restoreBackup(ByteArrayInputStream(archive)))
        assertEquals(before, restored.list().associate { it.id to Pair(it.folderId, it.sourceSizeBytes) })
        assertEquals(2, restored.folders().size)
        assertEquals(ordered("name_asc"), restored.list().sortedWith(ConversationOrdering.comparator("name_asc", Locale.US)).map { it.id })
        restored.deleteFolder(folder.id)
        assertTrue(restored.list().all { ConversationOrdering.inFolder(it.folderId, "unfiled") })
        assertEquals(3, restored.list().size)
        assertEquals("中文", restored.readSource("c2"))
    }
}
