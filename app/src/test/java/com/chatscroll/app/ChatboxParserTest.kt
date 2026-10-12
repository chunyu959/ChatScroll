package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chatbox exports wrap every turn in a fence under a lower-case role label and
 * write tool calls as `Tool Call: name` lines between those fences. These check
 * that such a file is told apart from the other two formats, that the system
 * prompt and the footer are dropped, and that tool calls fold out of the reply.
 */
class ChatboxParserTest {

    private val sample = """
        # 测试工具与数学公式

        ## 1. 测试工具与数学公式

        **system**:

        ```
        You are a helpful assistant.
        ```

        **user**:

        ```
        这是一个测试对话，请你使用所有工具
        ```

        **assistant**:

        ```
        好的，我来测试所有工具。

        ```

        Tool Call: web_search (state: result)
        Args:
          {
            "query": "Euler identity"
          }
        Result:
          {
            "title": "Euler's identity"
          }

        ```
        两个工具已测试完毕。

        ${'$'}${'$'}e^{i\pi} + 1 = 0${'$'}${'$'}
        ```

        **user**:

        ```
        图片测试
        [image]
        ```

        --------------------

        <a href="https://chatboxai.app"><b>Chatbox AI</b></a>
    """.trimIndent()

    @Test
    fun readsTurnsAndDropsTheSystemPromptAndFooter() {
        val result = ChatMarkdownParser.parse(sample)

        assertEquals("测试工具与数学公式", result.title)
        assertEquals(3, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("这是一个测试对话，请你使用所有工具", result.messages[0].content)
        assertFalse(result.messages.any { it.content.contains("helpful assistant") })
        assertFalse(result.messages.any { it.content.contains("chatboxai.app") })
    }

    @Test
    fun foldsToolCallsOutOfTheReply() {
        val reply = ChatMarkdownParser.parse(sample).messages[1]

        assertEquals(ChatMarkdownParser.Role.ASSISTANT, reply.role)
        val tools = reply.blocks.filter { it.kind == "tool" }
        assertEquals(listOf("web_search"), tools.map { it.name })
        assertTrue(tools[0].text.contains("Euler identity"))
        assertFalse(reply.content.contains("Tool Call"))
        assertTrue(reply.content.contains("两个工具已测试完毕"))
        assertTrue(reply.content.contains("${'$'}${'$'}e^{i\\pi} + 1 = 0${'$'}${'$'}"))

        // The reply is written around the tool call, so its text stays on either side.
        val order = reply.blocks.map { it.kind }
        assertEquals(listOf("text", "tool", "text"), order)
        assertTrue(reply.blocks.first().text.startsWith("好的，我来测试所有工具"))
    }

    @Test
    fun keepsTheImagePlaceholderAsPlainText() {
        val image = ChatMarkdownParser.parse(sample).messages[2]
        assertEquals("图片测试\n[image]", image.content)
        assertTrue(image.images.isEmpty())
    }

    @Test
    fun parsesTheRealExport() {
        val file = java.io.File("C:/Users/ASUS/AppData/Roaming/CherryStudio/Data/Files/01a12f38-7729-729b-8192-03505257216b.md")
        org.junit.Assume.assumeTrue(file.exists())
        val result = ChatMarkdownParser.parse(file.readText(Charsets.UTF_8))

        assertEquals("测试工具与数学公式", result.title)
        assertEquals(6, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertTrue(result.messages[0].content.startsWith("这是一个测试对话"))

        val reply = result.messages[1]
        assertEquals(
            listOf("web_search", "parse_link", "save_memory", "delete_memory"),
            reply.blocks.filter { it.kind == "tool" }.map { it.name }
        )
        assertTrue(reply.blocks.first { it.name == "parse_link" }.text.contains("Euler's identity"))
        assertTrue(reply.content.contains("全部工具测试完成"))
        assertFalse(reply.content.contains("Tool Call"))
        assertFalse(reply.content.contains("searchResults"))

        // Each remark sits between the tool calls it refers to, not after all of them.
        val kinds = reply.blocks.map { it.kind }
        assertEquals(listOf("text", "tool", "tool", "text", "tool", "text", "tool", "text"), kinds)
        assertTrue(reply.blocks[3].text.contains("两个工具已测试完毕"))
        assertTrue(reply.blocks[5].text.contains("delete_memory"))

        assertTrue(result.messages[2].content.contains("[image]"))
        assertTrue(result.messages[2].images.isEmpty())
        assertTrue(result.messages[5].content.contains("表格、代码块"))
        assertFalse(result.messages.any { it.content.contains("chatboxai.app") })
    }

    @Test
    fun leavesARoleHeaderExportUntouched() {
        val md = """
            # 测试

            **User**:

            你好

            ---

            **Assistant**:

            好的
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)
        assertEquals(2, result.messages.size)
        assertEquals("你好", result.messages[0].content)
        assertEquals("好的", result.messages[1].content)
    }

    @Test
    fun leavesAKelivoExportUntouched() {
        val md = """
            # 新对话

            > 2026年10月3日 16:02:10 · 醒栖

            你好

            ---

            > 2026年10月3日 16:02:10 · gemini

            好的
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)
        assertEquals(2, result.messages.size)
        assertEquals("醒栖", result.messages[0].speaker)
        assertEquals("你好", result.messages[0].content)
    }
}
