package com.chatscroll.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ConversationOrderingTest {
    private fun summary(id: String, title: String = id, time: Long = 0, size: Long? = null,
        pinned: Boolean = false, pinnedAt: Long? = null, folderId: String? = null) = ConversationSummary(
        id = id, title = title, importedAt = time, sourceSizeBytes = size,
        pinned = pinned, pinnedAt = pinnedAt, folderId = folderId
    )

    @Test fun namesUseNaturalNumbersWithoutOverflowAndIgnoreCase() {
        val names = listOf("Chapter 10", "chapter 2", "Chapter 1", "Chapter 01", "Chapter 0002", "Chapter 9999999999999999999999", "Chapter 10000000000000000000000")
        assertEquals(listOf("Chapter 1", "Chapter 01", "chapter 2", "Chapter 0002", "Chapter 10", "Chapter 9999999999999999999999", "Chapter 10000000000000000000000"),
            names.sortedWith(ConversationOrdering.naturalNames(Locale.US)))
        assertEquals(0, ConversationOrdering.naturalNames(Locale.US).compare("Report", "report"))
    }

    @Test fun nameSortUsesCurrentDisplayTitleInBothDirections() {
        val rows = listOf(summary("a", "part10"), summary("b", "part2"), summary("c", "part1"))
        assertEquals(listOf("c", "b", "a"), rows.sortedWith(ConversationOrdering.comparator("name_asc", Locale.US)).map { it.id })
        assertEquals(listOf("a", "b", "c"), rows.sortedWith(ConversationOrdering.comparator("name_desc", Locale.US)).map { it.id })
        val renamed = rows.map { if (it.id == "a") it.copy(title = "part0") else it }
        assertEquals("a", renamed.sortedWith(ConversationOrdering.comparator("name_asc", Locale.US)).first().id)
    }

    @Test fun sizesKeepUnknownLastInEitherDirection() {
        val rows = listOf(summary("unknown"), summary("big", size = 1_000_000), summary("zero", size = 0), summary("small", size = 20))
        assertEquals(listOf("zero", "small", "big", "unknown"), rows.sortedWith(ConversationOrdering.comparator("size_asc")).map { it.id })
        assertEquals(listOf("big", "small", "zero", "unknown"), rows.sortedWith(ConversationOrdering.comparator("size_desc")).map { it.id })
    }

    @Test fun pinningStaysFirstAndTimeDirectionIsRememberable() {
        val rows = listOf(summary("old", time = 1), summary("new", time = 9),
            summary("pin1", time = 0, pinned = true, pinnedAt = 10), summary("pin2", pinned = true, pinnedAt = 20))
        for (mode in ConversationOrdering.orders) {
            assertEquals(listOf("pin2", "pin1"), rows.sortedWith(ConversationOrdering.comparator(mode)).take(2).map { it.id })
        }
        assertEquals(listOf("old", "new"), rows.filterNot { it.pinned }.sortedWith(ConversationOrdering.comparator("time_asc")).map { it.id })
        assertEquals("time_desc", ConversationOrdering.normalized("invalid"))
        assertEquals("size_asc", ConversationOrdering.normalized("size_asc"))
    }

    @Test fun folderScopesDoNotOverlapExceptAll() {
        assertTrue(ConversationOrdering.inFolder(null, "all"))
        assertTrue(ConversationOrdering.inFolder("f1", "all"))
        assertTrue(ConversationOrdering.inFolder(null, "unfiled"))
        assertFalse(ConversationOrdering.inFolder("f1", "unfiled"))
        assertTrue(ConversationOrdering.inFolder("f1", "f1"))
        assertFalse(ConversationOrdering.inFolder(null, "f1"))
        assertFalse(ConversationOrdering.inFolder("f2", "f1"))
    }

    @Test fun comparatorsAreAntisymmetricTransitiveAndDeterministic() {
        val names = listOf("a", "A", "a1", "a01", "a2", "a10", "a2b1", "a2b10", "a-1", "a_1", "1", "01", "9", "10", "中文2", "中文10", "")
        val rows = names.mapIndexed { i, title -> summary("$i", title, i.toLong(), if (i % 3 == 0) null else i.toLong(), i % 4 == 0, if (i % 8 == 0) null else 0L) }
        for (mode in ConversationOrdering.orders) {
            val comparator = ConversationOrdering.comparator(mode, Locale.US)
            for (a in rows) for (b in rows) {
                assertEquals(-comparator.compare(b, a).sign(), comparator.compare(a, b).sign())
                for (c in rows) if (comparator.compare(a, b) <= 0 && comparator.compare(b, c) <= 0) {
                    assertTrue("$mode comparator order: ${a.title}/${a.pinnedAt} <= ${b.title}/${b.pinnedAt} <= ${c.title}/${c.pinnedAt}", comparator.compare(a, c) <= 0)
                }
            }
            assertEquals(rows.sortedWith(comparator), rows.reversed().sortedWith(comparator))
        }
    }

    private fun Int.sign(): Int = compareTo(0)
}
