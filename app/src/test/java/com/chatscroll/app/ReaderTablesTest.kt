package com.chatscroll.app

import com.chatscroll.app.data.ChatMessage
import com.chatscroll.app.ui.ReaderRowKind
import com.chatscroll.app.ui.readerMarkdownSections
import com.chatscroll.app.ui.readerRows
import com.chatscroll.app.ui.readerTableColumnCount
import com.chatscroll.app.ui.readerTableWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTablesTest {
    private val table = "| Name | Value |\n| :--- | ---: |\n| A | B |"

    @Test
    fun countsAlignmentOptionalOuterPipesAndSingleColumns() {
        assertEquals(2, readerTableColumnCount(table))
        assertEquals(3, readerTableColumnCount("A | B | C\n:-- | :-: | --:\n1 | 2 | 3"))
        assertEquals(1, readerTableColumnCount("| Name |\n| --- |\n| Value |"))
        assertEquals(2, readerTableColumnCount("A | B\n- | -\n1 | 2"))
        assertEquals(2, readerTableColumnCount(table.replace("\n", "\r\n")))
    }

    @Test
    fun ignoresEscapedPipesAndMatchedCodeRunsWhenCountingColumns() {
        assertEquals(2, readerTableColumnCount("| A\\|B | `x|y` |\n| --- | --- |\n| 1 | 2 |"))
        assertEquals(2, readerTableColumnCount("| ``x`|y`` | C |\n| --- | --- |"))
        assertEquals(3, readerTableColumnCount("| A\\\\| B | C |\n| --- | --- | --- |"))
        assertEquals(2, readerTableColumnCount("| unclosed ` | C |\n| --- | --- |"))
    }

    @Test
    fun rejectsPipeProseMismatchedColumnsAndCodeExamples() {
        listOf("a | b", "a | b\n---", "| a | b |\n| --- | --- | --- |",
            "`a|b`\n--- | ---", "| a | b |\n| --x | --- |", "```md\n$table\n```",
            "~~~\n$table\n~~~", table.lines().joinToString("\n") { "    $it" },
            "$$\n$table\n$$", "\\[\n$table\n\\]", "``\n$table\n``").forEach {
            assertEquals(it, 0, readerTableColumnCount(it))
            assertTrue(readerMarkdownSections(it).all { section -> section.tableColumns == 0 })
        }
    }

    @Test
    fun safeTablesScrollWithoutWideningSurroundingProseAndKeepExactSource() {
        val source = "Before\n\n$table\n\nAfter\n\n$table\n"
        val sections = readerMarkdownSections(source)
        assertEquals(source, sections.joinToString("") { it.markdown })
        assertEquals(2, sections.count { it.tableColumns == 2 })
        assertTrue(sections.first().markdown.contains("Before"))
        assertEquals(0, sections.first().tableColumns)
        assertTrue(sections.any { it.tableColumns == 0 && it.markdown.contains("After") })
    }

    @Test
    fun referencesHtmlAndAmbiguousParagraphsKeepFullContext() {
        val reference = "[link][ref]\n\n$table\n\n[ref]: https://example.com"
        val html = "Before\n\n<table><tr><th colspan=\"2\">A</th><th>B</th></tr>" +
            "<tr><td>1</td><td>2</td><td>3</td></tr></table>\n\nAfter"
        listOf(reference, html, "Before\n$table", "<details>\n\n$table\n\n</details>",
            "- list\n\n  $table").forEach { source ->
            val sections = readerMarkdownSections(source)
            assertEquals(1, sections.size)
            assertEquals(source, sections.single().markdown)
            assertTrue(sections.single().tableColumns > 0)
        }
        assertEquals(3, readerTableColumnCount(html))
        val inlineHtml = "Before <table><tr><td>A</td><td>B</td></tr>\n\n</table>\n\nAfter"
        val htmlRows = readerRows(listOf(ChatMessage("assistant", inlineHtml)))
        assertEquals(inlineHtml, htmlRows.single { it.kind == ReaderRowKind.TEXT }.text)
        assertEquals(3, readerTableColumnCount("<table>\n    <tr><th>A</th><th>B</th><th>C</th></tr>\n</table>"))
        assertEquals(0, readerTableColumnCount("```html\n$html\n```"))
        assertEquals(0, readerTableColumnCount("`<table><tr><td>sample</td></tr></table>`"))
    }

    @Test
    fun tableImagesAndReferenceDefinitionsRemainInOneSelectableContext() {
        val source = "| Image | Link |\n| --- | --- |\n| [image:0] | [link][ref] |\n\n[ref]: https://example.com"
        val rows = readerRows(listOf(ChatMessage("assistant", source, images = listOf("file:/image.blob"))))
        assertEquals(1, rows.count { it.kind == ReaderRowKind.TEXT })
        assertFalse(rows.any { it.kind == ReaderRowKind.IMAGE })
        val content = rows.single { it.kind == ReaderRowKind.TEXT }.text
        val section = readerMarkdownSections(content).single()
        assertEquals(content, section.markdown)
        assertEquals(2, section.tableColumns)
        assertTrue(section.markdown.contains("![](<file:/image.blob>)"))
        assertTrue(section.markdown.contains("[ref]: https://example.com"))
    }

    @Test
    fun columnWidthHonorsViewportAndCapsOversizedTablesWithoutFontScaling() {
        assertEquals(420f, readerTableWidthDp(3, 320f), 0f)
        assertEquals(840f, readerTableWidthDp(6, 320f), 0f)
        assertEquals(600f, readerTableWidthDp(2, 600f), 0f)
        assertEquals(3360f, readerTableWidthDp(Int.MAX_VALUE, 320f), 0f)
    }
}
