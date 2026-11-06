package com.chatscroll.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chatscroll.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/** Bounds pass first; no full-resolution decode or file I/O on the UI thread. */
internal fun decodeReaderBitmap(source: String, maxEdge: Int): Bitmap? {
    return try {
        val bytes = if (source.startsWith("data:image/", ignoreCase = true)) {
            val comma = source.indexOf(',')
            if (comma < 0 || !source.substring(0, comma).contains(";base64", ignoreCase = true)) return null
            Base64.decode(source.substring(comma + 1), Base64.DEFAULT)
        } else null
        val file = if (bytes == null) {
            if (source.startsWith("file:", ignoreCase = true)) File(URI(source))
            else File(source).takeIf { it.isAbsolute } ?: return null
        } else null
        fun decode(options: BitmapFactory.Options): Bitmap? = if (bytes != null) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } else {
            file!!.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        decode(BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private data class ReaderBitmapState(val bitmap: Bitmap? = null, val loading: Boolean = true)

@Composable
private fun readerBitmap(source: String, maxEdge: Int): ReaderBitmapState =
    produceState(ReaderBitmapState(), source, maxEdge) {
        value = ReaderBitmapState()
        val bitmap = withContext(Dispatchers.IO) { decodeReaderBitmap(source, maxEdge) }
        value = ReaderBitmapState(bitmap, loading = false)
    }.value

@Composable
internal fun ReaderEmbeddedImage(source: String, onOpen: () -> Unit) {
    val state = readerBitmap(source, 1600)
    val label = stringResource(R.string.reader_open_image)
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = state.bitmap
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = label,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentScale = ContentScale.FillWidth)
            state.loading -> CircularProgressIndicator(Modifier.padding(16.dp))
            else -> Text(stringResource(R.string.reader_image_failed), modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReaderImageViewer(source: String, onDismiss: () -> Unit) {
    // Keep the full source out of rememberSaveable, including old base64 payloads.
    val state = readerBitmap(source, 3072)
    var scale by remember(source) { mutableFloatStateOf(1f) }
    var pan by remember(source) { mutableStateOf(Offset.Zero) }
    ReaderMotionDialog(onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) { dismiss ->
        Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
            val bitmap = state.bitmap
            var revealed by remember(source) { mutableStateOf(false) }
            val imageAlpha by animateFloatAsState(if (revealed) 1f else 0f,
                tween(ReaderMotion.Overlay, easing = ReaderMotion.Easing), label = "viewerImage")
            LaunchedEffect(bitmap) { if (bitmap != null) revealed = true }
            if (bitmap != null) {
                Box(Modifier.fillMaxSize().pointerInput(source) {
                    detectTransformGestures { centroid, movement, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 6f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val relative = centroid - center
                        val candidate = (pan - relative) * (next / scale) + relative + movement
                        val maxX = size.width * (next - 1f) / 2f
                        val maxY = size.height * (next - 1f) / 2f
                        pan = Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
                        scale = next
                    }
                }) {
                    Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.reader_image),
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = imageAlpha
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        }, contentScale = ContentScale.Fit)
                }
            } else if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                Text(stringResource(R.string.reader_image_failed), color = Color.White,
                    modifier = Modifier.align(Alignment.Center))
            }
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    ReaderMotionIconButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); pan = Offset.Zero }) {
                        Icon(Icons.Default.ZoomOut, stringResource(R.string.reader_zoom_out), tint = Color.White)
                    }
                    TextButton(onClick = { scale = 1f; pan = Offset.Zero }) {
                        Text(stringResource(R.string.reader_zoom_reset), color = Color.White)
                    }
                    ReaderMotionIconButton(onClick = { scale = (scale * 1.5f).coerceAtMost(6f) }) {
                        Icon(Icons.Default.ZoomIn, stringResource(R.string.reader_zoom_in), tint = Color.White)
                    }
                    ReaderMotionIconButton(onClick = dismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.reader_close), tint = Color.White)
                    }
                }
            }
        }
    }
}
