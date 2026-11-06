package com.chatscroll.app.ui

private val displayMath = Regex("""(?m)^[ \t]*\\\[(.*?)\\\][ \t]*$""", RegexOption.DOT_MATCHES_ALL)
private val codeFence = Regex("""^ {0,3}(?:>[ \t]?)*(`{3,}|~{3,})(.*)$""")

/** Normalize supported display formulas, without changing fenced or inline code examples. */
fun normalizeMathDelimiters(markdown: String): String {
    if (!markdown.contains("\\[")) return markdown
    val code = protectedCodeCharacters(markdown)
    val searchable = String(CharArray(markdown.length) { if (code[it]) ' ' else markdown[it] })
    val output = StringBuilder(markdown.length)
    var copiedThrough = 0
    displayMath.findAll(searchable).forEach { match ->
        // A formula may not consume a code block between its delimiters.
        if (match.range.none { code[it] }) {
            output.append(markdown, copiedThrough, match.range.first)
            val body = checkNotNull(match.groups[1]).range
            output.append("$$\n").append(markdown.substring(body).trim()).append("\n$$")
            copiedThrough = match.range.last + 1
        }
    }
    return output.append(markdown, copiedThrough, markdown.length).toString()
}

private fun protectedCodeCharacters(markdown: String): BooleanArray {
    val protected = BooleanArray(markdown.length)
    var fenceCharacter: Char? = null
    var fenceLength = 0
    var inDisplayMath = false
    var lineStart = 0
    while (lineStart < markdown.length) {
        val newline = markdown.indexOf('\n', lineStart).let { if (it < 0) markdown.length else it }
        val line = markdown.substring(lineStart, newline).trimEnd('\r')
        val fence = codeFence.matchEntire(line)
        val marker = fence?.groupValues?.get(1)
        val afterMarker = fence?.groupValues?.get(2).orEmpty()
        if (fenceCharacter != null) {
            protected.fill(true, lineStart, newline)
            if (marker != null && marker[0] == fenceCharacter && marker.length >= fenceLength &&
                afterMarker.isBlank()
            ) {
                fenceCharacter = null
            }
        } else if (marker != null && (marker[0] != '`' || !afterMarker.contains('`'))) {
            fenceCharacter = marker[0]
            fenceLength = marker.length
            protected.fill(true, lineStart, newline)
        } else if (inDisplayMath) {
            if (line.trimEnd().endsWith("\\]")) inDisplayMath = false
        } else if (line.startsWith("    ") || line.startsWith('\t')) {
            protected.fill(true, lineStart, newline)
        } else if (line.trimStart().startsWith("\\[")) {
            inDisplayMath = !line.trimEnd().endsWith("\\]")
        }
        lineStart = newline + 1
    }

    var offset = 0
    while (offset < markdown.length) {
        if (protected[offset] || markdown[offset] != '`' || markdown.isEscaped(offset)) {
            offset++
            continue
        }
        val start = offset
        while (offset < markdown.length && markdown[offset] == '`') offset++
        val markerLength = offset - start
        var candidate = offset
        var end = -1
        while (candidate < markdown.length) {
            if (protected[candidate]) break
            if (markdown[candidate] != '`') {
                candidate++
                continue
            }
            val candidateStart = candidate
            while (candidate < markdown.length && markdown[candidate] == '`') candidate++
            if (candidate - candidateStart == markerLength) {
                end = candidate
                break
            }
        }
        if (end >= 0) {
            protected.fill(true, start, end)
            offset = end
        }
    }
    return protected
}

private fun String.isEscaped(offset: Int): Boolean {
    var backslashes = 0
    var index = offset - 1
    while (index >= 0 && this[index--] == '\\') backslashes++
    return backslashes % 2 == 1
}

/** Offsets refer to visible rendered text, never to the original Markdown source. */
internal fun visibleMatchRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val matches = mutableListOf<IntRange>()
    var start = text.indexOf(query, ignoreCase = true)
    while (start >= 0) {
        matches += start until start + query.length
        start = text.indexOf(query, start + query.length, ignoreCase = true)
    }
    return matches
}
