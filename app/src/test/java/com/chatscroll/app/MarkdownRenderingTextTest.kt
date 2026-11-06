package com.chatscroll.app

import com.chatscroll.app.ui.normalizeMathDelimiters
import com.chatscroll.app.ui.visibleMatchRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingTextTest {
    @Test fun leavesBacktickAndTildeFencesUntouched() {
        listOf("```latex", "~~~~").forEach { opening ->
            val closing = if (opening.startsWith('`')) "```" else "~~~~"
            val code = "$opening\n\\[\nx^2\n\\]\n$closing"
            assertEquals(code, normalizeMathDelimiters(code))
        }
    }

    @Test fun leavesInlineAndMultilineCodeSpansUntouched() {
        val code = "Use `\\[x\\]` or ``\n\\[\nx^2\n\\]\n`` to show syntax."
        assertEquals(code, normalizeMathDelimiters(code))
    }

    @Test fun shorterFenceCannotCloseLongerFence() {
        val code = "````\n```\n\\[x\\]\n````"
        assertEquals(code, normalizeMathDelimiters(code))
    }

    @Test fun protectsUnclosedAndIndentedCodeBlocks() {
        val unclosed = "~~~latex\n\\[x\\]"
        assertEquals(unclosed, normalizeMathDelimiters(unclosed))
        val indented = "Example:\n\n    \\[x\\]\n"
        assertEquals(indented, normalizeMathDelimiters(indented))
    }

    @Test fun laterFormulaStillNormalizesAfterUnmatchedBracketInCode() {
        val prefix = "```\n\\[\n```\n\n"
        assertEquals(prefix + "$$\nx^2\n$$", normalizeMathDelimiters(prefix + "\\[x^2\\]"))
    }

    @Test fun formulasOutsideCodeKeepCommandsAndNormalizeIdempotently() {
        val source = "`\\[example\\]`\n\n\\[\n\\frac{a}{b}\n\\]"
        val expected = "`\\[example\\]`\n\n$$\n\\frac{a}{b}\n$$"
        val actual = normalizeMathDelimiters(source)
        assertEquals(expected, actual)
        assertEquals(actual, normalizeMathDelimiters(actual))
    }

    @Test fun indentationInsideAFormulaIsPreservedAsMath() {
        val source = "\\[\n    x + y\n\\]"
        assertEquals("$$\nx + y\n$$", normalizeMathDelimiters(source))
    }

    @Test fun formulaCannotCrossACodeBlock() {
        val source = "\\[\n```\nexample\n```\n\\]"
        assertEquals(source, normalizeMathDelimiters(source))
    }

    @Test fun searchOffsetsUseVisibleCaseInsensitiveText() {
        val text = "Title: Read this link, then READ again."
        assertEquals(listOf(7..10, 28..31), visibleMatchRanges(text, "read"))
        assertTrue(visibleMatchRanges(text, "  ").isEmpty())
        assertTrue(visibleMatchRanges(text, "**Read**").isEmpty())
    }

    @Test fun searchKeepsUnicodeOffsetsAndDoesNotOverlapMatches() {
        assertEquals(listOf(2..3, 6..7), visibleMatchRanges("正文阅读，再阅读", "阅读"))
        assertEquals(listOf(0..1, 2..3), visibleMatchRanges("aaaaa", "aa"))
    }
}
