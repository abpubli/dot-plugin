package io.github.abpubli.dotsupport.editor.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.abpubli.dotsupport.external.DotExecutionResult
import io.github.abpubli.dotsupport.external.runDotCommand
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern
import javax.imageio.ImageIO
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
            "^(Error|Warning):.*? (?:line|near line)\\s*(\\d+)(.*)", // <-- Nowy regex
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
        // TRACE logs for entry, skip check, proceeding are here (levels assumed set previously)
        LOG.trace("triggerUpdate called. Force: $force, New text length: ${dotText.length}, Last text length: ${lastRenderedText?.length}")
        if (!force && dotText == lastRenderedText) {
            LOG.trace("Skipping render: Text unchanged and force=false.")
            return
        }
        LOG.trace("Proceeding with preview update.")
        LOG.debug("Setting status to 'Rendering...'") // Added DEBUG log
        showStatus("Rendering...")
        lastRenderingTask?.cancel(true)
        LOG.debug("Previous rendering task cancelled (if running).")

        errorToDisplay = null // Reset error state

        lastRenderingTask = ApplicationManager.getApplication().executeOnPooledThread {
            LOG.debug("Background thread [${Thread.currentThread().id}]: Starting direct 'dot -Tpng' execution.")

            var executionResult: DotExecutionResult? = null
            try {
                if (Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().id}]: Task cancelled before 'dot' execution.")
                    return@executeOnPooledThread
                }

                // Call dot with PNG format and potentially longer timeout
                executionResult = runDotCommand(dotText, "png", 15) // 15 second timeout for rendering

                if (Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().id}]: Task cancelled after 'dot' execution.")
                    return@executeOnPooledThread
                }

                // First, handle errors from the dot execution itself
                if (executionResult.executionError != null) {
                    throw executionResult.executionError // Rethrow so the main catch block handles it
                }
                if (executionResult.timedOut) {
                    throw TimeoutException("Graphviz 'dot' rendering timed out.") // Rethrow so the main catch block handles it
                }

                // Process the result - even if there are errors, we'll try to show the image if available
                lastRenderedText = dotText // Save the text regardless of the outcome

                val imageBytes = executionResult.outputBytes
                val errors = executionResult.errorOutput // Errors/warnings from stderr
                val exitCode = executionResult.exitCode
                val thisTaskFuture = lastRenderingTask // Capture the Future for THIS task

                LOG.debug("Direct 'dot' execution finished. Exit code: $exitCode, Stderr present: ${!errors.isNullOrBlank()}, Output bytes: ${imageBytes?.size}")

                // Pass results to the UI thread (EDT)
                SwingUtilities.invokeLater {
                    // Compare the reference of THIS task with the CURRENT value of lastRenderingTask in the panel
                    // AND check if THIS task was cancelled
                    if (thisTaskFuture != this@GraphvizPreviewPanel.lastRenderingTask || thisTaskFuture?.isCancelled == true) {
                        LOG.debug("Successful UI update skipped because task was cancelled or superseded by a newer one.")
                        return@invokeLater
                    }


                    var errorMessageToDisplay: String? = null
                    // Check stderr and exit code
                    if (!errors.isNullOrBlank()) {
                        // Use existing parsing logic for a concise message
                        errorMessageToDisplay = parseConciseErrorMessage(errors)
                        if (exitCode != 0) errorMessageToDisplay += " (Exit code: $exitCode)"
                        LOG.warn("Graphviz stderr reported during rendering: $errors")
                    } else if (exitCode != 0) {
                        errorMessageToDisplay = "Graphviz 'dot' failed with exit code $exitCode."
                        LOG.warn(errorMessageToDisplay)
                    }

                    var imageDisplayed = false
                    // Try to display the image if data is available
                    if (imageBytes != null && imageBytes.isNotEmpty()) {
                        try {
                            val image = ImageIO.read(ByteArrayInputStream(imageBytes))
                            if (image != null) {
                                updateImage(image) // This method sets the success status
                                imageDisplayed = true
                                // If there were warnings (stderr not empty), but the image was rendered,
                                // we can log them or briefly show a status like "completed with warnings".
                                if (!errors.isNullOrBlank()) {
                                    // Update the status after loading the image
                                    showStatus("Completed (with warnings - check log)")
                                }
                            } else {
                                // Data was present, but ImageIO failed to decode
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

                    // If the image was not displayed, show the error (if any)
                    if (!imageDisplayed) {
                        if (!errorMessageToDisplay.isNullOrBlank()) {
                            showError(errorMessageToDisplay)
                        } else {
                            // No image and no specific error (e.g., dot returned 0, empty stdout/stderr?)
                            showError("Rendering produced no image or failed silently.")
                        }
                    }
                } // End invokeLater

            } catch (e: Exception) { // Catches execution errors, timeouts, etc.
                if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                    LOG.debug(
                        "Background thread [${Thread.currentThread().id}]: Direct 'dot' execution cancelled/interrupted in outer catch.",
                        e
                    )
                    return@executeOnPooledThread // Don't show an error for cancellation
                }
                LOG.error("Error executing or processing 'dot' command for preview.", e)
                val finalError = e // Capture for invokeLater
                val thisTaskFuture = lastRenderingTask // Capture the Future for THIS task
                SwingUtilities.invokeLater {
                    // Compare the reference of THIS task with the CURRENT value of lastRenderingTask in the panel
                    // AND check if THIS task was cancelled
                    if (thisTaskFuture != this@GraphvizPreviewPanel.lastRenderingTask || thisTaskFuture?.isCancelled == true) {
                        LOG.debug("Error UI update skipped because task was cancelled or superseded by a newer one.")
                        return@invokeLater
                    }
                    // Show execution error (e.g., dot not found, timeout)
                    showError("Failed to run/process Graphviz 'dot': ${finalError.message}")
                }
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