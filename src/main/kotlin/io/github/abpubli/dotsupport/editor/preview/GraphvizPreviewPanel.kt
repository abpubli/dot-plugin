package io.github.abpubli.dotsupport.editor.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.abpubli.dotsupport.external.DotExecutionResult
import io.github.abpubli.dotsupport.external.GRAPHVIZ_ISSUE_PATTERN
import io.github.abpubli.dotsupport.external.runDotCommand
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import javax.swing.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/**
 * JCEF-based preview panel for Graphviz DOT (SVG).
 * Renders SVG from `dot -Tsvg` inside a browser, with JS-based scaling.
 */
class GraphvizPreviewPanel : JPanel(BorderLayout()), Disposable {

    private companion object {
        private val LOG = Logger.getInstance(GraphvizPreviewPanel::class.java)
        private const val MAX_CONCISE_MESSAGE_LENGTH = 150
    }

    private var disposed = false

    // Zoom is applied via CSS transform on a wrapper div in the injected HTML.
    private var scale: Double = 1.0

    fun setZoomPercent(percent: Double) {
        scale = percent / 100.0
        // Apply to currently loaded page (no reload).
        val js = "var w=document.getElementById('wrapper'); if(w){w.style.transform='scale('+$scale+')';}"
        browser?.cefBrowser?.executeJavaScript(js, browser?.cefBrowser?.url, 0)
    }

    @Volatile
    private var lastRenderingTask: Future<*>? = null

    // Keeping the components used to show messages, same UX as before
    private val statusTextArea: JTextArea = JTextArea()
    private val statusScrollPane: JBScrollPane

    private var lastRenderedText: String? = null

    @Volatile
    private var renderingInProgress = false

    @Volatile
    private var pendingText: String? = null

    private var browser: JBCefBrowser? = null
    private val browserScroll: JBScrollPane

    init {
        // Browser
        browser = JBCefBrowser()
        browserScroll = JBScrollPane(browser!!.component)
        add(browserScroll, BorderLayout.CENTER)

        // Status area
        statusTextArea.isEditable = false
        statusTextArea.isFocusable = true
        statusTextArea.wrapStyleWord = false
        statusTextArea.lineWrap = false
        statusTextArea.border = JBUI.Borders.empty(3, 5)
        statusTextArea.background = UIUtil.getPanelBackground()
        statusTextArea.foreground = JBUI.CurrentTheme.Label.foreground()

        statusScrollPane = JBScrollPane(statusTextArea)
        statusScrollPane.border = null
        statusScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        statusScrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        add(statusScrollPane, BorderLayout.SOUTH)

        showStatus("Waiting for data...")
    }

    fun triggerUpdate(dotText: String, force: Boolean = false) {
        LOG.trace("triggerUpdate called. Force: $force, New text length: ${dotText.length}, Last text length: ${lastRenderedText?.length}")
        if (!force && dotText == lastRenderedText) {
            LOG.trace("Skipping render: Text unchanged and force=false.")
            return
        }

        if (renderingInProgress) {
            pendingText = dotText
            LOG.debug("Skipping triggerUpdate: previous rendering still in progress.")
            return
        }
        renderingInProgress = true
        pendingText = null

        LOG.trace("Proceeding with preview update.")
        showStatus("Rendering...")
        lastRenderingTask?.cancel(true)

        lastRenderingTask = ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (Thread.currentThread().isInterrupted) return@executeOnPooledThread

                val executionResult: DotExecutionResult = runDotCommand(dotText, "svg", 15)

                if (Thread.currentThread().isInterrupted) return@executeOnPooledThread
                if (executionResult.executionError != null) throw executionResult.executionError
                if (executionResult.timedOut) throw TimeoutException("Graphviz 'dot' rendering timed out.")

                lastRenderedText = dotText

                val svgBytes = executionResult.outputBytes
                val errors = executionResult.errorOutput
                val exitCode = executionResult.exitCode
                val thisTaskFuture = lastRenderingTask

                SwingUtilities.invokeLater {
                    if (thisTaskFuture != this@GraphvizPreviewPanel.lastRenderingTask || thisTaskFuture?.isCancelled == true) {
                        LOG.debug("UI update skipped because task was cancelled or superseded by a newer one.")
                        return@invokeLater
                    }

                    var errorMessageToDisplay: String? = null
                    if (!errors.isNullOrBlank()) {
                        val concise = parseConciseErrorMessage(errors)
                        errorMessageToDisplay = if (exitCode != 0) "$errors (Exit code: $exitCode)" else errors
                        LOG.warn("Graphviz stderr: $concise")
                    } else if (exitCode != 0) {
                        errorMessageToDisplay = "Graphviz 'dot' failed with exit code $exitCode."
                        LOG.warn(errorMessageToDisplay)
                    }

                    if (svgBytes != null && svgBytes.isNotEmpty()) {
                        val html = buildHtmlForSvg(String(svgBytes, StandardCharsets.UTF_8), scale)
                        loadHtml(html)
                        if (!errors.isNullOrBlank()) {
                            showStatus("Completed (with warnings)")
                        } else {
                            showStatus("Rendering completed successfully")
                        }
                    } else {
                        if (!errorMessageToDisplay.isNullOrBlank()) {
                            showError(errorMessageToDisplay)
                        } else {
                            showError("Rendering produced no SVG or failed silently.")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                    LOG.debug("Direct 'dot' execution cancelled/interrupted.", e)
                    return@executeOnPooledThread
                }
                LOG.error("Error executing or processing 'dot' command for preview.", e)
                val thisTaskFuture = lastRenderingTask
                SwingUtilities.invokeLater {
                    if (thisTaskFuture != this@GraphvizPreviewPanel.lastRenderingTask || thisTaskFuture?.isCancelled == true) {
                        LOG.debug("Error UI update skipped because task was cancelled or superseded by a newer one.")
                        return@invokeLater
                    }
                    showError("Failed to run/process Graphviz 'dot': ${e.message}")
                }
            } finally {
                renderingInProgress = false
                pendingText?.let {
                    pendingText = null
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisplayable && !isDisposed()) triggerUpdate(it)
                    }
                }
            }
        }
    }

    /**
     * Minimal, defensive SVG sanitizer:
     * - removes elements: script, foreignObject, iframe, object, embed, audio, video, link, meta
     * - removes all attributes beginning with “on” (onload, onclick, …)
     * - neutralizes javascript: in href/xlink:href/src/style (url(...))
     * - blocks external URLs (http/https/file) in href/xlink:href/src
     * - removes <style> completely (simplest) – if you want to keep styles from Graphviz, replace this with the url() filter
     *
     * Note: no dependencies – uses DOM from JDK. We do not parse DTD (XXE off).
     */
    private fun sanitizeSvg(input: String): String {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = true
        }
        val builder = dbf.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(input)))

        val dangerousTags = setOf(
            "script", "foreignObject", "iframe", "object", "embed",
            "audio", "video", "link", "meta", "base"
        )

        fun isDangerousElement(el: Element): Boolean {
            val lname = el.localName?.lowercase() ?: el.nodeName.lowercase()
            return lname in dangerousTags
        }

        fun removeNode(node: Node) {
            node.parentNode?.removeChild(node)
        }

        // DFS on tree and cleaning
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                val next = child.nextSibling

                if (child.nodeType == Node.ELEMENT_NODE) {
                    val el = child as Element

                    if (isDangerousElement(el)) {
                        removeNode(el)
                        child = next
                        continue
                    }

                    // Remove support for built-in events (onload, onclick, etc.)
                    val toRemove = mutableListOf<String>()
                    for (i in 0 until el.attributes.length) {
                        val attr = el.attributes.item(i)
                        val name = attr.nodeName
                        if (name.startsWith("on", ignoreCase = true)) {
                            toRemove += name
                        }
                    }
                    toRemove.forEach { el.removeAttribute(it) }

                    // Neutralize dangerous URLs in href/xlink:href/src
                    fun purgeUrlAttr(attrName: String) {
                        if (el.hasAttribute(attrName)) {
                            val v = el.getAttribute(attrName).trim()
                            val low = v.lowercase()
                            val isExternal = low.startsWith("http:") || low.startsWith("https:") || low.startsWith("file:")
                            val isJs = low.startsWith("javascript:")
                            if (isExternal || isJs) {
                                el.removeAttribute(attrName)
                            }
                        }
                    }
                    purgeUrlAttr("href")
                    purgeUrlAttr("xlink:href")
                    purgeUrlAttr("src")

                    // Simple neutralization of potential url() in the style attribute
                    if (el.hasAttribute("style")) {
                        val style = el.getAttribute("style")
                        val sanitized = style.replace(Regex("url\\s*\\(.*?\\)", RegexOption.IGNORE_CASE), "none")
                        el.setAttribute("style", sanitized)
                    }
                }

                if (child != null) walk(child)
                child = next
            }
        }

        // Remove the entire <style> (the easiest way). If you want to keep the styles, convert it to a url() filter.
        val styleNodes = doc.getElementsByTagName("style")
        // Note: LiveNodeList – we remove from the end
        for (i in styleNodes.length - 1 downTo 0) {
            val n = styleNodes.item(i)
            n.parentNode?.removeChild(n)
        }

        walk(doc.documentElement)

        return serializeXml(doc)
    }

    private fun serializeXml(doc: Document): String {
        val tf = TransformerFactory.newInstance()
        val transformer = tf.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.INDENT, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val sw = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(sw))
        return sw.toString()
    }

    private fun buildHtmlForSvg(svg: String, scale: Double): String {
        val cleanSvg = try {
            sanitizeSvg(svg)
        } catch (e: Exception) {
            LOG.warn("SVG sanitization failed, falling back to raw SVG: ${e.message}", e)
            svg
        }

        // Very restrictive CSP: no scripts, objects, frames, connections.
        // We leave inline style for our transform: scale(...).
        val csp = listOf(
            "default-src 'none'",
            "script-src 'none'",
            "style-src 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'none'",
            "connect-src 'none'",
            "frame-src 'none'",
            "object-src 'none'",
            "base-uri 'none'",
            // additional sandbox at the document level
            "sandbox"
        ).joinToString("; ")

        return """
        <html>
          <head>
            <meta charset="UTF-8"/>
            <meta http-equiv="Content-Security-Policy" content="$csp">
            <style>
              html, body { margin:0; padding:0; overflow:auto; }
              #wrapper { transform-origin: top left; transform: scale($scale); }
              /* Opcjonalnie: zapobiec klikalnym linkom, gdyby zostały */
              svg a { pointer-events: none; }
            </style>
          </head>
          <body>
            <div id="wrapper">
              $cleanSvg
            </div>
          </body>
        </html>
    """.trimIndent()
    }

    private fun loadHtml(html: String) {
        // JBCefBrowser#loadHTML injects a temporary url base; good enough for raw SVG inline
        browser?.loadHTML(html)
    }

    fun isDisposed(): Boolean = disposed

    override fun dispose() {
        LOG.debug("Disposing GraphvizPreviewPanel (JCEF)")
        try {
            lastRenderingTask?.cancel(true)
        } catch (_: Throwable) { }
        lastRenderingTask = null
        try {
            browser?.dispose()
        } catch (_: Throwable) { }
        browser = null
        lastRenderedText = null
        disposed = true
    }

    /** Show error in the status area (kept from original UX). */
    fun showError(message: String) {
        statusTextArea.text = message
        statusTextArea.foreground = UIUtil.getErrorForeground()
        statusTextArea.background = UIUtil.getPanelBackground()
        statusTextArea.caretPosition = 0
        statusScrollPane.isVisible = true
        statusScrollPane.revalidate()
        statusScrollPane.repaint()
        this.revalidate()
        this.repaint()
    }

    /** Show status in the status area (kept from original UX). */
    fun showStatus(message: String) {
        statusTextArea.text = message
        statusTextArea.foreground = JBUI.CurrentTheme.Label.foreground()
        statusTextArea.background = UIUtil.getPanelBackground()
        statusTextArea.caretPosition = 0
        statusScrollPane.isVisible = true
        statusScrollPane.revalidate()
        statusScrollPane.repaint()
    }

    /** Parse concise message from Graphviz stderr (unchanged). */
    private fun parseConciseErrorMessage(stderr: String?): String {
        if (stderr == null) return "Unknown Graphviz error"
        val firstIssueLine = stderr.lines().find {
            val t = it.trim()
            t.startsWith("Error:", true) || t.startsWith("Warning:", true)
        }
        if (firstIssueLine != null) {
            val m = GRAPHVIZ_ISSUE_PATTERN.matcher(firstIssueLine.trim())
            if (m.find()) {
                val type = m.group(1) ?: "Issue"
                val lineNum = m.group(2)
                val details = m.group(3)?.trim()?.take(MAX_CONCISE_MESSAGE_LENGTH - 30)
                    ?: "details unavailable"
                return "$type on line $lineNum: $details"
            }
            return firstIssueLine.trim().take(MAX_CONCISE_MESSAGE_LENGTH)
        }
        return stderr.trim().take(MAX_CONCISE_MESSAGE_LENGTH)
    }
}
