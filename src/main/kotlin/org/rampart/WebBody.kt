package org.rampart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.util.Base64
/**
 * A message drawn by a real engine, sitting in the Compose window like any other component.
 *
 * JavaFX's WebKit, reached through a [JFXPanel]. The alternative was Chromium through JCEF,
 * which is three times the download for accuracy no email needs, and the one after that was
 * carrying on drawing HTML by hand, which is what this replaces. See [emailDocument] for
 * what reaches it and what does not.
 *
 * It is a heavyweight component in a Compose scene, so interop blending has to be on or it
 * draws over everything above it when the pane scrolls: see [enableWebBody].
 */
@Composable
internal fun WebBody(
    document: String,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit,
    /**
     * A turn of the wheel over the message, in pixels, for whatever is scrolling it.
     *
     * The engine is a real component and swallows its own input, so with nothing here the
     * pane stops scrolling the moment the pointer is over the message, which is most of the
     * pane. The page does not scroll itself: it is exactly as tall as its content, and the
     * scrolling belongs to the pane it sits in.
     */
    onScroll: (Float) -> Unit = {},
    /**
     * The engine loaded the document and drew nothing out of it.
     *
     * Not a diagnosis, a report. This has now happened four separate ways, none of which
     * logged anything: a string the engine re-encoded into nothing, a URL past a size
     * limit nobody documents, a height that came back through a bridge that was not
     * attached, and a height in the wrong unit. The reader's answer to all four is the
     * same, so this says only that the message is not on screen and lets the caller draw
     * it the other way.
     */
    onBlank: () -> Unit = {},
) {
    /*
     * Grows to whatever the message turns out to be, so the pane scrolls rather than the
     * message scrolling inside a box inside the pane. **Up to a point**, and the point is
     * the reason this is capped rather than free.
     *
     * The panel underneath is a heavyweight AWT component, and asking for one as tall as a
     * long mail thread asks the graphics stack for a single surface taller than it will
     * make: past roughly eight thousand physical pixels it stops being drawn and the
     * message is a blank box. At 150% scaling that is a document about 5,400 points tall,
     * which a quoted thread reaches easily and a newsletter does not, so it looked like
     * particular messages were broken rather than long ones.
     *
     * Past the cap the page scrolls itself. [WIRING] works out which of the two is
     * happening rather than being told, so there is one rule and nothing to keep in step.
     */
    var height by remember(document) { mutableStateOf(160) }
    /*
     * How big a pixel is, according to the rest of the application.
     *
     * The engine has its own answer and it is not always the same one. Where they
     * disagree the message is drawn visibly smaller than the interface around it, which is
     * what happens on a scaled display: the window is laid out at the system's scale and
     * the page is laid out at the engine's. Dividing one by the other makes a message the
     * same size as everything else on any display, and then [Settings.messageScale] is a
     * preference on top of that rather than a correction somebody has to discover.
     */
    val density = LocalDensity.current.density
    val scale = Settings.messageScale()
    // Held here rather than made in the factory: JavaFX calls it from the JS side and keeps
    // only a weak reference, so anything it can collect stops being callable a minute in.
    val bridge = remember { WebBridge() }
    bridge.onLink = onLink
    /*
     * The tallest answer wins, not the latest.
     *
     * The page is asked its height several times, because the one that counts is whichever
     * ask lands after the panel has its real width, and there is no way to know which that
     * will be. Every measurement before that one is of a collapsed layout and is therefore
     * too short, never too tall, so keeping the largest is the whole of the arbitration.
     *
     * Safe to keep across a document because [height] is reset with it: showing the
     * pictures builds a new document, which starts the measuring again from nothing.
     */
    var measured by remember(document) { mutableStateOf(false) }
    bridge.onHeight = {
        if (it > 0) {
            measured = true
            height = maxOf(height, minOf(it, TALLEST))
        }
    }
    /*
     * If nothing ever answers, show the message anyway.
     *
     * Every version of this fault has ended the same way: a panel left at the height it
     * started with, which is a blank strip where the mail should be, with nothing on
     * screen or in a log saying so. The measuring is worth doing because a message sized
     * to its content scrolls with the rest of the pane instead of inside a box. It is not
     * worth a blank message when it fails.
     *
     * So after three seconds of no answer the panel takes the whole pane and the page
     * scrolls itself, which is what webmail does and is always readable. Nothing here
     * needs to know why the measuring did not work.
     */
    LaunchedEffect(document) {
        delay(3_000)
        if (!measured) height = UNMEASURED
    }
    bridge.onScroll = onScroll
    bridge.onBlank = onBlank
    val panel = remember { JFXPanel() }
    /*
     * The measuring of the message before this one, so it can be stopped.
     *
     * It runs for six seconds and the reader moves faster than that. Left running, it goes
     * on asking the engine how tall it is, and the engine now holds a different message, so
     * it answers for that one: the panel takes the previous message's height and, worse,
     * counts as measured, which is what stops the fallback from ever running.
     */
    val ticker = remember { java.util.concurrent.atomic.AtomicReference<javafx.animation.Timeline?>(null) }

    remember(document, density, scale) {
        Platform.runLater {
            /*
             * Writing the document out is the one step here that touches a disk, and a disk
             * that is full or read-only throws. Uncaught it takes the pane down; caught, it
             * is the same answer as every other way this can fail, which is that the block
             * renderer draws the message instead.
             */
            runCatching {
                val view = (panel.scene?.root as? WebView) ?: WebView().also { fresh ->
                    fresh.isContextMenuEnabled = false
                    // The page paints its own background; the panel behind it must not add a
                    // second one or every message sits on a white card.
                    panel.scene = Scene(fresh).apply {
                        fill = javafx.scene.paint.Color.TRANSPARENT
                        // The engine is laid out at the size it is actually shown at.
                        fresh.prefWidthProperty().bind(widthProperty())
                        fresh.prefHeightProperty().bind(heightProperty())
                    }
                    fresh.engine.loadWorker.stateProperty().addListener { _, _, state ->
                        if (state != Worker.State.SUCCEEDED) return@addListener
                        (fresh.engine.executeScript("window") as JSObject).setMember("rampart", bridge)
                        fresh.engine.executeScript(WIRING)
                    }
                }
                val zoom = zoomFor(density, scale, panelScale(panel))
                view.zoom = zoom
                view.engine.load(asUrl(document))
                ticker.getAndSet(measure(view, zoom, { bridge.onHeight(it) }, { bridge.onBlank() }))?.stop()
            }.onFailure { bridge.onBlank() }
        }
    }

    SwingPanel(
        background = Color.Transparent,
        factory = { panel },
        modifier = modifier.fillMaxWidth().height(height.dp),
    )
}

/**
 * Asking the page how tall it is, from here rather than from inside it.
 *
 * **The page used to report its own height through the JavaScript bridge, and that is one
 * more thing that has to work than is needed.** The bridge has to be attached, the
 * injected script has to run, and the page has to be allowed to call back out, and when
 * any of those does not happen there is no error anywhere: the message is simply a short
 * blank box, which is what was being reported. Nothing here needs the page's cooperation.
 * It is one `executeScript` on the thread the engine already runs on.
 *
 * **Multiplied by the zoom, which is the second half of the same fault.** `scrollHeight`
 * is in the page's own pixels and the panel is measured in the window's, and those are
 * only the same thing when the zoom is 1. On a scaled display they are not, so a message
 * was asking for a panel a fraction of the size it was about to draw itself at.
 *
 * Asked repeatedly for a few seconds rather than once, because the answer changes: the
 * pane is laid out after the document loads, pictures decode later still, and the
 * measurement that counts is whichever one lands after all of that. Stops on its own.
 */
private fun measure(view: WebView, zoom: Double, report: (Int) -> Unit, blank: () -> Unit): javafx.animation.Timeline {
    val timeline = javafx.animation.Timeline()
    // Fifteen seconds rather than six. A picture fetched from the sender's own server, which
    // is what agreeing to remote pictures means, can decode long after the document loads,
    // and a measurement that stopped before it did left the message in a panel too short for
    // it with the rest scrolling inside a box.
    timeline.cycleCount = 60
    var ticks = 0
    val tick = javafx.event.EventHandler<javafx.event.ActionEvent> {
        ticks++
        val tall = runCatching {
            (view.engine.executeScript("document.documentElement.scrollHeight") as? Number)?.toDouble()
        }.getOrNull()
        if (tall != null && tall > 0) report((tall * zoom).toInt())
        // Three seconds in, and the engine either will not answer or is answering that it
        // has a document with nothing in it. Asked once rather than every tick, because a
        // page part way through loading is legitimately empty and this is not a race to
        // win: it is the last resort after every ordinary way of getting there has failed.
        if (ticks == 12 && drewNothing(view)) blank()
    }
    timeline.keyFrames.add(javafx.animation.KeyFrame(javafx.util.Duration.millis(250.0), tick))
    timeline.play()
    return timeline
}

/**
 * Whether there is anything on the page a reader would see.
 *
 * Text or a picture. Not the height, which a blank page has plenty of, and not whether the
 * load reported success, which it does for a document that drew nothing.
 */
private fun drewNothing(view: WebView): Boolean = runCatching {
    (view.engine.executeScript(
        "document.body ? document.body.innerText.trim().length + document.images.length : 0"
    ) as? Number)?.toInt()
}.getOrNull().let { it == null || it <= 0 }

/**
 * What to multiply the page by so it comes out the size the rest of the window is.
 *
 * [density] is what Compose lays the window out at and `outputScaleX` is what JavaFX draws
 * at. Usually they agree and this is 1, in which case the only thing left is the reader's
 * own preference. Where they do not, this is the whole correction, and it is worked out
 * rather than configured so there is nothing to keep in step when somebody moves the
 * window to a second monitor at a different scale.
 *
 * Bounded, because a preference that can make a message unreadable in either direction is
 * a setting somebody can break the application with.
 */
internal fun zoomFor(density: Float, scale: Float, engineScale: Float): Double =
    (density / engineScale.coerceAtLeast(0.1f) * scale).coerceIn(0.5f, 3f).toDouble()

/**
 * What this panel's own screen is drawing at, or 1 where it cannot say.
 *
 * **Asked of the panel rather than of the primary screen**, which is the same number only
 * on a machine with one monitor. With a laptop at 150% and a monitor beside it at 100%, the
 * message on whichever of them is not the primary was laid out at the other one's scale:
 * two thirds the size of the interface around it, or half as large again, and either way
 * the layout it was designed for. Java knows which screen a component is on and what that
 * screen's transform is, so nothing here has to guess.
 */
private fun panelScale(panel: JFXPanel): Float = runCatching {
    panel.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()
}.getOrNull()?.takeIf { it > 0f } ?: 1f

/**
 * The document, as something the engine will take the bytes of.
 *
 * **`loadContent` corrupts the text and there is no charset argument that stops it.** A
 * ticket emoji arrived as six replacement characters, and so did every en dash and em dash
 * in every newsletter: the string is re-encoded somewhere between here and WebKit, and a
 * character outside the Basic Multilingual Plane does not survive the trip. Passing
 * `text/html; charset=UTF-8` as the content type does not fix it, it stops the page
 * loading at all, because the type is matched exactly.
 *
 * So the document is written out as UTF-8 bytes and the engine is pointed at the file. The
 * bytes it reads are the bytes written here, and the charset is stated in the document
 * itself, so there is no step in between that can reinterpret anything.
 *
 * **A `data:` URL did the same job and had a ceiling.** A signature with a 700 KB picture
 * in it makes a document of nearly a megabyte, which is a data URL of one and a quarter,
 * and at that size the picture came out as a blank white rectangle on Windows while the
 * text around it was fine. A file has no such limit.
 *
 * **Old ones are swept by age, not by keeping track of the last one.** The previous file
 * was deleted the moment the next was written, which is wrong twice: a thread draws a
 * message per panel, so one panel was deleting the file another was still reading, and a
 * message that rebuilds itself when its pictures arrive deletes the document it is showing
 * while the replacement is still loading. Both come out as a message that is blank
 * sometimes and fine sometimes, which is what was being reported. Nothing written in the
 * last few minutes is touched, and there is no shared bookkeeping left to get wrong.
 */
internal fun asUrl(document: String): String {
    leftovers
    val file = kotlin.io.path.createTempFile("rampart-message-", ".html")
    file.toFile().deleteOnExit()
    java.nio.file.Files.write(file, unpack(document, file).toByteArray(Charsets.UTF_8))
    return file.toUri().toString()
}

/**
 * What a previous run left behind, cleared once when this one first draws a message.
 *
 * **Sweeping by age during the session was wrong, and wrong in the direction that shows.**
 * A picture is a file the page fetches when it needs it, not something read once at load,
 * so a message left open while somebody reads it still depends on its files being there.
 * Sweeping anything older than a few minutes therefore took the pictures out of the message
 * on screen. This run's own files go on exit and are never touched before then; an hour is
 * long enough that a second copy of Rampart started alongside the first cannot lose a
 * message either.
 */
private val leftovers: Unit by lazy<Unit> {
    runCatching {
        val old = System.currentTimeMillis() - 60 * 60 * 1000
        val temp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
        java.nio.file.Files.newDirectoryStream(temp, "rampart-message-*").use { files ->
            files.filter { it.toFile().lastModified() < old }
                .forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } }
        }
    }
}

/**
 * A big picture the message carried, written beside the document instead of inside it.
 *
 * **The same ceiling as the document had, one level down.** A signature with a 700 KB logo
 * in it is a `data:` URI of about nine hundred thousand characters, and past somewhere
 * around there the engine draws a blank white rectangle the size of the picture, with the
 * message around it perfectly fine. Nothing is logged and the load still reports success,
 * so it reads as a message that half arrived. Writing the bytes to a file beside the
 * document and referring to it by name has no such limit, and it takes the document itself
 * from nine hundred thousand characters back to a few thousand.
 *
 * Only the big ones. A small picture inline is one file fewer and nothing is wrong with it.
 */
private fun unpack(document: String, beside: java.nio.file.Path): String {
    val stem = beside.fileName.toString().removeSuffix(".html")
    var n = 0
    return INLINE_PICTURE.replace(document) { match ->
        val bytes = runCatching { Base64.getDecoder().decode(match.groupValues[2]) }.getOrNull()
            ?: return@replace match.value
        val name = "$stem-${++n}." + extensionFor(match.groupValues[1])
        runCatching {
            val file = beside.resolveSibling(name)
            java.nio.file.Files.write(file, bytes)
            file.toFile().deleteOnExit()
        }.getOrElse { return@replace match.value }
        """src="$name""""
    }
}

/**
 * Anything bigger than a placeholder becomes a file.
 *
 * **The threshold used to be a hundred thousand characters, which was an attempt to sit
 * just under a ceiling nobody has documented.** The only size anybody has measured is the
 * one that failed, so a threshold picked from it is a guess, and the cost of guessing low
 * is a few small files in a temporary directory while the cost of guessing high is a blank
 * rectangle where a picture should be. Two thousand characters is about fifteen hundred
 * bytes: big enough to leave the blocked-picture placeholder inline, small enough that no
 * real picture is ever handed over as text again.
 */
private val INLINE_PICTURE =
    Regex("""src="data:(image/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=]{2000,})"""")

/** What to call the file, so the engine knows what it is holding without being told. */
private fun extensionFor(type: String): String =
    when (val sub = type.substringAfter('/').substringBefore('+').lowercase()) {
        "jpeg" -> "jpg"
        else -> sub.filter { it.isLetterOrDigit() }.ifBlank { "img" }
    }


/**
 * Printing the message, through the engine that already knows how to draw it.
 *
 * A fresh offscreen view rather than the one on screen. The one on screen is capped at
 * [TALLEST] and may be scrolled, and a print is the whole message, not the part that fits;
 * loading it again costs nothing worth saving and gets the whole thing.
 *
 * The dialog is the operating system's own, so paper size, printer and range are all
 * already answered. With no printer configured there is no job to make and nothing
 * happens, which is the same as every other application on the machine.
 */
internal fun printDocument(document: String) {
    Platform.runLater {
        val job = javafx.print.PrinterJob.createPrinterJob() ?: return@runLater
        val view = WebView()
        view.engine.loadWorker.stateProperty().addListener { _, _, state ->
            if (state != Worker.State.SUCCEEDED) return@addListener
            // Asked after the load, not before: the dialog shows a page count, and before
            // the load there are no pages to count.
            if (job.showPrintDialog(null)) {
                view.engine.print(job)
                job.endJob()
            }
        }
        view.engine.load(asUrl(document))
    }
}

/**
 * What the page calls back into.
 *
 * Public, and the methods with it, because JavaFX reaches them by reflection from the
 * page's own JavaScript. There is nothing here worth reaching: a link to open and a height
 * to report, and the height is bounded by the caller.
 */
class WebBridge {
    internal var onLink: (String) -> Unit = {}
    internal var onHeight: (Int) -> Unit = {}
    internal var onScroll: (Float) -> Unit = {}
    internal var onBlank: () -> Unit = {}

    fun open(url: String) = onLink(url)

    fun height(px: Int) = onHeight(px)

    fun scroll(dy: Double) = onScroll(dy.toFloat())
}

/**
 * The two things the page is asked to do, injected after it loads rather than written into
 * the document, so nothing script-shaped is ever part of the message's own HTML.
 *
 * A link opens in the reader's browser, never in here: this engine has no address bar, no
 * back button and no profile, and a message that can navigate it is a message that can show
 * you a login page. The height is reported again whenever the page changes size, because a
 * picture finishing decoding is the ordinary reason the first measurement is short.
 */
/**
 * The tallest panel worth asking for, in points.
 *
 * Under the graphics stack's own limit at any scaling anybody runs: 3,000 points is 6,000
 * physical pixels at 200%, comfortably inside the roughly 8,000 where a surface stops
 * being drawn. Well over nine tenths of messages are shorter than this and never notice.
 */
private const val TALLEST = 3_000

/**
 * The panel a message gets when it never said how tall it was.
 *
 * Tall enough to be a message rather than a strip, short enough to sit inside any window
 * somebody has actually opened. The page scrolls itself at this size, so nothing is lost,
 * it is only read in a box rather than in the pane.
 */
private const val UNMEASURED = 720

private val WIRING = """
(function () {
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    e.preventDefault();
    window.rampart.open(a.getAttribute('href'));
  }, true);
  window.addEventListener('wheel', function (e) {
    // A message taller than the panel it was given scrolls itself, and the wheel is left
    // alone to do that. Everything else is exactly as tall as its content, so there is
    // nothing here to scroll and the wheel belongs to the pane outside.
    if (document.documentElement.scrollHeight > window.innerHeight + 1) return;
    // A line at a time and a page at a time are both reported here, so they are turned
    // into pixels before they leave. Otherwise a mouse that reports lines moves three.
    var step = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? 400 : 1);
    window.rampart.scroll(e.deltaY * step);
  }, { passive: true });
  function tell(force) {
    // A measurement taken before the panel has a real width is a measurement of a
    // collapsed layout. A newsletter is nested tables: in a pane a few pixels wide every
    // one of them is a few pixels wide, the cells report their padding and nothing else,
    // and the message comes out as a short strip of its own background colour with no
    // card, no heading and no text in it. Refused rather than reported, because the
    // caller keeps the tallest answer and a wrong small one would be harmless but a
    // wrong large one would not.
    //
    // Except at the end. A pane really can be narrow, and a message that is permanently
    // 160 pixels tall because the guard never let go is a worse fault than the one the
    // guard is for.
    if (!force && window.innerWidth < 40) return;
    window.rampart.height(document.documentElement.scrollHeight);
  }
  // Wrapped, every one of them: a listener is called with its event as the first
  // argument, so passing `tell` itself made `force` an Event, which is truthy, and the
  // guard above was never once applied.
  function again() { tell(false); }
  tell();
  window.addEventListener('load', again);
  window.addEventListener('resize', again);
  if (window.ResizeObserver) new ResizeObserver(again).observe(document.documentElement);
  // A picture finishing is the ordinary reason a first measurement is short.
  Array.prototype.forEach.call(document.images, function (img) {
    img.addEventListener('load', again);
    img.addEventListener('error', again);
  });
  /*
   * And a few times regardless.
   *
   * The panel getting its real width is not an event this page can rely on seeing: it is
   * laid out by the toolkit outside, after the document has already loaded, and whether
   * a resize reaches the page depends on which of the two happened first. That made the
   * fault look random, which is exactly what it is. Asking a few times over the first
   * couple of seconds costs nothing and does not depend on winning the race.
   */
  [60, 200, 500, 1200, 2500].forEach(function (ms) { setTimeout(again, ms); });
  setTimeout(function () { tell(true); }, 4000);
})();
""".trimIndent()

/**
 * Whether the engine can actually be started here.
 *
 * Asked once, by starting one and seeing. There is no way to know from the outside: the
 * native side is missing on a platform we did not package for, and present but unusable
 * with no display, which is every test run and every machine over SSH. The answer decides
 * whether a message is drawn by the engine or by the block renderer, and either way it is
 * drawn, which is the point of asking rather than finding out by crashing on somebody's
 * mail.
 */
internal val webEngineWorks: Boolean by lazy {
    runCatching { JFXPanel(); true }.getOrDefault(false)
}

/**
 * Turns on drawing Swing components into the Compose scene rather than over the top of it.
 *
 * Without it a heavyweight component is a hole punched through the window: it ignores the
 * clip of whatever is scrolling it, so the message draws over the header above it, and
 * every menu, dialog and overlay opens behind it. Set before the first window, because it
 * is read once when the scene is made.
 */
internal fun enableWebBody() {
    System.setProperty("compose.interop.blending", "true")
}
