package io.github.abpubli.dotsupport.editor.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.abpubli.dotsupport.external.DotExecutionResult
import io.github.abpubli.dotsupport.external.GRAPHVIZ_ISSUE_PATTERN
import io.github.abpubli.dotsupport.external.runDotCommand
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import org.xml.sax.InputSource
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO
import javax.swing.*
import javax.xml.parsers.DocumentBuilderFactory


/**
 * A JPanel implementation responsible for rendering and displaying
 * a preview of Graphviz DOT text as an image.
 * It handles background rendering, displays the image or error messages.
 * Error messages are displayed in a scrollable text area.
 */
class GraphvizPreviewPanel : JPanel(BorderLayout()), Disposable {

    private companion object {
        private val LOG = Logger.getInstance(GraphvizPreviewPanel::class.java)

        // Limit message parsing length for performance/log readability
        private const val MAX_STDERR_PARSE_LENGTH = 5000
        private const val MAX_CONCISE_MESSAGE_LENGTH = 150
    }

    private var disposed = false
    private var scale: Float = 1.0f

    fun setZoomPercent(percent: Float) {
        scale = percent / 100f
        if (lastRenderedText != null) {
            triggerUpdate(lastRenderedText!!, force = true)
        }
    }

    @Volatile
    private var lastRenderingTask: Future<*>? = null
    private val imageLabel: JLabel = JLabel()

    private val statusTextArea: JTextArea = JTextArea()

    private val imageScrollPane: JBScrollPane // Renamed for clarity
    private val statusScrollPane: JBScrollPane // Added scroll pane for status text area

    private var lastRenderedText: String? = null

    @Volatile
    private var errorToDisplay: Any? = null // Keep this if used elsewhere

    init {
        imageLabel.horizontalAlignment = SwingConstants.CENTER
        imageLabel.verticalAlignment = SwingConstants.CENTER
        imageScrollPane = JBScrollPane(imageLabel)
        add(imageScrollPane, BorderLayout.CENTER)

        statusTextArea.isEditable = false
        statusTextArea.isFocusable = true
        statusTextArea.wrapStyleWord = false // Disable wrapping to force horizontal scrollbar
        statusTextArea.lineWrap = false    // Disable wrapping
        statusTextArea.border = JBUI.Borders.empty(3, 5) // Add some padding
        // Match background/foreground to look like a label initially
        statusTextArea.background = UIUtil.getPanelBackground() // Use panel background
        statusTextArea.foreground = JBUI.CurrentTheme.Label.foreground()

        statusScrollPane = JBScrollPane(statusTextArea)
        statusScrollPane.border = null // Remove border from the scroll pane itself
        statusScrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        statusScrollPane.verticalScrollBarPolicy =
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED // Usually not needed for single line status

        add(statusScrollPane, BorderLayout.SOUTH)

        showStatus("Waiting for data...")
    }

    @Volatile
    private var renderingInProgress = false

    @Volatile
    private var pendingText: String? = null

    fun convertSvgLengthToPixels(length: String, dpi: Float = 96f): Float {
        val regex = Regex("""([0-9.]+)\s*([a-z%]*)""")
        val match = regex.matchEntire(length.trim()) ?: return 0f

        val value = match.groupValues[1].toFloatOrNull() ?: return 0f
        val unit = match.groupValues[2].lowercase()

        val multiplier = when (unit) {
            "", "px" -> 1.0f
            "pt" -> dpi / 72f
            "in" -> dpi
            "cm" -> dpi / 2.54f
            "mm" -> dpi / 25.4f
            "pc" -> dpi / 6f
            "%" -> 1f // nie wiadomo do czego się odnosi — musisz pominąć
            else -> 1f // default fallback
        }

        return value * multiplier
    }

    fun renderSvgToBufferedImage(svgBytes: ByteArray, scale: Float = 1.0f): BufferedImage {
        var renderedImage: BufferedImage? = null

        val transcoder = object : ImageTranscoder() {
            override fun createImage(w: Int, h: Int): BufferedImage {
                return UIUtil.createImage(null, w, h, BufferedImage.TYPE_INT_ARGB)
            }

            override fun writeImage(img: BufferedImage, out: TranscoderOutput?) {
                renderedImage = img
            }
        }

        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) // NIE true!
        }
        val builder = dbf.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val document = builder.parse(ByteArrayInputStream(svgBytes))
        val svgElement = document.documentElement
        val widthAttr = svgElement.getAttribute("width")
        val widthSvg = convertSvgLengthToPixels(widthAttr)

        val input = TranscoderInput(ByteArrayInputStream(svgBytes))
        transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, widthSvg * scale)
        transcoder.transcode(input, null)

        return renderedImage ?: throw IllegalStateException("Batik SVG rendering failed")
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
        LOG.debug("Setting status to 'Rendering...'")
        showStatus("Rendering...") // Update status area
        lastRenderingTask?.cancel(true)
        LOG.debug("Previous rendering task cancelled (if running).")

        errorToDisplay = null

        lastRenderingTask = ApplicationManager.getApplication().executeOnPooledThread {
            LOG.debug("Background thread [${Thread.currentThread().name}]: Starting direct 'dot -Tpng' execution.")

            var executionResult: DotExecutionResult? = null
            try {
                if (Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().name}]: Task cancelled before 'dot' execution.")
                    return@executeOnPooledThread
                }

                executionResult = runDotCommand(dotText, "svg", 15)

                if (Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().name}]: Task cancelled after 'dot' execution.")
                    return@executeOnPooledThread
                }

                if (executionResult.executionError != null) throw executionResult.executionError
                if (executionResult.timedOut) throw TimeoutException("Graphviz 'dot' rendering timed out.")

                lastRenderedText = dotText

                val svgBytes = executionResult.outputBytes
                val errors = executionResult.errorOutput
                val exitCode = executionResult.exitCode
                val thisTaskFuture = lastRenderingTask

                LOG.debug("Direct 'dot' execution finished. Exit code: $exitCode, Stderr present: ${!errors.isNullOrBlank()}, Output bytes: ${svgBytes?.size}")

                SwingUtilities.invokeLater {
                    if (thisTaskFuture != this@GraphvizPreviewPanel.lastRenderingTask || thisTaskFuture?.isCancelled == true) {
                        LOG.debug("UI update skipped because task was cancelled or superseded by a newer one.")
                        return@invokeLater
                    }

                    var errorMessageToDisplay: String? = null
                    if (!errors.isNullOrBlank()) {
                        errorMessageToDisplay = parseConciseErrorMessage(errors) // Use the concise message first
                        if (exitCode != 0) errorMessageToDisplay += " (Exit code: $exitCode)"
                        // For the text area, we might want to display the *full* error
                        // Let's store the full error temporarily
                        val fullErrorDetails = errors
                        LOG.warn("Graphviz stderr reported during rendering: $errors")
                        // Optionally, update the text area with full details later or keep the concise one
                        errorMessageToDisplay = fullErrorDetails // Display full error in text area

                    } else if (exitCode != 0) {
                        errorMessageToDisplay = "Graphviz 'dot' failed with exit code $exitCode."
                        LOG.warn(errorMessageToDisplay)
                    }

                    var imageDisplayed = false
                    if (svgBytes != null && svgBytes.isNotEmpty()) {
                        try {
                            val image = renderSvgToBufferedImage(svgBytes, scale)
                            if (image != null) {//todo always true
                                updateImage(image)
                                imageDisplayed = true
                                if (!errors.isNullOrBlank()) {
                                    showStatus("Completed (with warnings)") // Keep status concise
                                    // Optionally log full warnings or provide another way to view them
                                }
                            } else {
                                LOG.warn("Rendered image data could not be decoded.")
                                if (errorMessageToDisplay == null) errorMessageToDisplay =
                                    "Failed to decode rendered image."
                            }
                        } catch (e: Exception) {
                            LOG.error("Error decoding rendered image data.", e)
                            if (errorMessageToDisplay == null) errorMessageToDisplay =
                                "Error decoding image: ${e.message}"
                        }
                    }

                    if (!imageDisplayed) {
                        if (!errorMessageToDisplay.isNullOrBlank()) {
                            showError(errorMessageToDisplay) // Show full error in text area
                        } else {
                            showError("Rendering produced no image or failed silently.")
                        }
                    }
                }

            } catch (e: Exception) {
                if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                    LOG.debug(
                        "Background thread [${Thread.currentThread().name}]: Direct 'dot' execution cancelled/interrupted in outer catch.",
                        e
                    )
                    return@executeOnPooledThread
                }
                LOG.error("Error executing or processing 'dot' command for preview.", e)
                val finalError = e
                val thisTaskFuture = lastRenderingTask
                SwingUtilities.invokeLater {
                    if (thisTaskFuture != this@GraphvizPreviewPanel.lastRenderingTask || thisTaskFuture?.isCancelled == true) {
                        LOG.debug("Error UI update skipped because task was cancelled or superseded by a newer one.")
                        return@invokeLater
                    }
                    // Show execution error (e.g., dot not found, timeout)
                    showError("Failed to run/process Graphviz 'dot': ${finalError.message}")
                }
            } finally {
                renderingInProgress = false
                pendingText?.let {
                    pendingText = null
                    LOG.debug("Running pending queued update after rendering completion.")
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisplayable && !isDisposed()) {
                            triggerUpdate(it)
                        }
                    }
                }
            }
        }
    }

    fun isDisposed(): Boolean = disposed

    override fun dispose() {
        LOG.debug("Disposing GraphvizPreviewPanel")
        lastRenderingTask?.cancel(true)
        lastRenderingTask = null
        imageLabel.icon = null
        lastRenderedText = null
        disposed = true
    }

    private fun updateImage(image: BufferedImage) {
        imageLabel.icon = ImageIcon(image)
        imageLabel.text = null
        showStatus("Rendering completed successfully") // Keep using showStatus for consistency
        imageScrollPane.revalidate()
        imageScrollPane.repaint()
        LOG.debug("Image updated in the preview panel.")
        this.revalidate()
        this.repaint()
    }

    /**
     * Updates the UI to display an error message in the status text area.
     * Clears any existing image from the display. Must be called on EDT.
     * @param message The error message to display.
     */
    fun showError(message: String) {
        imageLabel.icon = null
        imageLabel.text = null
        statusTextArea.text = message
        statusTextArea.foreground = UIUtil.getErrorForeground() // Use standard error color
        statusTextArea.background = UIUtil.getPanelBackground() // Ensure background matches panel
        statusTextArea.caretPosition = 0 // Scroll to the beginning of the text
        statusScrollPane.isVisible = true
        LOG.debug("Error message displayed in status area: '$message'")
        statusScrollPane.revalidate() // Revalidate the scroll pane
        statusScrollPane.repaint()
        this.revalidate()
        this.repaint()
    }

    /**
     * Updates the UI to display a status message in the status text area.
     * Clears any existing image from the display. Must be called on EDT.
     * @param message The status message to display.
     */
    fun showStatus(message: String) {
        statusTextArea.text = message
        statusTextArea.foreground = JBUI.CurrentTheme.Label.foreground() // Standard text color
        statusTextArea.background = UIUtil.getPanelBackground() // Ensure background matches panel
        statusTextArea.caretPosition = 0 // Scroll to the beginning
        statusScrollPane.isVisible = true
        LOG.debug("Status message displayed in status area: '$message'")
        statusScrollPane.revalidate() // Revalidate the scroll pane
        statusScrollPane.repaint()
    }

    /**
     * Parses the potentially multi-line stderr output from Graphviz
     * to extract a concise, single-line summary suitable for quick display or logging.
     * NOTE: The full error message might still be displayed in the text area.
     * @param stderr The stderr output (may be null).
     * @return A concise string summarizing the first reported error/warning, or a default message.
     */
    private fun parseConciseErrorMessage(stderr: String?): String {
        // Keep the existing logic for parsing concise message for logging or potentially tooltips later
        if (stderr == null) return "Unknown Graphviz error"
        val firstIssueLine = stderr.lines().find {
            val trimmed = it.trim()
            trimmed.startsWith("Error:", ignoreCase = true) || trimmed.startsWith("Warning:", ignoreCase = true)
        }
        if (firstIssueLine != null) {
            val matcher = GRAPHVIZ_ISSUE_PATTERN.matcher(firstIssueLine.trim())
            if (matcher.find()) {
                val type = matcher.group(1) ?: "Issue"
                val lineNum = matcher.group(2)
                val details = matcher.group(3)?.trim()?.take(MAX_CONCISE_MESSAGE_LENGTH - 30) ?: "details unavailable"
                return "$type on line $lineNum: $details"
            }
            return firstIssueLine.trim().take(MAX_CONCISE_MESSAGE_LENGTH)
        }
        return stderr.trim().take(MAX_CONCISE_MESSAGE_LENGTH)
    }

} // End GraphvizPreviewPanel class