package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownParserTest {

    @Test
    fun parsesStandardExport() {
        val md = """
            # 测试对话

            *Exported on 2026/10/01 21:00:00*

            **User**:

            你好，帮我写个 Hello World

            ---

            **Assistant**:

            > thinking step

            当然：

            ```kotlin
            fun main() {
                println("Hello")
            }
            ```

            ---

            **User**:

            谢谢！

            ---
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals("测试对话", result.title)
        assertEquals(3, result.messages.size)

        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("你好，帮我写个 Hello World", result.messages[0].content)

        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[1].role)
        assertTrue(result.messages[1].content.contains("> thinking step"))
        assertTrue(result.messages[1].content.contains("```kotlin"))
        assertTrue(result.messages[1].content.contains("println"))
        assertTrue(!result.messages[1].content.trimEnd().endsWith("---"))

        assertEquals(ChatMarkdownParser.Role.USER, result.messages[2].role)
        assertEquals("谢谢！", result.messages[2].content)
    }

    @Test
    fun parsesPlainHeadersAndInlineContent() {
        val md = """
            User:
            hi there

            ---

            Assistant: hello, how can I help?
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertNull(result.title)
        assertEquals(2, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("hi there", result.messages[0].content)
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[1].role)
        assertEquals("hello, how can I help?", result.messages[1].content)
    }

    @Test
    fun mergesConsecutiveSameRoleMessages() {
        val md = """
            **User**:

            first

            ---

            **User**:

            second

            ---

            **Assistant**:

            answer
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals(2, result.messages.size)
        assertEquals("first\n\nsecond", result.messages[0].content)
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[1].role)
    }

    @Test
    fun keepsInteriorHorizontalRulesButStripsTrailingOnes() {
        val md = """
            **Assistant**:

            before

            ---

            after

            ---
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals(1, result.messages.size)
        assertEquals("before\n\n---\n\nafter", result.messages[0].content)
    }

    @Test
    fun fallsBackToSingleDocumentWhenNoHeaders() {
        val md = """
            # Just a document

            Some plain markdown content.
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        // title is recognized from the heading
        assertEquals("Just a document", result.title)
        assertEquals(1, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[0].role)
        assertTrue(result.messages[0].content.contains("Some plain markdown content."))
    }
}
