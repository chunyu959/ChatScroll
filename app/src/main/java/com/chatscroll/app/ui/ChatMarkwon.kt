package com.chatscroll.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.key
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnPreDraw
import com.chatscroll.app.ui.theme.LightReaderPalette
import com.chatscroll.app.ui.theme.LocalReaderPalette
import com.chatscroll.app.ui.theme.ReaderPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.commonmark.node.Node
import com.chatscroll.app.Prism4jGrammarLocator
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.util.regex.Pattern
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImageProps
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.MediaDecoder
import java.io.InputStream
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

/**
 * Longest edge a decoded image may have, in pixels. Embedded exports can carry
 * screenshots several thousand pixels tall; decoding those at full size costs
 * tens of megabytes each and is what gets the app killed while scrolling.
 * 1600 px is sharper than any phone screen needs at reading width.
 */
private const val MAX_BITMAP_EDGE = 1600

/**
 * Builds the Markwon instance used to render assistant messages:
 * code blocks with syntax highlighting, tables, quotes, task lists,
 * inline HTML, images and LaTeX math.
 */
object ChatMarkwon {

    fun build(
        context: Context,
        palette: ReaderPalette = LightReaderPalette,
        fontSizeSp: Float = 16f,
        scaledDensity: Float = context.resources.displayMetrics.scaledDensity
    ): Markwon {
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
                        .tableBorderColor(palette.border.toArgb())
                        .tableBorderWidth(dip(1))
                        .tableCellPadding(dip(10))
                        .tableEvenRowBackgroundColor(palette.tableRow.toArgb())
                        .tableOddRowBackgroundColor(palette.tableRow.toArgb())
                        .tableHeaderRowBackgroundColor(palette.tableHeader.toArgb())
                        .build()
                )
            )
            .usePlugin(TaskListPlugin.create(
                palette.accent.toArgb(), palette.accent.toArgb(), palette.background.toArgb()
            ))
            .usePlugin(
                SyntaxHighlightPlugin.create(
                    Prism4j(Prism4jGrammarLocator()),
                    if (palette.isDark) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
                )
            )
            // The first processor to match a backslash wins. The built-in one treats
            // `\` as an escape, which strips it from `\(...\)` and leaves the formula
            // as plain text, so the \(...\) reader goes in before the defaults.
            // factoryBuilder() loads the defaults immediately, so it is built by hand.
            .usePlugin(MarkwonInlineParserPlugin.create(
                io.noties.markwon.inlineparser.MarkwonInlineParser.factoryBuilderNoDefaults()
                    .apply {
                        addInlineProcessor(ParenMathProcessor())
                        includeDefaults()
                    }
            ))
            .usePlugin(JLatexMathPlugin.create(fontSizeSp * scaledDensity) { builder ->
                builder.inlinesEnabled(true)
                builder.theme().textColor(palette.text.toArgb())
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configure(registry: io.noties.markwon.MarkwonPlugin.Registry) {
                    registry.require(MarkwonInlineParserPlugin::class.java)
                        .factoryBuilder()
                        .addInlineProcessor(ParenMathProcessor())
                }
            })
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
                        .codeTextColor(palette.codeText.toArgb())
                        .codeBackgroundColor(palette.codeBackground.toArgb())
                        .codeBlockTextColor(palette.codeText.toArgb())
                        .codeBlockBackgroundColor(palette.codeBackground.toArgb())
                        .linkColor(palette.accent.toArgb())
                        .isLinkUnderlined(true)
                        .blockQuoteColor(palette.accent.toArgb())
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

/**
 * Reads inline math written as `\(...\)`, which is how exports write it.
 *
 * The renderer has no inline dollar syntax, so this produces the formula node
 * directly. It has to run before the built-in backslash handler: that handler
 * treats a backslash as an escape and would otherwise drop it, leaving `(ax^2...)`
 * as plain text. It only matches a complete pair, so a stray `\(` is left alone.
 */
private class ParenMathProcessor : InlineProcessor() {
    override fun specialCharacter(): Char = '\\'

    override fun parse(): org.commonmark.node.Node? {
        val matched = match(PAREN_MATH) ?: return null
        return JLatexMathNode().apply { latex(matched.substring(2, matched.length - 2)) }
    }

    companion object {
        private val PAREN_MATH: Pattern = Pattern.compile("\\\\\\((.+?)\\\\\\)")
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

private class ReaderRenderer(val markwon: Markwon) {
    // Markwon plugins share a parser. Serialize parse calls without blocking the UI;
    // span creation and all TextView/plugin view callbacks stay on the main thread.
    private val parseLock = Mutex()

    suspend fun parse(markdown: String): Node = withContext(Dispatchers.Default) {
        parseLock.withLock { markwon.parse(normalizeMathDelimiters(markdown)) }
    }
}

private val LocalReaderRenderer = staticCompositionLocalOf<ReaderRenderer?> { null }

/** One renderer per reading environment; no rendered spans are shared between views. */
@Composable
fun ProvideReaderRendering(preferences: ReaderPreferences, content: @Composable () -> Unit) {
    val safePreferences = remember(preferences) { preferences.validated() }
    val renderer = rememberReaderRenderer(safePreferences)
    CompositionLocalProvider(
        LocalReaderPreferences provides safePreferences,
        LocalReaderRenderer provides renderer,
        content = content
    )
}

@Composable
private fun rememberReaderRenderer(preferences: ReaderPreferences): ReaderRenderer {
    val context = LocalContext.current
    val palette = LocalReaderPalette.current
    val density = LocalDensity.current
    return remember(context, palette, preferences.fontSizeSp, density.density, density.fontScale) {
        ReaderRenderer(ChatMarkwon.build(context, palette, preferences.fontSizeSp, density.density * density.fontScale))
    }
}

private data class ParsedMarkdown(val renderer: ReaderRenderer, val source: String, val node: Node)
private class SearchBackground(color: Int) : BackgroundColorSpan(color)
private class SearchForeground(color: Int) : ForegroundColorSpan(color)

private class ReaderTextView(context: Context) : TextView(context) {
    var appliedQuery: String? = null
}

/** Rendered-text search offsets remain correct across links, emphasis, and headings. */
private fun highlightMatches(text: Spannable, query: String, palette: ReaderPalette) {
    text.getSpans(0, text.length, SearchBackground::class.java).forEach(text::removeSpan)
    text.getSpans(0, text.length, SearchForeground::class.java).forEach(text::removeSpan)
    visibleMatchRanges(text.toString(), query).forEach { range ->
        text.setSpan(SearchBackground(palette.searchHighlight.toArgb()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(SearchForeground(palette.searchText.toArgb()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private fun highlightMatches(view: TextView, query: String, palette: ReaderPalette) {
    val text = view.text as? Spannable ?: return
    highlightMatches(text, query, palette)
    // Table rows are replacement spans. Their visible cell text lives in layouts,
    // so highlighting the parent TextView alone would miss every table cell.
    text.getSpans(0, text.length, TableRowSpan::class.java).forEach { row ->
        val cellWidth = row.cellWidth()
        if (cellWidth > 0) {
            var x = 0
            while (x < view.width) {
                val layout = row.findLayoutForHorizontalOffset(x) ?: break
                (layout.text as? Spannable)?.let { highlightMatches(it, query, palette) }
                x += cellWidth
            }
        }
    }
}

/** Selectable Markdown with one shared renderer and independent spans per TextView. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, searchQuery: String = "") {
    val preferences = LocalReaderPreferences.current
    val palette = LocalReaderPalette.current
    val renderer = LocalReaderRenderer.current ?: rememberReaderRenderer(preferences)
    val parsed by produceState<ParsedMarkdown?>(null, renderer, markdown) {
        value = ParsedMarkdown(renderer, markdown, renderer.parse(markdown))
    }
    val current = parsed?.takeIf { it.renderer === renderer && it.source == markdown }
    // Render once on the UI thread. Image/LaTeX spans contain mutable drawable state,
    // so this result belongs to this composition and is never cached globally.
    val rendered = remember(current, preferences) { current?.let { renderer.markwon.render(it.node) } }

    if (rendered == null) {
        val placeholderHeight = with(LocalDensity.current) {
            (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp.toDp()
        }
        Spacer(modifier.fillMaxWidth().height(placeholderHeight))
        return
    }

    // LazyColumn can premeasure an empty native view before async parsing finishes.
    // The first native measure must see both the final typography and populated text.
    // Source/typography changes get a fresh view; search only changes highlight spans.
    key(renderer, markdown, preferences) {
        AndroidView(
            factory = { context ->
                ReaderTextView(context).apply {
                    setTextIsSelectable(true)
                    linksClickable = true
                    textSize = preferences.fontSizeSp
                    setLineSpacing(0f, preferences.lineHeightMultiplier)
                    setTextColor(palette.text.toArgb())
                    setLinkTextColor(palette.accent.toArgb())
                    highlightColor = palette.searchHighlight.toArgb()
                    renderer.markwon.setParsedMarkdown(this, SpannableStringBuilder(rendered))
                }
            },
            update = { view ->
                if (view.appliedQuery != searchQuery) {
                    highlightMatches(view, searchQuery, palette)
                    view.appliedQuery = searchQuery
                    view.invalidate()
                    // Tables create their cell layouts during measurement, after update().
                    view.doOnPreDraw {
                        highlightMatches(view, view.appliedQuery.orEmpty(), palette)
                    }
                }
            },
            modifier = modifier.fillMaxWidth()
        )
    }
}
