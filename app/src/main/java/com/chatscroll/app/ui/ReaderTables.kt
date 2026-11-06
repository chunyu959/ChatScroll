package com.chatscroll.app.ui

import com.chatscroll.app.parser.MarkdownFenceScanner

internal data class ReaderMarkdownSection(val markdown: String, val tableColumns: Int = 0)

private data class ReaderTableRange(val start: Int, val end: Int, val columns: Int, val standalone: Boolean)
private data class ReaderTableCells(val values: List<String>, val hasPipe: Boolean)
private val tableAlignment = Regex(":?-+:?")
private val tableReference = Regex("(?m)^ {0,3}\\[[^\\]\\n]+]:")
private val tableHtmlContext = Regex("(?im)^ {0,3}<(?:/?[a-z][a-z0-9]*\\b|!--|!\\[CDATA\\[)")
private val tableContainer = Regex("(?m)^ {0,3}(?:[-+*][ \\t]+|\\d{1,9}[.)][ \\t]+|>)")
private val htmlTable = Regex("<table\\b", RegexOption.IGNORE_CASE)
private val htmlTableEnd = Regex("</table\\s*>", RegexOption.IGNORE_CASE)
private val htmlRow = Regex("<tr\\b[^>]*>(.*?)</tr\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val htmlCell = Regex("<t[dh]\\b([^>]*)>", RegexOption.IGNORE_CASE)
private val htmlColspan = Regex("\\bcolspan\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)

/** Only layout changes: every source character stays in exactly one rendered section. */
internal fun readerMarkdownSections(markdown: String): List<ReaderMarkdownSection> {
    val tables = readerTableRanges(markdown)
    val htmlColumns = readerHtmlTableColumns(markdown)
    val columns = maxOf(tables.maxOfOrNull { it.columns } ?: 0, htmlColumns)
    if (columns == 0) return listOf(ReaderMarkdownSection(markdown))
    // Definitions and HTML can affect distant blocks. Nested/container tables also
    // keep their original context; never feed a lossy fragment to MarkdownText.
    if (htmlColumns > 0 || tableReference.containsMatchIn(markdown) ||
        tableHtmlContext.containsMatchIn(markdown) || tableContainer.containsMatchIn(markdown) ||
        tables.any { !it.standalone }) {
        return listOf(ReaderMarkdownSection(markdown, columns))
    }
    return buildList {
        var start = 0
        tables.forEach { table ->
            if (start < table.start) add(ReaderMarkdownSection(markdown.substring(start, table.start)))
            add(ReaderMarkdownSection(markdown.substring(table.start, table.end), table.columns))
            start = table.end
        }
        if (start < markdown.length) add(ReaderMarkdownSection(markdown.substring(start)))
    }
}

internal fun readerTableColumnCount(markdown: String): Int = maxOf(
    readerTableRanges(markdown).maxOfOrNull { it.columns } ?: 0,
    readerHtmlTableColumns(markdown)
)

/** Wide enough for readable cells without unbounded native view allocations. */
internal fun readerTableWidthDp(columns: Int, viewportWidth: Float): Float =
    maxOf(viewportWidth, columns.coerceIn(1, 24) * 140f).coerceAtMost(maxOf(viewportWidth, 3360f))

private fun readerTableRanges(markdown: String): List<ReaderTableRange> {
    val lines = markdown.split('\n')
    val offsets = IntArray(lines.size + 1)
    lines.forEachIndexed { index, line -> offsets[index + 1] = offsets[index] + line.length + 1 }
    val eligible = readerTableEligibleLines(lines)
    // A multiline inline-code span can contain table-looking physical lines.
    // Only mask spans crossing a newline; ordinary cell code remains eligible.
    var codeIndex = 0
    while (codeIndex < markdown.length) {
        val lineIndex = offsets.binarySearch(codeIndex).let { if (it >= 0) it else -it - 2 }
        if (!eligible[lineIndex]) { codeIndex = offsets[lineIndex + 1]; continue }
        if (markdown[codeIndex] == '\\') { codeIndex += 2; continue }
        if (markdown[codeIndex] == '`') {
            val end = codeSpanEnd(markdown, codeIndex)
            if (end != null) {
                val lastLine = offsets.binarySearch(end - 1).let { if (it >= 0) it else -it - 2 }
                if (lastLine > lineIndex && (lineIndex..lastLine).all { eligible[it] }) {
                    for (line in lineIndex..lastLine) eligible[line] = false
                }
                codeIndex = end
                continue
            }
        }
        codeIndex++
    }
    val result = mutableListOf<ReaderTableRange>()
    var index = 1
    while (index < lines.size) {
        if (!eligible[index] || !eligible[index - 1]) { index++; continue }
        val header = tableCells(lines[index - 1])
        val delimiter = tableCells(lines[index])
        if (!header.hasPipe || !delimiter.hasPipe || delimiter.values.isEmpty() ||
            header.values.size != delimiter.values.size ||
            delimiter.values.any { !tableAlignment.matches(it.trim()) }) {
            index++
            continue
        }
        val first = index - 1
        var end = index + 1
        // Keep the complete table paragraph. A non-pipe line can be a one-cell
        // body row in GFM, so guessing where prose resumes would be destructive.
        while (end < lines.size && lines[end].isNotBlank() && eligible[end]) end++
        result += ReaderTableRange(offsets[first], offsets[end].coerceAtMost(markdown.length),
            delimiter.values.size, first == 0 || lines[first - 1].isBlank())
        index = end + 1
    }
    return result
}

private fun readerTableEligibleLines(lines: List<String>): BooleanArray {
    val result = BooleanArray(lines.size)
    var fence: MarkdownFenceScanner.Fence? = null
    var mathEnd: String? = null
    lines.forEachIndexed { index, line ->
        val openFence = fence
        if (openFence != null) {
            if (openFence.closes(line)) fence = null
            return@forEachIndexed
        }
        if (mathEnd != null) {
            if (line.trim() == mathEnd) mathEnd = null
            return@forEachIndexed
        }
        fence = MarkdownFenceScanner.opening(line)
        if (fence != null) return@forEachIndexed
        when (line.trim()) { "$$" -> mathEnd = "$$"; "\\[" -> mathEnd = "\\]" }
        if (mathEnd != null) return@forEachIndexed
        result[index] = !line.startsWith("    ") && !line.startsWith('\t')
    }
    return result
}

/** Escapes and matched backtick runs do not introduce table columns. */
private fun tableCells(line: String): ReaderTableCells {
    val text = line.trim()
    val separators = mutableListOf<Int>()
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '\\' -> { index += 2; continue }
            '`' -> {
                val end = codeSpanEnd(text, index)
                if (end != null) { index = end; continue }
                while (index < text.length && text[index] == '`') index++
                continue
            }
            '|' -> separators += index
        }
        index++
    }
    if (separators.isEmpty()) return ReaderTableCells(listOf(text), false)
    val cells = mutableListOf<String>()
    var start = 0
    separators.forEach { separator -> cells += text.substring(start, separator); start = separator + 1 }
    cells += text.substring(start)
    if (separators.first() == 0) cells.removeAt(0)
    if (separators.last() == text.lastIndex) cells.removeAt(cells.lastIndex)
    return ReaderTableCells(cells, true)
}

private fun codeSpanEnd(text: String, start: Int): Int? {
    var openEnd = start
    while (openEnd < text.length && text[openEnd] == '`') openEnd++
    val length = openEnd - start
    var next = text.indexOf('`', openEnd)
    while (next >= 0) {
        var end = next
        while (end < text.length && text[end] == '`') end++
        if (end - next == length) return end
        next = text.indexOf('`', end)
    }
    return null
}

private fun readerHtmlTableColumns(markdown: String): Int {
    if (!htmlTable.containsMatchIn(markdown)) return 0
    val lines = markdown.split('\n')
    val eligible = readerTableEligibleLines(lines)
    var insideTable = false
    val html = lines.mapIndexed { lineIndex, line ->
        // Indented cells inside an HTML table still belong to its HTML block;
        // only the opening tag must be outside fenced/indented Markdown code.
        if (!insideTable && !eligible[lineIndex]) return@mapIndexed ""
        val visible = buildString {
            var index = 0
            while (index < line.length) {
                val end = if (line[index] == '`') codeSpanEnd(line, index) else null
                if (end != null) { append(' '); index = end }
                else { append(line[index]); index++ }
            }
        }
        if (htmlTable.containsMatchIn(visible)) insideTable = true
        if (htmlTableEnd.containsMatchIn(visible)) insideTable = false
        visible
    }.joinToString("\n")
    if (!htmlTable.containsMatchIn(html)) return 0
    return htmlRow.findAll(html).maxOfOrNull { row ->
        htmlCell.findAll(row.groupValues[1]).sumOf { cell ->
            htmlColspan.find(cell.groupValues[1])?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 200) ?: 1
        }.coerceAtMost(200)
    }?.coerceAtLeast(1) ?: 2
}
