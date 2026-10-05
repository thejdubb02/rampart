package org.rampart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Desktop
import java.nio.file.Files
import kotlin.math.abs

/** Fifty megabytes cap for rendering in-place previews. */
internal const val MAX_PDF_PREVIEW_SIZE = 50L * 1024 * 1024

/** Maximum number of rendered page images kept in memory simultaneously. */
internal const val MAX_CACHED_PAGES = 6

/**
 * Loads a PDF document from raw bytes.
 *
 * Remote content is never loaded and scripts inside the document are never executed.
 * Returns null if the byte array exceeds the size cap or cannot be parsed.
 */
internal fun loadPdfDocument(bytes: ByteArray): PDDocument? {
    if (bytes.size.toLong() > MAX_PDF_PREVIEW_SIZE) return null
    return runCatching { Loader.loadPDF(bytes) }.getOrNull()
}

/**
 * Renders a single page of a PDF document to an ImageBitmap.
 *
 * Rendering is synchronized on the document to avoid concurrent access during rasterization.
 */
internal fun renderPdfPage(
    document: PDDocument,
    pageIndex: Int,
    dpi: Float = 96f,
): ImageBitmap? {
    if (pageIndex < 0 || pageIndex >= document.numberOfPages) return null
    return runCatching {
        val bufferedImage = synchronized(document) {
            val renderer = PDFRenderer(document)
            renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB)
        }
        bufferedImage.toComposeImageBitmap()
    }.getOrNull()
}

/**
 * Dimensions for a single page in PDF points.
 */
internal data class PdfPageSize(val width: Float, val height: Float) {
    val aspectRatio: Float get() = if (height > 0f) width / height else 0.75f
}

/**
 * Reads page count and page dimensions without rasterizing the pages.
 */
internal fun readPdfPageSizes(document: PDDocument): List<PdfPageSize> {
    val count = document.numberOfPages
    return (0 until count).map { index ->
        val page = runCatching { document.getPage(index) }.getOrNull()
        val box = page?.cropBox ?: page?.mediaBox
        if (box != null && box.width > 0f && box.height > 0f) {
            PdfPageSize(box.width, box.height)
        } else {
            PdfPageSize(612f, 792f)
        }
    }
}

/**
 * Opens a PDF attachment with the default system application.
 *
 * Off the UI thread, since the Defender scan takes a second or two. A file Defender
 * blocks is deleted and simply does not open.
 */
internal fun openInSystemViewer(attachment: Attachment, bytes: ByteArray) = kotlin.concurrent.thread(isDaemon = true) {
    runCatching {
        val dir = Files.createTempDirectory("rampart-pdf-preview")
        dir.toFile().deleteOnExit()
        val rawName = safeFileName(attachment.name).ifBlank { "document.pdf" }
        val finalName = if (rawName.endsWith(".pdf", ignoreCase = true)) rawName else "$rawName.pdf"
        val tempFile = dir.resolve(finalName)
        Defender.check(Files.write(tempFile, bytes))
        tempFile.toFile().deleteOnExit()
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
            desktop.open(tempFile.toFile())
        } else {
            ProcessBuilder("xdg-open", tempFile.toAbsolutePath().toString()).start()
        }
    }
}

/**
 * In-app preview overlay for PDF attachments.
 *
 * Pages are rendered lazily on a background dispatcher at screen resolution.
 * Memory usage is bounded by caching only nearby pages.
 */
@Composable
internal fun PdfPreviewModal(
    attachment: Attachment,
    bytes: ByteArray?,
    onDownload: (Attachment) -> Unit,
    onClose: () -> Unit,
) {
    CoverBody(true)

    var pdfDoc by remember(bytes) { mutableStateOf<PDDocument?>(null) }
    var pageSizes by remember(bytes) { mutableStateOf<List<PdfPageSize>>(emptyList()) }
    var parseAttempted by remember(bytes) { mutableStateOf(false) }

    DisposableEffect(bytes) {
        parseAttempted = false
        val doc = if (bytes != null && bytes.size.toLong() <= MAX_PDF_PREVIEW_SIZE) {
            loadPdfDocument(bytes)
        } else {
            null
        }
        pdfDoc = doc
        pageSizes = doc?.let(::readPdfPageSizes).orEmpty()
        parseAttempted = true
        onDispose {
            doc?.close()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Surface(
            Modifier.align(Alignment.Center).padding(24.dp).fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
        ) {
            val doc = pdfDoc
            val isOversized = attachment.size > MAX_PDF_PREVIEW_SIZE || (bytes != null && bytes.size.toLong() > MAX_PDF_PREVIEW_SIZE)

            if (isOversized) {
                Column(
                    Modifier.padding(24.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "This PDF is larger than 50 MB and cannot be previewed in place.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(attachment) }) { Text("Download") }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                }
            } else if (bytes == null || !parseAttempted) {
                Column(
                    Modifier.padding(24.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading PDF...", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (doc == null || pageSizes.isEmpty()) {
                Column(
                    Modifier.padding(24.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "This PDF could not be opened for preview.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(attachment) }) { Text("Download") }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                }
            } else {
                PdfViewerContent(
                    attachment = attachment,
                    bytes = bytes,
                    document = doc,
                    pageSizes = pageSizes,
                    onDownload = onDownload,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun PdfViewerContent(
    attachment: Attachment,
    bytes: ByteArray,
    document: PDDocument,
    pageSizes: List<PdfPageSize>,
    onDownload: (Attachment) -> Unit,
    onClose: () -> Unit,
) {
    var zoom by remember { mutableStateOf(1.0f) }
    val pageCount = pageSizes.size
    val listState = rememberLazyListState()
    val pageCache = remember(zoom) { mutableStateMapOf<Int, ImageBitmap>() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    safeFileName(attachment.name),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (pageCount == 1) "1 page" else "Page ${listState.firstVisibleItemIndex + 1} of $pageCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.5f) },
                    enabled = zoom > 0.5f,
                ) {
                    Text("-", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = { zoom = 1.0f }) {
                    Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(
                    onClick = { zoom = (zoom + 0.25f).coerceAtMost(2.5f) },
                    enabled = zoom < 2.5f,
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.width(16.dp))

            TextButton(onClick = { openInSystemViewer(attachment, bytes) }) {
                Text("Open with system viewer")
            }
            TextButton(onClick = { onDownload(attachment) }) {
                Text("Download")
            }
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val baseWidth = 640.dp
        val targetWidth = baseWidth * zoom
        val renderDpi = (96f * zoom).coerceIn(48f, 300f)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            items(pageCount, key = { it }) { index ->
                val size = pageSizes[index]
                val cached = pageCache[index]

                LaunchedEffect(index, renderDpi) {
                    if (cached == null) {
                        val rendered = withContext(Dispatchers.Default) {
                            renderPdfPage(document, index, renderDpi)
                        }
                        if (rendered != null) {
                            if (pageCache.size >= MAX_CACHED_PAGES) {
                                val currentVisible = listState.firstVisibleItemIndex
                                val victim = pageCache.keys.maxByOrNull { abs(it - currentVisible) }
                                if (victim != null && abs(victim - currentVisible) > abs(index - currentVisible)) {
                                    pageCache.remove(victim)
                                }
                            }
                            pageCache[index] = rendered
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(targetWidth)
                        .aspectRatio(size.aspectRatio)
                        .shadow(3.dp)
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cached != null) {
                        androidx.compose.foundation.Image(
                            bitmap = cached,
                            contentDescription = "Page ${index + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
