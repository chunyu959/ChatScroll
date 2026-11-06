package com.chatscroll.app

import com.chatscroll.app.data.ChatMessage
import com.chatscroll.app.data.MessageBlock
import com.chatscroll.app.ui.ImageMarkerSegment
import com.chatscroll.app.ui.ReaderBlockKey
import com.chatscroll.app.ui.ReaderCodeBlock
import com.chatscroll.app.ui.ReaderPosition
import com.chatscroll.app.ui.ReaderRowKind
import com.chatscroll.app.ui.readerCodeBlocks
import com.chatscroll.app.ui.readerDirectoryIndices
import com.chatscroll.app.ui.readerImageSegments
import com.chatscroll.app.ui.readerMarkdownBlocks
import com.chatscroll.app.ui.readerMessageText
import com.chatscroll.app.ui.readerPosition
import com.chatscroll.app.ui.readerRowIndex
import com.chatscroll.app.ui.readerRows
import com.chatscroll.app.ui.readerSearchMatches
import com.chatscroll.app.ui.readerVisibleRows
import com.chatscroll.app.ui.readerVisibleMessageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRowsTest {
    @Test
    fun splitsLongRepliesAtParagraphsWithoutSplittingFencesOrTables() {
        val text = "First paragraph.\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n" +
            "| Name | Value |\n| --- | --- |\n| A | B |\n\nLast paragraph."
        val blocks = readerMarkdownBlocks(text)
        assertEquals(4, blocks.size)
        assertTrue(blocks[1].contains("val a = 1\n\nval b = 2"))
        assertTrue(blocks[2].contains("| A | B |"))
    }

    @Test
    fun preservesLooseListsIndentedCodeMathAndReferenceDefinitions() {
        val list = "- first\n\n  continuation\n\n- second\n\nAfter"
        assertEquals(2, readerMarkdownBlocks(list).size)
        val code = "    first\n\n    second\n\nAfter"
        assertEquals(2, readerMarkdownBlocks(code).size)
        val math = "Before\n\n$$\nx\n\ny\n$$\n\nAfter"
        assertEquals(3, readerMarkdownBlocks(math).size)
        val reference = "[link][ref]\n\nOther paragraph\n\n[ref]: https://example.org"
        assertEquals(listOf(reference), readerMarkdownBlocks(reference))
        val html = "<details>\n\n<summary>More</summary>\n\nBody\n\n</details>"
        assertEquals(listOf(html), readerMarkdownBlocks(html))
    }

    @Test
    fun preservesUnclosedAndLongerFences() {
        assertEquals(2, readerMarkdownBlocks("Before\n\n```\nfirst\n\nsecond").size)
        val nested = "````text\n```\n\ninside\n```\n````\n\nAfter"
        assertEquals(2, readerMarkdownBlocks(nested).size)
    }

    @Test
    fun keepsInterleavedOrderAndStableCoordinatesWhenFoldsChange() {
        val rows = readerRows(listOf(ChatMessage("assistant", "first\n\nlast", blocks = listOf(
            MessageBlock("text", "first"),
            MessageBlock("tool", "hidden\n\nsecond tool paragraph", "lookup"),
            MessageBlock("text", "last")
        )), ChatMessage("user", "Next message")))
        assertEquals(listOf("first", "hidden\n", "second tool paragraph", "last", "Next message"),
            rows.filter { it.kind == ReaderRowKind.TEXT }.map { it.text })
        val fold = rows.first { it.kind == ReaderRowKind.FOLD }.key
        val closed = readerVisibleRows(rows, emptySet())
        val open = readerVisibleRows(rows, setOf(fold))
        val lastKey = rows.first { it.text == "last" }.key
        assertEquals(lastKey, closed[readerRowIndex(closed, 0, lastKey.blockIndex)].key)
        assertEquals(lastKey, open[readerRowIndex(open, 0, lastKey.blockIndex)].key)
        assertEquals(ReaderPosition(0, 27, lastKey.blockIndex), readerPosition(closed,
            readerRowIndex(closed, 0, lastKey.blockIndex), 27))
        // Legacy positions point to the message header, not a flattened global row.
        assertEquals(ReaderBlockKey(1, 0), closed[readerRowIndex(closed, 1)].key)
    }

    @Test
    fun collapsedChildRestoreFallsBackToItsFoldAndInvalidPositionsClamp() {
        val rows = readerRows(listOf(ChatMessage("assistant", "reply", thinking = "thought")))
        val hidden = rows.first { it.parentFold != null }
        val closed = readerVisibleRows(rows, emptySet())
        assertEquals(hidden.parentFold, closed[readerRowIndex(closed, 0, hidden.key.blockIndex)].key)
        assertEquals(0, readerRowIndex(closed, -4, -7))
        assertEquals(closed.lastIndex, readerRowIndex(closed, 100, Int.MAX_VALUE))
        assertEquals(null, readerPosition(emptyList(), 0, 0))
    }

    @Test
    fun searchMapsToVisibleReplyBlocksNotReasoningOrTools() {
        val rows = readerRows(listOf(ChatMessage("assistant", "needle", blocks = listOf(
            MessageBlock("thinking", "needle"), MessageBlock("tool", "needle"),
            MessageBlock("text", "Intro\n\nNeedle one\n\nneedle two")
        )), ChatMessage("user", "NEEDLE three")))
        val matches = readerSearchMatches(rows, " needle ")
        assertEquals(3, matches.size)
        assertEquals(listOf(0, 0, 1), matches.map { it.messageIndex })
        assertTrue(readerSearchMatches(rows, " ").isEmpty())
        assertFalse(matches.any { key -> rows.first { it.key == key }.parentFold != null })
    }

    @Test
    fun bookmarkDirectoryDropsInvalidDuplicatesAndKeepsMessageOrder() {
        assertEquals(listOf(0, 2), readerDirectoryIndices(3, listOf(2, -1, 2, 9, 0), true))
        assertEquals(listOf(0, 1, 2), readerDirectoryIndices(3, emptyList(), false))
        assertTrue(readerDirectoryIndices(0, listOf(0), true).isEmpty())
    }

    @Test
    fun wholeMessageAndCodeCopyKeepOrderedBodiesWithoutFenceMarkers() {
        val message = ChatMessage("assistant", "duplicated reply", blocks = listOf(
            MessageBlock("text", "Before"),
            MessageBlock("tool", "```json\n{\"ok\":true}\n```"),
            MessageBlock("text", "~~~kotlin\nval x = 1\n\nval y = 2\n~~~")
        ))
        assertFalse(readerMessageText(message).contains("duplicated reply"))
        assertEquals(listOf(ReaderCodeBlock("json", "{\"ok\":true}"),
            ReaderCodeBlock("kotlin", "val x = 1\n\nval y = 2")), readerCodeBlocks(message))
    }

    @Test
    fun bodyCopyOmitsLegacyThinkingAndToolBlocksWithoutDroppingPlaceholders() {
        val legacy = ChatMessage("assistant", "Reply [image:0]", thinking = "private thought")
        assertEquals("Reply [image:0]", readerVisibleMessageText(legacy))
        assertEquals("private thought\n\nReply [image:0]", readerMessageText(legacy))
        val tools = legacy.copy(thinking = null, blocks = listOf(MessageBlock("tool", "tool result")))
        assertEquals("Reply [image:0]", readerVisibleMessageText(tools))
        assertEquals("tool result\n\nReply [image:0]", readerMessageText(tools))
    }

    @Test
    fun bodyCopyUsesOrderedTextBlocksOnceAndAllCopyPreservesOriginalBehavior() {
        val message = ChatMessage("assistant", "duplicated fallback", blocks = listOf(
            MessageBlock("thinking", "reasoning"), MessageBlock("text", "First [image:0]"),
            MessageBlock("tool", "lookup"), MessageBlock("text", "```kotlin\nval x = 1\n```"),
            MessageBlock("text", "Last")
        ))
        assertEquals("First [image:0]\n\n```kotlin\nval x = 1\n```\n\nLast", readerVisibleMessageText(message))
        assertEquals("reasoning\n\nFirst [image:0]\n\nlookup\n\n```kotlin\nval x = 1\n```\n\nLast", readerMessageText(message))
        assertEquals("", readerVisibleMessageText(message.copy(blocks = listOf(MessageBlock("text", "")))))
    }

    @Test
    fun pureReadingKeepsEveryCoordinateAndBookmarkJumpAcrossFoldChanges() {
        val rows = readerRows(listOf(ChatMessage("assistant", "First\n\nLast", thinking = "Thought"),
            ChatMessage("user", "[image:0]", images = listOf("file:/image.blob")), ChatMessage("assistant", "")))
        val fold = rows.first { it.kind == ReaderRowKind.FOLD }.key
        listOf(emptySet(), setOf(fold)).forEach { expanded ->
            val normal = readerVisibleRows(rows, expanded)
            val pure = readerVisibleRows(rows, expanded, pureReading = true)
            assertEquals(normal.map { it.key }, pure.map { it.key })
            assertFalse(pure.any { it.kind == ReaderRowKind.MESSAGE })
            assertEquals(3, pure.count { it.kind == ReaderRowKind.MESSAGE_ANCHOR })
            normal.forEachIndexed { index, row ->
                assertEquals(readerPosition(normal, index, 17), readerPosition(pure, index, 17))
                assertEquals(readerRowIndex(normal, row.key.messageIndex, row.key.blockIndex),
                    readerRowIndex(pure, row.key.messageIndex, row.key.blockIndex))
            }
            readerDirectoryIndices(3, listOf(0, 2), true).forEach { bookmarked ->
                assertEquals(ReaderBlockKey(bookmarked, 0), pure[readerRowIndex(pure, bookmarked)].key)
            }
        }
    }

    @Test
    fun tablesContainingImagesStayInOneMarkdownRow() {
        val table = "| Image | Name |\n| --- | --- |\n| [image:0] | Sample |"
        val rows = readerRows(listOf(ChatMessage("assistant", table,
            images = listOf("file:/private/table-image.blob"))))
        assertEquals(1, rows.count { it.kind == ReaderRowKind.TEXT })
        assertEquals(0, rows.count { it.kind == ReaderRowKind.IMAGE })
        assertTrue(rows.last().text.contains("![](<file:/private/table-image.blob>)"))
    }

    @Test
    fun rawEmbeddedImagesAndStoredFilesRetainOrderWhileCodeExamplesStayText() {
        val data = "data:image/png;base64,AAAA"
        val source = "before ![old]($data) after [image:0]"
        val segments = readerImageSegments(source, listOf("file:/private/image.blob"))
        assertEquals(listOf(ImageMarkerSegment.Text("before "), ImageMarkerSegment.Image(data),
            ImageMarkerSegment.Text(" after "), ImageMarkerSegment.Image("file:/private/image.blob")), segments)
        val code = "```md\n![sample]($data)\n```"
        assertEquals(listOf(ImageMarkerSegment.Text(code)), readerImageSegments(code, emptyList()))
    }
}
