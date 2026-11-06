package com.chatscroll.app.parser

/** Source ranges for inline images; Markdown examples and escaped syntax stay opaque. */
internal object MarkdownImageScanner {
    data class Image(val start: Int, val end: Int, val destination: String)
    private data class InlineNode(val image: Image, val labelEnd: Int)
    private val quotePrefix = Regex("^ {0,3}(?:> ?)+")

    fun scan(text: String): List<Image> {
        val images = ArrayList<Image>()
        val code = codeBlocks(text)
        var block = 0
        var cursor = 0
        while (cursor < text.length) {
            while (block < code.size && cursor > code[block].last) block++
            if (block < code.size && cursor in code[block]) {
                cursor = code[block].last + 1
                continue
            }
            if (isEscaped(text, cursor)) {
                cursor++
                continue
            }
            if (text[cursor] == '`') {
                val end = codeSpanEnd(text, cursor)
                if (end != null) {
                    cursor = end
                    continue
                }
                while (cursor < text.length && text[cursor] == '`') cursor++
                continue
            }
            val image = imageAt(text, cursor)
            if (image != null && (block >= code.size || image.end <= code[block].first)) {
                images.add(image)
                cursor = image.end
            } else if (text[cursor] == '[') {
                val link = inlineNodeAt(text, cursor, false)
                if (link != null && (block >= code.size || link.image.end <= code[block].first)) {
                    // Images inside a link label are real images; image-looking text in its URL/title is not.
                    images.addAll(scan(text.substring(cursor + 1, link.labelEnd)).map {
                        it.copy(start = it.start + cursor + 1, end = it.end + cursor + 1)
                    })
                    cursor = link.image.end
                } else cursor++
            } else cursor++
        }
        return images
    }

    /** A bounded image-only output may wrap its embedded payload across physical lines. */
    fun standaloneEnd(lines: List<String>, start: Int): Int? {
        val first = lines.getOrNull(start) ?: return null
        val indent = first.takeWhile { it == ' ' }.length
        if (indent > 3 || !first.substring(indent).startsWith("![")) return null
        val source = lines.subList(start, lines.size).joinToString("\n")
        val image = imageAt(source, indent) ?: return null
        val lineEnd = source.indexOf('\n', image.end).let { if (it < 0) source.length else it }
        if (!source.substring(image.end, lineEnd).isBlank()) return null
        return start + source.substring(0, lineEnd).count { it == '\n' } + 1
    }

    private fun imageAt(text: String, start: Int): Image? = inlineNodeAt(text, start, true)?.image

    private fun inlineNodeAt(text: String, start: Int, isImage: Boolean): InlineNode? {
        if (!text.startsWith(if (isImage) "![" else "[", start)) return null
        var cursor = start + if (isImage) 2 else 1
        var brackets = 1
        while (cursor < text.length && brackets > 0) {
            when (text[cursor]) {
                '\\' -> { cursor += 2; continue }
                '`' -> {
                    val end = codeSpanEnd(text, cursor)
                    if (end != null) { cursor = end; continue }
                }
                '[' -> brackets++
                ']' -> brackets--
            }
            cursor++
        }
        if (brackets != 0 || cursor >= text.length || text[cursor] != '(') return null
        val labelEnd = cursor - 1
        cursor++
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        val destination: String
        if (text.getOrNull(cursor) == '<') {
            val from = ++cursor
            while (cursor < text.length && text[cursor] != '>') {
                if (text[cursor] == '<') return null
                cursor += if (text[cursor] == '\\') 2 else 1
            }
            if (cursor >= text.length) return null
            destination = text.substring(from, cursor++)
        } else {
            val from = cursor
            var parentheses = 0
            // Exporters also wrap raw base64 and data URLs, though ordinary URLs cannot wrap.
            val mayWrap = text.regionMatches(cursor, "data:image/", 0, 11, true) ||
                text.getOrNull(cursor)?.let { it.isLetterOrDigit() || it == '/' || it == '+' } == true
            while (cursor < text.length) {
                val character = text[cursor]
                if (character == '\\') {
                    cursor += 2
                    continue
                }
                if (character == '(') parentheses++
                if (character == ')') {
                    if (parentheses == 0) break
                    parentheses--
                }
                if (character.isWhitespace()) {
                    val gap = cursor
                    while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                    val next = text.getOrNull(cursor)
                    if (next == ')' || next == '"' || next == '\'' || next == '(' || next == null) {
                        cursor = gap
                        break
                    }
                    val spacing = text.substring(gap, cursor)
                    if (!mayWrap || spacing.count { it == '\n' } != 1 ||
                        (!text.substring(from, gap).all { isPayloadCharacter(it) || it.isWhitespace() } &&
                            !text.regionMatches(from, "data:image/", 0, 11, true))) {
                        cursor = gap
                        break
                    }
                    continue
                }
                cursor++
            }
            if (cursor > text.length || parentheses != 0) return null
            destination = text.substring(from, cursor)
        }
        val beforeSpace = cursor
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (text.getOrNull(cursor) == ')') return InlineNode(Image(start, cursor + 1, destination), labelEnd)
        if (cursor == beforeSpace) return null
        val quote = text.getOrNull(cursor) ?: return null
        if (quote != '"' && quote != '\'' && quote != '(') return null
        val close = if (quote == '(') ')' else quote
        cursor++
        var closed = false
        while (cursor < text.length) {
            if (text[cursor] == '\\') { cursor += 2; continue }
            if (text[cursor] == close) { cursor++; closed = true; break }
            if (quote == '(' && text[cursor] == '(') return null
            cursor++
        }
        if (!closed) return null
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        return if (text.getOrNull(cursor) == ')') InlineNode(Image(start, cursor + 1, destination), labelEnd) else null
    }

    private fun codeBlocks(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var fence: MarkdownFenceScanner.Fence? = null
        var offset = 0
        for (line in text.split('\n')) {
            val end = offset + line.length
            val framing = line.replaceFirst(quotePrefix, "")
            val active = fence
            if (active != null) {
                ranges.add(offset..end)
                if (active.closes(framing)) fence = null
            } else {
                fence = MarkdownFenceScanner.opening(framing)
                if (fence != null || framing.startsWith("    ") || framing.startsWith('\t')) ranges.add(offset..end)
            }
            offset = end + 1
        }
        return ranges
    }

    private fun codeSpanEnd(text: String, start: Int): Int? {
        var cursor = start
        while (cursor < text.length && text[cursor] == '`') cursor++
        val length = cursor - start
        while (cursor < text.length) {
            if (text[cursor] != '`') { cursor++; continue }
            val from = cursor
            while (cursor < text.length && text[cursor] == '`') cursor++
            if (cursor - from == length) return cursor
        }
        return null
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var previous = index - 1
        while (previous >= 0 && text[previous] == '\\') previous--
        return (index - previous - 1) % 2 == 1
    }

    private fun isPayloadCharacter(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in "+/="
}
