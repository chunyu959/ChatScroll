package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kelivo exports mark each turn with a time and a name rather than a role header.
 * These check that such a file is told apart from a role-header export, that the
 * first speaker is read as the user, and that reasoning and tool calls fold away
 * from the reply.
 */
class KelivoParserTest {

    private val sample = """
        # 新对话

        > 2026年10月3日 16:02:10 · 醒栖

        这是一个测试对话，请使用所有工具

        ---

        > 2026年10月3日 16:02:10 · gemini

        **思考内容**

        ```text
        用户要求使用所有工具。
        先调用搜索。
        ```

        [search_web]
        {"items":[{"title":"Euler"}]}

        [calculate]
        {"expression":"cos(pi) + 1","result":"0.0"}

        醒栖，工具都调用过了。数值上 \(\cos\pi+1=0\)。

        \[
        e^{ix}=\cos x+i\sin x
        \]

        ---

        > 2026年10月3日 16:06:27 · 醒栖

        图片测试

        ![](data:image/jpeg;base64,AAAA)

        ---
    """.trimIndent()

    @Test
    fun readsTurnsWithTheFirstSpeakerAsTheUser() {
        val result = ChatMarkdownParser.parse(sample)

        assertEquals("新对话", result.title)
        assertEquals(3, result.messages.size)

        val user = result.messages[0]
        assertEquals(ChatMarkdownParser.Role.USER, user.role)
        assertEquals("醒栖", user.speaker)
        assertEquals("10-03 16:02", user.time)
        assertEquals("这是一个测试对话，请使用所有工具", user.content)

        val reply = result.messages[1]
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, reply.role)
        assertEquals("gemini", reply.speaker)
        assertTrue(reply.content.startsWith("醒栖，工具都调用过了"))
        assertTrue(reply.content.contains("\\[") && reply.content.contains("\\]"))
    }

    @Test
    fun foldsReasoningAndToolCallsOutOfTheReply() {
        val reply = ChatMarkdownParser.parse(sample).messages[1]

        val thinking = reply.blocks.filter { it.kind == "thinking" }
        assertEquals(1, thinking.size)
        assertTrue(thinking[0].text.contains("用户要求使用所有工具"))
        assertFalse(reply.content.contains("思考内容"))

        val tools = reply.blocks.filter { it.kind == "tool" }
        assertEquals(listOf("search_web", "calculate"), tools.map { it.name })
        assertTrue(tools[1].text.contains("\"result\":\"0.0\""))
        assertFalse(reply.content.contains("[calculate]"))

        // Thinking, then the two tools, then the reply, in the order they were written.
        assertEquals(
            listOf("thinking", "tool", "tool", "text"),
            reply.blocks.map { it.kind }
        )
    }

    @Test
    fun liftsEmbeddedImagesOutOfAUserTurn() {
        val image = ChatMarkdownParser.parse(sample).messages[2]
        assertEquals(1, image.images.size)
        assertTrue(image.images[0].startsWith("data:image/jpeg"))
        assertTrue(image.content.contains("[image:0]"))
        assertEquals("醒栖", image.speaker)
    }

    @Test
    fun keepsFetchedPageContentInsideTheTool() {
        val fetchedPage = """
            # Synthetic library guide

            [Library index](https://example.invalid/library)

            ## Visiting the archive

            June 12, 2030

            - Fictional opening hours
            - Synthetic visitor instructions
        """.trimIndent()
        val md = """
            # Synthetic fetch exercise

            > 2030年6月12日 09:30:00 · Reader

            Fetch the synthetic library guide.

            ---

            > 2030年6月12日 09:30:01 · Assistant

            [kelivo_fetch]
            {"status":"ready"}

            [kelivo_fetch]
            FETCHED_PAGE

            The synthetic tools have finished. Here is the reply.

            ---
        """.trimIndent().replace("FETCHED_PAGE", fetchedPage)
        val reply = ChatMarkdownParser.parse(md).messages[1]
        val tools = reply.blocks.filter { it.kind == "tool" }

        assertEquals(listOf("kelivo_fetch", "kelivo_fetch"), tools.map { it.name })
        assertEquals(fetchedPage, tools.last().text)
        assertEquals("The synthetic tools have finished. Here is the reply.", reply.content)
        assertEquals(listOf("tool", "tool", "text"), reply.blocks.map { it.kind })
        assertFalse(reply.content.contains("June 12, 2030"))
        assertFalse(reply.content.contains("example.invalid"))
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
        assertNull(result.messages[0].speaker)
        assertNull(result.messages[0].time)
    }
}
