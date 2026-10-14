package dev.shin.codewalk.ui

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/**
 * Renders a step body (already converted to HTML by [Markdown]). Two implementations:
 * [JcefBodyView] (IDE-embedded Chromium + bundled mermaid.js, renders ```mermaid diagrams) and
 * [SwingBodyView] (plain JEditorPane fallback for environments without JCEF, e.g. some remote-dev setups).
 */
interface BodyView {
    val component: JComponent
    fun setHtml(innerHtml: String)

    companion object {
        /** `onLink` receives the raw href (`walk:line:…`, `https://…`). */
        fun create(parent: Disposable, onLink: (String) -> Unit): BodyView {
            // The JCEF plugin is an optional dependency: its classes may be absent (NoClassDefFoundError)
            // or present but unusable (isSupported false). Either way, fall back to Swing.
            val jcef = runCatching { JBCefApp.isSupported() }.getOrDefault(false)
            return if (jcef) runCatching { JcefBodyView(parent, onLink) as BodyView }.getOrElse { SwingBodyView(onLink) }
            else SwingBodyView(onLink)
        }

        fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
        val codeBg get() = JBColor(0xF0F0F0, 0x2B2D30)
        val link get() = JBColor.namedColor("Link.activeForeground", JBColor(0x2470B3, 0x589DF6))
    }
}

class SwingBodyView(onLink: (String) -> Unit) : BodyView {
    private val pane = JEditorPane("text/html", "")

    init {
        pane.isEditable = false
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.border = JBUI.Borders.empty(8, 12)
        pane.background = UIUtil.getPanelBackground()
        pane.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) onLink(e.description ?: e.url?.toString() ?: "")
        }
    }

    override val component: JComponent get() = com.intellij.ui.components.JBScrollPane(pane)

    override fun setHtml(innerHtml: String) {
        pane.text = css() + innerHtml
        pane.caretPosition = 0
    }

    private fun css(): String {
        val font = UIUtil.getLabelFont()
        return "<style>" +
            "body{font-family:'${font.family}';font-size:${font.size}pt;color:${BodyView.hex(UIUtil.getLabelForeground())};}" +
            "a{color:${BodyView.hex(BodyView.link)};text-decoration:none;}" +
            "h3{margin:0 0 8px 0;} p{margin:0 0 8px 0;} ul{margin:0 0 8px 16px;} li{margin:2px 0;}" +
            "code{font-family:'${UIUtil.getFontWithFallback("JetBrains Mono", 0, font.size).family}';background:${BodyView.hex(BodyView.codeBg)};}" +
            "pre{background:${BodyView.hex(BodyView.codeBg)};padding:6px;margin:0 0 8px 0;}" +
            "</style>"
    }
}

/**
 * One page is loaded once (CSS + mermaid.js inlined); every step change only swaps `#body`
 * through `window.__setBody(html)` and re-runs mermaid on the new `pre.mermaid` blocks.
 * Link clicks are routed back to Kotlin through a [JBCefJSQuery].
 */
class JcefBodyView(parent: Disposable, private val onLink: (String) -> Unit) : BodyView {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val gson = Gson()

    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    init {
        Disposer.register(parent, browser)
        Disposer.register(parent, query)
        query.addHandler { href -> onLink(href); null }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                // Bridge: page calls window.__walk(href) → JBCefJSQuery → onLink.
                cefBrowser.executeJavaScript("window.__walk = function(href){ ${query.inject("href")} };", cefBrowser.url, 0)
                ready = true
                pending?.let { pending = null; setHtml(it) }
            }
        }, browser.cefBrowser)
        browser.loadHTML(page())
    }

    override val component: JComponent get() = browser.component

    override fun setHtml(innerHtml: String) {
        if (!ready) { pending = innerHtml; return }
        browser.cefBrowser.executeJavaScript("window.__setBody(${gson.toJson(innerHtml)});", browser.cefBrowser.url, 0)
    }

    private fun page(): String {
        val font = UIUtil.getLabelFont()
        val mono = UIUtil.getFontWithFallback("JetBrains Mono", 0, font.size).family
        val dark = !JBColor.isBright()
        val bg = BodyView.hex(UIUtil.getPanelBackground())
        val fg = BodyView.hex(UIUtil.getLabelForeground())
        val mermaidJs = JcefBodyView::class.java.getResource("/web/mermaid.min.js")?.readText() ?: ""
        return """
<!doctype html><html><head><meta charset="utf-8">
<style>
  html,body{margin:0;padding:0;background:$bg;color:$fg;font-family:'${font.family}',sans-serif;font-size:${font.size + 1}px;line-height:1.55;}
  #body{padding:10px 14px 16px 14px;}
  a{color:${BodyView.hex(BodyView.link)};text-decoration:none;} a:hover{text-decoration:underline;}
  h1,h2,h3,h4{margin:0 0 10px 0;line-height:1.3;} h3{font-size:1.15em;}
  p{margin:0 0 10px 0;} ul,ol{margin:0 0 10px 20px;padding:0;} li{margin:3px 0;}
  code{font-family:'$mono',monospace;font-size:0.92em;background:${BodyView.hex(BodyView.codeBg)};padding:1px 4px;border-radius:3px;}
  pre{font-family:'$mono',monospace;background:${BodyView.hex(BodyView.codeBg)};padding:8px 10px;border-radius:4px;margin:0 0 10px 0;overflow:auto;}
  pre.mermaid{background:transparent;padding:4px 0;text-align:center;}
  pre.mermaid svg{max-width:100%;height:auto;}
  table{border-collapse:collapse;margin:0 0 10px 0;} td,th{border:1px solid ${BodyView.hex(BodyView.codeBg)};padding:3px 8px;}
</style>
<script>$mermaidJs</script>
<script>
  mermaid.initialize({ startOnLoad: false, theme: ${if (dark) "'dark'" else "'neutral'"}, securityLevel: 'strict',
    fontFamily: '${font.family}', flowchart: { useMaxWidth: true, htmlLabels: true } });
  window.__setBody = async function (html) {
    const b = document.getElementById('body');
    b.innerHTML = html;
    window.scrollTo(0, 0);
    try { await mermaid.run({ querySelector: '#body pre.mermaid' }); }
    catch (e) { console.error('mermaid', e); }
  };
  document.addEventListener('click', function (e) {
    const a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;
    e.preventDefault();
    if (window.__walk) window.__walk(a.getAttribute('href') || '');
  });
</script>
</head><body><div id="body"></div></body></html>
"""
    }
}
