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
import androidx.compose.ui.unit.dp
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import netscape.javascript.JSObject
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
    // Grows to whatever the message turns out to be, so the pane scrolls rather than the
    // message scrolling inside a box inside the pane.
    var height by remember { mutableStateOf(160) }
    // Held here rather than made in the factory: JavaFX calls it from the JS side and keeps
    // only a weak reference, so anything it can collect stops being callable a minute in.
    val bridge = remember { WebBridge() }
    bridge.onLink = onLink
    bridge.onHeight = { if (it in 1..40_000) height = it }
    bridge.onScroll = onScroll
    val panel = remember { JFXPanel() }

    remember(document) {
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
            view.engine.loadContent(document, "text/html")
        }
    }

    SwingPanel(
        background = Color.Transparent,
        factory = { panel },
        modifier = modifier.fillMaxWidth().height(height.dp),
    )
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
private val WIRING = """
(function () {
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    e.preventDefault();
    window.rampart.open(a.getAttribute('href'));
  }, true);
  window.addEventListener('wheel', function (e) {
    // A line at a time and a page at a time are both reported here, so they are turned
    // into pixels before they leave. Otherwise a mouse that reports lines moves three.
    var step = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? 400 : 1);
    window.rampart.scroll(e.deltaY * step);
  }, { passive: true });
  function tell() { window.rampart.height(document.documentElement.scrollHeight); }
  tell();
  window.addEventListener('load', tell);
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
