package io.github.abpubli.dotsupport.editor.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.engine.GraphvizException // Import GraphvizException
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.util.concurrent.Future
import java.util.regex.Pattern // Import Pattern for parsing
import javax.swing.*

/**
 * A JPanel implementation responsible for rendering and displaying
 * a preview of Graphviz DOT text as an image.
 * It handles background rendering using [Graphviz.fromString] and displays status/error messages.
 * Uses IntelliJ UI components like [JBLabel] and [JBScrollPane].
 */
class GraphvizPreviewPanel : JPanel(BorderLayout()), Disposable {

    private companion object {
        private val LOG = Logger.getInstance(GraphvizPreviewPanel::class.java)
        // Regex pattern also used here for parsing a concise error message for the status label.
        // Duplicated from Annotator for now, could be refactored into a shared utility.
        private val ISSUE_PATTERN: Pattern = Pattern.compile(
            "^(Error|Warning):\\s*(?:.*?:)?\\s*(?:line|near line)\\s*(\\d+)(.*)",
            Pattern.CASE_INSENSITIVE
        )
        // Limit message parsing length for performance/log readability
        private const val MAX_STDERR_PARSE_LENGTH = 5000
        private const val MAX_CONCISE_MESSAGE_LENGTH = 150
    }

    @Volatile
    private var lastRenderingTask: Future<*>? = null
    private val imageLabel: JLabel = JLabel()
    private val statusLabel: JBLabel = JBLabel("", SwingConstants.CENTER)
    private val scrollPane: JBScrollPane
    private var lastRenderedText: String? = null

    // Field to store the error information to be displayed in the UI thread.
    // Can be Exception or a specific String message.
    @Volatile
    private var errorToDisplay: Any? = null

    init {
        imageLabel.horizontalAlignment = SwingConstants.CENTER
        imageLabel.verticalAlignment = SwingConstants.CENTER
        scrollPane = JBScrollPane(imageLabel)
        add(scrollPane, BorderLayout.CENTER)
        statusLabel.border = JBUI.Borders.empty(5)
        add(statusLabel, BorderLayout.SOUTH)
        showStatus("Waiting for data...")
    }

    /**
     * Initiates the rendering process for the provided DOT text.
     * Rendering is performed on a background thread. Skips rendering if text is unchanged (unless forced).
     * Cancels any previous rendering task. Handles [GraphvizException] specifically
     * to display a more concise error message in the status label.
     *
     * @param dotText The DOT source text to render.
     * @param force If `true`, forces rendering even if `dotText` is identical to `lastRenderedText`.
     */
    fun triggerUpdate(dotText: String, force: Boolean = false) {
        LOG.trace("triggerUpdate called. Force: $force, New text length: ${dotText.length}, Last text length: ${lastRenderedText?.length}")

        if (!force && dotText == lastRenderedText) {
            LOG.trace("Skipping render: Text unchanged and force=false.")
            return
        }

        LOG.trace("Proceeding with preview update.")
        LOG.debug("Setting status to 'Rendering...'")
        showStatus("Rendering...") // showStatus wewnętrznie loguje na DEBUG

        lastRenderingTask?.cancel(true)
        LOG.debug("Previous rendering task cancelled (if running).")

        // Reset error state before starting background task
        errorToDisplay = null
        var renderedImage: BufferedImage? = null // Use local variable in task scope

        lastRenderingTask = ApplicationManager.getApplication().executeOnPooledThread {
            LOG.debug("Background thread [${Thread.currentThread().id}]: Starting Graphviz rendering.")

            try {
                if (Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().id}]: Task cancelled before rendering.")
                    return@executeOnPooledThread
                }

                // Attempt to render to PNG image.
                renderedImage = Graphviz.fromString(dotText)
                    .render(Format.PNG)
                    .toImage()

                if (renderedImage == null && !Thread.currentThread().isInterrupted) {
                    // Graphviz might return null without exception in some cases.
                    throw IllegalStateException("Graphviz rendering returned a null image.")
                }

                lastRenderedText = dotText // Store text on success
                LOG.info("Graphviz rendering successful on thread [${Thread.currentThread().id}].")

            } catch (e: Exception) {
                // --- Modified Error Handling ---
                if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().id}]: Rendering cancelled/interrupted.", e)
                    return@executeOnPooledThread // Exit, don't show error for cancellation.
                }

                // Store the text that caused the error regardless of type.
                lastRenderedText = dotText

                if (e is GraphvizException) {
                    // Handle Graphviz-specific errors: Log full error, prepare concise message for UI.
                    LOG.warn("GraphvizException rendering preview on thread [${Thread.currentThread().id}]: ${e.message?.take(MAX_STDERR_PARSE_LENGTH)}", e)
                    errorToDisplay = parseConciseErrorMessage(e.message) // Generate user-friendly message
                } else {
                    // Handle other unexpected exceptions during rendering.
                    LOG.error("Unexpected error rendering Graphviz preview on thread [${Thread.currentThread().id}]: ${e.message}", e)
                    errorToDisplay = e // Store the exception itself for generic display
                }
                // --- End Modified Error Handling ---
            }

            // Update UI on EDT, check for cancellation again.
            if (!Thread.currentThread().isInterrupted) {
                val finalError = errorToDisplay // Capture volatile field for EDT task
                val finalImage = renderedImage // Capture local variable for EDT task

                SwingUtilities.invokeLater {
                    if (finalError != null) {
                        // Display error based on what was stored in errorToDisplay
                        val errorMessage = when(finalError) {
                            is String -> finalError // Use the pre-parsed concise message
                            is Exception -> finalError.localizedMessage ?: finalError.javaClass.simpleName // Generic message from other exceptions
                            else -> "Unknown error occurred"
                        }
                        showError(errorMessage)
                    } else if (finalImage != null) {
                        updateImage(finalImage)
                    } else {
                        // Handle case where image is null but no error was stored (e.g., cancelled late)
                        showError("Rendering did not produce an image or was cancelled.")
                    }
                }
            } else {
                LOG.debug("Background thread [${Thread.currentThread().id}]: UI update skipped due to cancellation.")
            }
        } // End executeOnPooledThread
    } // End triggerUpdate


    override fun dispose() {
        LOG.debug("Disposing GraphvizPreviewPanel")
        lastRenderingTask?.cancel(true) // Cancel any active rendering task
        lastRenderingTask = null
        imageLabel.icon = null
        lastRenderedText = null
    }

    private fun updateImage(image: BufferedImage) {
        imageLabel.icon = ImageIcon(image)
        imageLabel.text = null
        statusLabel.text = "Rendering completed successfully"
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
        statusLabel.isVisible = true
        scrollPane.revalidate()
        scrollPane.repaint()
        LOG.debug("Image updated in the preview panel.")
        this.revalidate()
        this.repaint()
    }

    /**
     * Updates the UI to display an error message in the status label.
     * Clears any existing image from the display. Must be called on EDT.
     * @param message The error message to display.
     */
    fun showError(message: String) {
        imageLabel.icon = null
        imageLabel.text = null
        statusLabel.text = "Error: $message" // Prefix with "Error: "
        statusLabel.foreground = UIUtil.getErrorForeground()
        statusLabel.isVisible = true
        LOG.debug("Error message displayed in status label: '$message'")
        this.revalidate()
        this.repaint()
    }

    private fun showStatus(message: String) {
        imageLabel.icon = null
        imageLabel.text = null
        statusLabel.text = message
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
        statusLabel.isVisible = true
        LOG.debug("Status message displayed in status label: '$message'")
        this.revalidate()
        this.repaint()
    }

    /**
     * Parses the potentially multi-line stderr output from a GraphvizException
     * to extract a concise, single-line summary suitable for the status label.
     * @param stderr The message content from GraphvizException (may be null).
     * @return A concise string summarizing the first reported error/warning, or a default message.
     */
    private fun parseConciseErrorMessage(stderr: String?): String {
        if (stderr == null) return "Unknown Graphviz error"

        // Find the first line starting with "Error:" or "Warning:"
        val firstIssueLine = stderr.lines().find {
            val trimmed = it.trim()
            trimmed.startsWith("Error:", ignoreCase = true) || trimmed.startsWith("Warning:", ignoreCase = true)
        }

        if (firstIssueLine != null) {
            // Try to extract details using the regex for a slightly better message
            val matcher = ISSUE_PATTERN.matcher(firstIssueLine.trim())
            if (matcher.find()) {
                val type = matcher.group(1) ?: "Issue"
                val lineNum = matcher.group(2)
                val details = matcher.group(3)?.trim()?.take(MAX_CONCISE_MESSAGE_LENGTH - 30) ?: "details unavailable"
                return "$type on line $lineNum: $details"
            }
            // Fallback: return the first issue line itself if regex fails
            return firstIssueLine.trim().take(MAX_CONCISE_MESSAGE_LENGTH)
        }

        // Fallback: return the beginning of the stderr if no specific issue line found
        return stderr.trim().take(MAX_CONCISE_MESSAGE_LENGTH)
    }

} // End GraphvizPreviewPanel class