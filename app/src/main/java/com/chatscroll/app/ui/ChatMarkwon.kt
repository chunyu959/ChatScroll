package com.chatscroll.app.ui

import android.content.Context
import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.chatscroll.app.Prism4jGrammarLocator
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

private const val CODE_TEXT = 0xFF8A4B28.toInt()
private const val CODE_BG = 0xFFF3EFE6.toInt()
private const val TERRACOTTA = 0xFFD97757.toInt()
private const val TABLE_BORDER = 0xFFE8E3D8.toInt()
private const val TABLE_HEADER_BG = 0xFFF6F1E7.toInt()

/**
 * Builds the Markwon instance used to render assistant messages:
 * code blocks with syntax highlighting, tables, quotes, task lists,
 * inline HTML, images and LaTeX math.
 */
object ChatMarkwon {

    fun build(context: Context): Markwon {
        val density = context.resources.displayMetrics.density
        fun dip(value: Int): Int = (value * density).toInt()

        return Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(ImagesPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(
                TablePlugin.create(
                    TableTheme.buildWithDefaults(context)
                        .tableBorderColor(TABLE_BORDER)
                        .tableBorderWidth(dip(1))
                        .tableCellPadding(dip(10))
                        .tableEvenRowBackgroundColor(Color.WHITE)
                        .tableOddRowBackgroundColor(Color.WHITE)
                        .tableHeaderRowBackgroundColor(TABLE_HEADER_BG)
                        .build()
                )
            )
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(
                SyntaxHighlightPlugin.create(
                    Prism4j(Prism4jGrammarLocator()),
                    Prism4jThemeDefault.create()
                )
            )
            .usePlugin(
                JLatexMathPlugin.create(16f * density)
            )
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(CODE_TEXT)
                        .codeBackgroundColor(CODE_BG)
                        .codeBlockTextColor(CODE_TEXT)
                        .codeBlockBackgroundColor(CODE_BG)
                        .linkColor(TERRACOTTA)
                        .isLinkUnderlined(true)
                        .blockQuoteColor(TERRACOTTA)
                        .blockQuoteWidth(dip(3))
                        .headingBreakHeight(0)
                }
            })
            .build()
    }
}

/** Renders a Markdown string inside a Compose layout via a TextView. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { ChatMarkwon.build(context) }

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = Color.TRANSPARENT
                setTextColor(0xFF1F1E1D.toInt())
                textSize = 16f
            }
        },
        update = { markwon.setMarkdown(it, markdown) },
        modifier = modifier.fillMaxWidth()
    )
}
