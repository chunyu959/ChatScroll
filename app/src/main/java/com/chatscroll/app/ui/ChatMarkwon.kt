package com.chatscroll.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
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
import io.noties.markwon.image.ImageProps
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.MediaDecoder
import java.io.InputStream
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

/**
 * Longest edge a decoded image may have, in pixels. Embedded exports can carry
 * screenshots several thousand pixels tall; decoding those at full size costs
 * tens of megabytes each and is what gets the app killed while scrolling.
 * 1600 px is sharper than any phone screen needs at reading width.
 */
private const val MAX_BITMAP_EDGE = 1600

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
            .usePlugin(ImagesPlugin.create { plugin ->
                plugin.defaultMediaDecoder(BoundedImageDecoder(context.resources, MAX_BITMAP_EDGE))
            })
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
                override fun configureSpansFactory(builder: io.noties.markwon.MarkwonSpansFactory.Builder) {
                    // Until an image has actually loaded, draw nothing. The default
                    // draws the destination text as a placeholder, which for an embedded
                    // base64 image means painting hundreds of thousands of characters.
                    builder.setFactory(org.commonmark.node.Image::class.java) { config, props ->
                        AsyncDrawableSpan(
                            config.theme(),
                            io.noties.markwon.image.AsyncDrawable(
                                ImageProps.DESTINATION.require(props),
                                config.asyncDrawableLoader(),
                                config.imageSizeResolver(),
                                ImageProps.IMAGE_SIZE[props]
                            ),
                            AsyncDrawableSpan.ALIGN_CENTER,
                            ImageProps.REPLACEMENT_TEXT_IS_LINK.get(props, false)
                        )
                    }
                }

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

/**
 * Decodes images down to [maxEdge] pixels on their longest side, so a tall
 * screenshot never becomes a full-size bitmap in memory. Supports the same
 * formats as the default decoder.
 */
private class BoundedImageDecoder(
    private val resources: android.content.res.Resources,
    private val maxEdge: Int
) : MediaDecoder() {

    override fun supportedTypes(): MutableCollection<String> =
        mutableListOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/bmp")

    override fun decode(contentType: String?, inputStream: InputStream): Drawable {
        val bytes = inputStream.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = bitmapSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("Could not decode image")
        return BitmapDrawable(resources, bitmap)
    }
}

/** Matches the `[image:N]` markers the parser leaves where an image was removed. */
val imageMarker = Regex("""\[image:(\d+)]""")

/**
 * Decodes an embedded `data:image/...;base64,...` URI to a bitmap whose longest
 * edge is at most [MAX_BITMAP_EDGE] pixels. Returns null when the URI is not a
 * decodable image.
 */
fun decodeDataUriBitmap(dataUri: String): Bitmap? {
    val comma = dataUri.indexOf(',')
    if (comma < 0) return null
    val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = bitmapSampleSize(bounds.outWidth, bounds.outHeight, MAX_BITMAP_EDGE)
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private fun bitmapSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / sample > maxEdge * 2 || height / sample > maxEdge * 2) sample *= 2
    return sample
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
