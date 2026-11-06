package com.chatscroll.app.ui

import com.chatscroll.app.parser.MarkdownFenceScanner

internal sealed interface ImageMarkerSegment {
    data class Text(val text: String) : ImageMarkerSegment
    data class Image(val dataUri: String) : ImageMarkerSegment
}

private val embeddedImageReference = Regex("""\[image:(\d+)]""")

/** Resolves unescaped, non-code references into the message-wide, zero-based image list. */
internal fun imageMarkerSegments(text: String, images: List<String>): List<ImageMarkerSegment> {
    if (images.isEmpty()) {
        return if (text.isEmpty()) emptyList() else listOf(ImageMarkerSegment.Text(text))
    }
    val codeRanges = imageMarkerCodeRanges(text)
    var codeRangeIndex = 0
    val segments = mutableListOf<ImageMarkerSegment>()
    var textStart = 0
    embeddedImageReference.findAll(text).forEach { match ->
        while (codeRangeIndex < codeRanges.size && codeRanges[codeRangeIndex].last < match.range.first) {
            codeRangeIndex++
        }
        if (codeRanges.getOrNull(codeRangeIndex)?.contains(match.range.first) == true ||
            text.isEscapedAt(match.range.first)
        ) return@forEach
        val index = match.groupValues[1].toIntOrNull()
        val image = index?.let(images::getOrNull) ?: return@forEach
        if (match.range.first > textStart) {
            segments += ImageMarkerSegment.Text(text.substring(textStart, match.range.first))
        }
        segments += ImageMarkerSegment.Image(image)
        textStart = match.range.last + 1
    }
    // Unresolved or malformed markers remain in the surrounding text verbatim.
    if (textStart < text.length) {
        segments += ImageMarkerSegment.Text(text.substring(textStart))
    }
    return segments
}

/** Finds protected spans without splitting the Markdown that will be rendered. */
private fun imageMarkerCodeRanges(text: String): List<IntRange> {
    val fences = mutableListOf<IntRange>()
    var fence: MarkdownFenceScanner.Fence? = null
    var fenceStart = 0
    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val nextLine = (lineEnd + 1).coerceAtMost(text.length)
        val line = text.substring(lineStart, lineEnd)
        val currentFence = fence
        if (currentFence != null) {
            if (currentFence.closes(line)) {
                fences += fenceStart until nextLine
                fence = null
            }
        } else {
            fence = MarkdownFenceScanner.opening(line)
            if (fence != null) fenceStart = lineStart
        }
        lineStart = nextLine
    }
    if (fence != null) fences += fenceStart until text.length

    val ranges = mutableListOf<IntRange>()
    var start = 0
    fences.forEach { range ->
        addInlineCodeRanges(text, start, range.first, ranges)
        ranges += range
        start = range.last + 1
    }
    addInlineCodeRanges(text, start, text.length, ranges)
    return ranges
}

private val backtickRun = Regex("`+")

private fun addInlineCodeRanges(text: String, start: Int, end: Int, ranges: MutableList<IntRange>) {
    val runs = backtickRun.findAll(text, start).takeWhile { it.range.first < end }.toList()
    val closingRuns = IntArray(runs.size) { -1 }
    val nextByLength = mutableMapOf<Int, Int>()
    // Inline code closes only on a run of the same length. Backslashes can escape
    // an opening backtick, but have no escaping effect inside an open code span.
    for (index in runs.indices.reversed()) {
        val run = runs[index]
        val openingLength = run.value.length - if (text.isEscapedAt(run.range.first)) 1 else 0
        closingRuns[index] = nextByLength[openingLength] ?: -1
        nextByLength[run.value.length] = index
    }
    var index = 0
    while (index < runs.size) {
        val closing = closingRuns[index]
        if (closing >= 0) {
            val run = runs[index]
            val openingStart = run.range.first + if (text.isEscapedAt(run.range.first)) 1 else 0
            ranges += openingStart..runs[closing].range.last
            index = closing + 1
        } else {
            index++
        }
    }
}

private fun String.isEscapedAt(index: Int): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') cursor--
    return (index - cursor - 1) % 2 == 1
}
