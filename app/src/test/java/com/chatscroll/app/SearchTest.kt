package com.chatscroll.app

import com.chatscroll.app.data.ConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Search matches only the visible text of a message. These check the snippet that
 * is quoted in the results: where it is cut, and that a miss returns nothing.
 */
class SearchTest {

    @Test
    fun quotesAWindowAroundTheMatch() {
        val content = "前面无关的内容，这里出现了求根公式，后面还有很多话。"
        val snippet = ConversationStore.excerpt(content, "求根公式")
        assertEquals("前面无关的内容，这里出现了求根公式，后面还有很多话。", snippet)
    }

    @Test
    fun returnsNullWhenThereIsNoMatch() {
        assertNull(ConversationStore.excerpt("这段话里没有目标", "求根公式"))
    }

    @Test
    fun matchesRegardlessOfCase() {
        val snippet = ConversationStore.excerpt("use the Delta value here", "delta")
        assertEquals("use the Delta value here", snippet)
    }
}
