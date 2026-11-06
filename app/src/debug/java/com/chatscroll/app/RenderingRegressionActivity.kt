package com.chatscroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.chatscroll.app.ui.MarkdownText
import com.chatscroll.app.ui.ProvideReaderRendering
import com.chatscroll.app.ui.ReaderPreferences
import com.chatscroll.app.ui.theme.ChatScrollTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Synthetic fixture only: no Root, repository, imported chats, or persisted preferences. */
class RenderingRegressionActivity : ComponentActivity() {
    var preferences by mutableStateOf(ReaderPreferences(15f, 1.2f))
    var revision by mutableIntStateOf(0)
    var query by mutableStateOf("")
    val listState = LazyListState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChatScrollTheme("light") {
                ProvideReaderRendering(preferences) {
                    Surface(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(48, key = { it }) { index ->
                                Box(Modifier.fillMaxWidth()) {
                                    Surface(Modifier.fillMaxWidth()) {
                                        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Column { MarkdownText(source(index, revision), searchQuery = query) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateSourceAsync() { lifecycleScope.launch { delay(80); revision = 1 } }
    fun scrollTo(index: Int) { lifecycleScope.launch { listState.scrollToItem(index) } }

    companion object {
        fun marker(index: Int, revision: Int) = "REGRESSION_${index}_V$revision"
        fun source(index: Int, revision: Int) = """
            ## ${marker(index, revision)} 中英文标题

            中文段落：逐行检查完整内容，确保异步解析后的原生视图具有真实宽度与足够高度。标点、换行和长句都应正常显示。这段文字用于覆盖屏幕之外的内容，不能只检查可见区域。

            English paragraph with **bold**, *emphasis*, and [a link](https://example.invalid). The complete paragraph must wrap naturally and remain measurable before any tap. More words make this a useful multiline layout on narrow and wide screens.

            第二段中文与 English mixed text。切换字体与行间距后，全部文字仍然保留，并且新滚入的行不应为空白。

            ```kotlin
            val message = "中文 English"
            println(message.repeat(2))
            ```

            | 名称 | Value |
            | --- | --- |
            | 中文 | English |
            | 高度 | complete |

            Inline math \(x^2 + y^2 = z^2\) and the final paragraph.

            ${if (revision == 0) "ORIGINAL_SENTINEL initial source." else "UPDATED_SENTINEL asynchronous replacement has additional 中文内容 and a different ending."}
            END_OF_COMPLETE_ROW
        """.trimIndent()
    }
}
