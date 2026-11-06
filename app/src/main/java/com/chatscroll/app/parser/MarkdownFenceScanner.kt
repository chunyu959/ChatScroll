package com.chatscroll.app.parser

/** Physical-line Markdown fences. Payloads are never decoded before framing. */
internal object MarkdownFenceScanner {
    data class Fence(val character: Char, val length: Int) {
        fun closes(line: String): Boolean {
            val text = unindent(line) ?: return false
            val count = text.takeWhile { it == character }.length
            return count >= length && text.drop(count).isBlank()
        }
    }

    fun opening(line: String): Fence? {
        val text = unindent(line) ?: return null
        val character = text.firstOrNull() ?: return null
        if (character != '`' && character != '~') return null
        val length = text.takeWhile { it == character }.length
        if (length < 3) return null
        if (character == '`' && text.drop(length).contains('`')) return null
        return Fence(character, length)
    }

    /** Index after a matched closing fence, or null for an incomplete block. */
    fun end(lines: List<String>, start: Int): Int? {
        val fence = lines.getOrNull(start)?.let(::opening) ?: return null
        for (index in start + 1 until lines.size) {
            if (fence.closes(lines[index])) return index + 1
        }
        return null
    }

    private fun unindent(line: String): String? {
        val spaces = line.takeWhile { it == ' ' }.length
        if (spaces > 3) return null
        return line.substring(spaces)
    }
}
