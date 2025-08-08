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

    private fun buildHtmlForSvg(svg: String, scale: Double): String {
        // Inline initial scale so the first paint already respects current zoom
        return """
            <html>
              <head>
                <meta charset="UTF-8"/>
                <style>
                  html, body { margin:0; padding:0; }
                  /* Avoid blurry text on some DPIs by letting Chrome do vector scaling */
                  #wrapper { transform-origin: top left; transform: scale($scale); }
                </style>
              </head>
              <body>
                <div id="wrapper">
                  $svg
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
