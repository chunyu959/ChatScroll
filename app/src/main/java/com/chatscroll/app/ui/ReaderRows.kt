package com.chatscroll.app.ui

import com.chatscroll.app.data.ChatMessage
import com.chatscroll.app.parser.MarkdownFenceScanner
import com.chatscroll.app.parser.MarkdownImageScanner

/** Stable coordinates count all rows, including the children of closed folds. */
internal data class ReaderBlockKey(val messageIndex: Int, val blockIndex: Int)
internal data class ReaderPosition(val messageIndex: Int, val offset: Int, val blockIndex: Int)
internal enum class ReaderRowKind { MESSAGE, MESSAGE_ANCHOR, TEXT, IMAGE, FOLD }

internal data class ReaderRow(
    val key: ReaderBlockKey,
    val kind: ReaderRowKind,
    val text: String = "",
    val imageSource: String? = null,
    val foldKind: String? = null,
    val foldName: String? = null,
    val parentFold: ReaderBlockKey? = null
)

internal fun readerRows(messages: List<ChatMessage>): List<ReaderRow> = buildList {
    messages.forEachIndexed { messageIndex, message ->
        var blockIndex = 0
        fun nextKey() = ReaderBlockKey(messageIndex, blockIndex++)
        add(ReaderRow(nextKey(), ReaderRowKind.MESSAGE))
        fun addText(text: String, parent: ReaderBlockKey? = null, foldKind: String? = null) {
            readerMarkdownBlocks(text).forEach { part ->
                // Tables and reference definitions need one Markdown context even when
                // they contain images. Replace stored markers with their destinations.
                if (readerTableColumnCount(part) > 0 || referenceDefinition.containsMatchIn(part) ||
                    htmlContainer.containsMatchIn(part)) {
                    val markdown = imageMarkerSegments(part, message.images).joinToString("") { segment ->
                        when (segment) {
                            is ImageMarkerSegment.Text -> segment.text
                            is ImageMarkerSegment.Image -> "![](<${segment.dataUri}>)"
                        }
                    }
                    add(ReaderRow(nextKey(), ReaderRowKind.TEXT, text = markdown,
                        parentFold = parent, foldKind = foldKind))
                } else {
                    readerImageSegments(part, message.images).forEach { segment ->
                        when (segment) {
                            is ImageMarkerSegment.Image -> add(
                                ReaderRow(nextKey(), ReaderRowKind.IMAGE, imageSource = segment.dataUri,
                                    parentFold = parent, foldKind = foldKind)
                            )
                            is ImageMarkerSegment.Text -> if (segment.text.isNotBlank()) {
                                add(ReaderRow(nextKey(), ReaderRowKind.TEXT, text = segment.text,
                                    parentFold = parent, foldKind = foldKind))
                            }
                        }
                    }
                }
            }
        }
        fun addFold(kind: String, text: String, name: String? = null) {
            val key = nextKey()
            add(ReaderRow(key, ReaderRowKind.FOLD, foldKind = kind, foldName = name))
            addText(text, key, kind)
        }
        val interleaved = message.blocks.any { it.kind == "text" }
        message.blocks.forEach { block ->
            when (block.kind) {
                "thinking", "tool" -> addFold(block.kind, block.text, block.name)
                else -> addText(block.text)
            }
        }
        if (message.blocks.isEmpty() && !message.thinking.isNullOrBlank()) {
            addFold("thinking", message.thinking)
        }
        if (!interleaved) addText(message.content)
        // Some old imports stored attachments without inline markers.
        if (message.content.isBlank() && message.blocks.none { it.text.isNotBlank() }) {
            message.images.forEach { source ->
                add(ReaderRow(nextKey(), ReaderRowKind.IMAGE, imageSource = source))
            }
        }
    }
}

/** Adapt legacy inline embedded images without putting their payload into a text view. */
internal fun readerImageSegments(text: String, images: List<String>): List<ImageMarkerSegment> = buildList {
    imageMarkerSegments(text, images).forEach { segment ->
        if (segment is ImageMarkerSegment.Image) {
            add(segment)
        } else if (segment is ImageMarkerSegment.Text) {
            var start = 0
            MarkdownImageScanner.scan(segment.text).forEach { image ->
                val source = image.destination
                val local = source.startsWith("data:image/", ignoreCase = true) ||
                    source.startsWith("file:", ignoreCase = true) || source.startsWith('/')
                if (local) {
                    if (start < image.start) add(ImageMarkerSegment.Text(segment.text.substring(start, image.start)))
                    add(ImageMarkerSegment.Image(source))
                    start = image.end
                }
            }
            if (start < segment.text.length) add(ImageMarkerSegment.Text(segment.text.substring(start)))
        }
    }
}

/**
 * Split at Markdown paragraph boundaries, never by length. Fences, indented code,
 * loose lists, quotations and display math keep their internal blank lines.
 * Reference definitions and HTML containers can affect distant blocks, so those
 * documents deliberately stay together to preserve their rendering semantics.
 */
internal fun readerMarkdownBlocks(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    if (referenceDefinition.containsMatchIn(text) || htmlContainer.containsMatchIn(text) ||
        inlineHtmlTable.containsMatchIn(text)) {
        return listOf(text)
    }
    val lines = text.split('\n')
    val parts = mutableListOf<String>()
    var start = 0
    var index = 0
    var fence: MarkdownFenceScanner.Fence? = null
    var mathEnd: String? = null
    var container = false
    var indentedCode = false
    while (index < lines.size) {
        val line = lines[index]
        val openFence = fence
        if (openFence != null) {
            if (openFence.closes(line)) fence = null
            index++
            continue
        }
        val closeMath = mathEnd
        if (closeMath != null) {
            if (line.trim() == closeMath) mathEnd = null
            index++
            continue
        }
        MarkdownFenceScanner.opening(line)?.let { fence = it }
        if (fence != null) {
            index++
            continue
        }
        when (line.trim()) {
            "$$" -> mathEnd = "$$"
            "\\[" -> mathEnd = "\\]"
        }
        if (mathEnd != null) {
            index++
            continue
        }
        if (listOrQuote.containsMatchIn(line)) container = true
        if (index == start && (line.startsWith("    ") || line.startsWith('\t'))) indentedCode = true
        if (line.isBlank()) {
            var next = index + 1
            while (next < lines.size && lines[next].isBlank()) next++
            val nextLine = lines.getOrNull(next)
            val continuesContainer = nextLine != null && container &&
                (listOrQuote.containsMatchIn(nextLine) || nextLine.startsWith(' ') || nextLine.startsWith('\t'))
            val continuesCode = nextLine != null && indentedCode &&
                (nextLine.startsWith("    ") || nextLine.startsWith('\t'))
            if (!continuesContainer && !continuesCode) {
                val part = lines.subList(start, next).joinToString("\n")
                if (part.isNotBlank()) parts += part
                start = next
                container = false
                indentedCode = false
            }
            index = next
        } else {
            index++
        }
    }
    if (start < lines.size) {
        val part = lines.subList(start, lines.size).joinToString("\n")
        if (part.isNotBlank()) parts += part
    }
    return parts
}

private val referenceDefinition = Regex("(?m)^ {0,3}\\[[^\\]\\n]+]:")
private val inlineHtmlTable = Regex("<table\\b", RegexOption.IGNORE_CASE)
private val htmlContainer = Regex("(?im)^ {0,3}<(?:/?(?:address|article|aside|blockquote|details|div|dl|fieldset|figure|form|h[1-6]|header|hr|iframe|li|main|nav|ol|p|pre|script|section|style|summary|table|tbody|td|th|thead|tr|ul)\\b|!--|!\\[CDATA\\[)")
private val listOrQuote = Regex("^ {0,3}(?:[-+*][ \\t]+|\\d{1,9}[.)][ \\t]+|>)")

/** Pure mode changes presentation only; message anchors keep saved coordinates stable. */
internal fun readerVisibleRows(
    rows: List<ReaderRow>, expanded: Set<ReaderBlockKey>, pureReading: Boolean = false
): List<ReaderRow> = rows.filter { it.parentFold == null || it.parentFold in expanded }.map { row ->
    if (pureReading && row.kind == ReaderRowKind.MESSAGE) row.copy(kind = ReaderRowKind.MESSAGE_ANCHOR) else row
}

internal fun readerRowIndex(rows: List<ReaderRow>, messageIndex: Int, blockIndex: Int = 0): Int {
    if (rows.isEmpty()) return 0
    val targetMessage = messageIndex.coerceIn(0, rows.last().key.messageIndex)
    val first = rows.indexOfFirst { it.key.messageIndex == targetMessage }
    if (first < 0) return 0
    var result = first
    for (index in first until rows.size) {
        val key = rows[index].key
        if (key.messageIndex != targetMessage || key.blockIndex > blockIndex.coerceAtLeast(0)) break
        result = index
    }
    return result
}

internal fun readerPosition(rows: List<ReaderRow>, rowIndex: Int, offset: Int): ReaderPosition? =
    rows.getOrNull(rowIndex)?.key?.let { ReaderPosition(it.messageIndex, offset.coerceAtLeast(0), it.blockIndex) }

internal fun readerSearchMatches(rows: List<ReaderRow>, query: String): List<ReaderBlockKey> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return rows.filter { it.kind == ReaderRowKind.TEXT && it.parentFold == null &&
        it.text.contains(needle, ignoreCase = true) }.map { it.key }
}

internal fun readerDirectoryIndices(messageCount: Int, bookmarks: List<Int>, onlyBookmarks: Boolean): List<Int> =
    if (onlyBookmarks) bookmarks.filter { it in 0 until messageCount }.distinct().sorted()
    else (0 until messageCount).toList()

/** Copy the reply body, preserving stored image placeholders and ordered text blocks. */
internal fun readerVisibleMessageText(message: ChatMessage): String {
    val textBlocks = message.blocks.filter { it.kind == "text" }
    return if (textBlocks.isEmpty()) message.content
    else textBlocks.map { it.text }.filter { it.isNotEmpty() }.joinToString("\n\n")
}

internal fun readerMessageText(message: ChatMessage): String {
    if (message.blocks.isEmpty()) {
        return listOfNotNull(message.thinking?.takeIf { it.isNotBlank() }, message.content)
            .joinToString("\n\n")
    }
    val ordered = message.blocks.map { it.text }
    return (if (message.blocks.any { it.kind == "text" }) ordered else ordered + message.content)
        .filter { it.isNotEmpty() }.joinToString("\n\n")
}

internal data class ReaderCodeBlock(val language: String, val code: String)

/** Extract on demand: large code bodies never enter saved instance state. */
internal fun readerCodeBlocks(message: ChatMessage): List<ReaderCodeBlock> {
    val lines = readerMessageText(message).split('\n')
    val result = mutableListOf<ReaderCodeBlock>()
    var index = 0
    while (index < lines.size) {
        val fence = MarkdownFenceScanner.opening(lines[index])
        if (fence == null) {
            index++
            continue
        }
        val info = lines[index].trimStart().drop(fence.length).trim()
        val language = info.takeWhile { !it.isWhitespace() }
        val start = ++index
        while (index < lines.size && !fence.closes(lines[index])) index++
        result += ReaderCodeBlock(language, lines.subList(start, index).joinToString("\n"))
        if (index < lines.size) index++
    }
    return result
}
