package org.rampart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
    bridge.onHeight = { if (it > 0) height = minOf(it, TALLEST) }
    bridge.onScroll = onScroll
    val panel = remember { JFXPanel() }

    remember(document, density, scale) {
        Platform.runLater {
            val view = (panel.scene?.root as? WebView) ?: WebView().also { fresh ->
                fresh.isContextMenuEnabled = false
                // The page paints its own background; the panel behind it must not add a
                // second one or every message sits on a white card.
                panel.scene = Scene(fresh).apply { fill = javafx.scene.paint.Color.TRANSPARENT }
                fresh.engine.loadWorker.stateProperty().addListener { _, _, state ->
                    if (state != Worker.State.SUCCEEDED) return@addListener
                    (fresh.engine.executeScript("window") as JSObject).setMember("rampart", bridge)
                    fresh.engine.executeScript(WIRING)
                }
            }
            view.zoom = zoomFor(density, scale)
            view.engine.load(asUrl(document))
        }
    }

    SwingPanel(
        background = Color.Transparent,
        factory = { panel },
        modifier = modifier.fillMaxWidth().height(height.dp),
    )
}

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
internal fun zoomFor(density: Float, scale: Float, engineScale: Float = javafxScale()): Double =
    (density / engineScale.coerceAtLeast(0.1f) * scale).coerceIn(0.5f, 3f).toDouble()

/** What JavaFX is drawing at, or 1 where it cannot say, which is every headless run. */
private fun javafxScale(): Float =
    runCatching { javafx.stage.Screen.getPrimary().outputScaleX.toFloat() }.getOrDefault(1f)

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
 * A `data:` URL has no such step. The document is encoded to UTF-8 here, base64 makes it
 * safe to be a URL, and the charset is stated in the URL itself, so what WebKit decodes is
 * the bytes this function produced. Verified against a 65 KB message: same content, same
 * measured height, and the emoji intact.
 *
 * The page's own content security policy is in a meta tag and applies either way.
 */
internal fun asUrl(document: String): String =
    "data:text/html;charset=utf-8;base64," +
        Base64.getEncoder().encodeToString(document.toByteArray(Charsets.UTF_8))

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
  function tell() {
    // A measurement taken before the panel has a real width is a measurement of a
    // collapsed layout, and it is worse than no measurement because it is then the height
    // of the panel forever. A newsletter is nested tables: in a pane one pixel wide every
    // one of them is one pixel wide, the cells report their padding and nothing else, and
    // the whole message came out as a 140 pixel strip of its own background colour with
    // no card, no heading and no text in it.
    if (window.innerWidth < 40) return;
    window.rampart.height(document.documentElement.scrollHeight);
  }
  tell();
  window.addEventListener('load', tell);
  // The viewport widening from nothing to the pane is the event that matters, and it is
  // the one a ResizeObserver on the document does not always see.
  window.addEventListener('resize', tell);
  if (window.ResizeObserver) new ResizeObserver(tell).observe(document.documentElement);
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
