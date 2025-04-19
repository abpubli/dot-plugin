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
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.util.concurrent.Future
import javax.swing.*

/**
 * A JPanel implementation responsible for rendering and displaying
 * a preview of Graphviz DOT text as an image.
 * It handles background rendering using [Graphviz.fromString] and displays status/error messages.
 * Uses IntelliJ UI components like [JBLabel] and [JBScrollPane].
 */
class GraphvizPreviewPanel : JPanel(BorderLayout()), Disposable {

    // Logger instance for this class
    private companion object {
        private val LOG = Logger.getInstance(GraphvizPreviewPanel::class.java)
    }

    // Field to hold the reference to the background rendering task
    @Volatile // Ensure visibility across threads
    private var lastRenderingTask: Future<*>? = null

    private val imageLabel: JLabel = JLabel() // Label to display the rendered Graphviz image
    private val statusLabel: JBLabel =
        JBLabel("", SwingConstants.CENTER) // Label for status messages (e.g., "Rendering...") or errors
    private val scrollPane: JBScrollPane // Scroll pane to make the image view scrollable

    // Stores the DOT text of the last successfully rendered or attempted render
    // Used for optimization to avoid re-rendering unchanged text.
    private var lastRenderedText: String? = null

    init {
        // Center the image within the label
        imageLabel.horizontalAlignment = SwingConstants.CENTER
        imageLabel.verticalAlignment = SwingConstants.CENTER

        // Place the image label inside a scroll pane
        scrollPane = JBScrollPane(imageLabel)
        add(scrollPane, BorderLayout.CENTER) // Add scroll pane to the center

        // Configure and add the status label at the bottom
        statusLabel.border = JBUI.Borders.empty(5) // Add some padding
        add(statusLabel, BorderLayout.SOUTH)

        showStatus("Waiting for data...") // Set initial status message
    }

    /**
     * Initiates the rendering process for the provided DOT text.
     * Rendering is performed on a background thread to avoid blocking the UI thread.
     * Includes an optimization to skip rendering if the text has not changed since the last attempt, unless forced.
     * Cancels any previous rendering task that might still be running.
     *
     * @param dotText The DOT source text to render.
     * @param force If `true`, forces rendering even if `dotText` is identical to `lastRenderedText`.
     */
    fun triggerUpdate(dotText: String, force: Boolean = false) {
        LOG.debug("triggerUpdate called. Force: $force, New text length: ${dotText.length}, Last text length: ${lastRenderedText?.length}")

        // Optimization: Avoid re-rendering if the text is identical and not forced
        if (!force && dotText == lastRenderedText) {
            LOG.debug("Skipping render: Text unchanged and force=false.")
            // Optionally update status if image already exists, otherwise keep current status/error
            if (imageLabel.icon != null) {
                // Keep the successful image, maybe just update status slightly if needed
                // showStatus("Preview up-to-date") // Example status
            }
            return // Do not proceed with rendering
        }

        LOG.debug("Proceeding with preview update.")
        // Display "Rendering..." message immediately on the EDT
        showStatus("Rendering...")

        // Cancel any previously running rendering task
        // Use '?' for safety, though it should ideally not be null if a task was started
        // 'true' attempts to interrupt the thread if it's running
        lastRenderingTask?.cancel(true)
        LOG.debug("Previous rendering task cancelled (if running).")

        // Execute the potentially time-consuming rendering task on a background thread
        // Store the Future reference to allow cancellation later
        lastRenderingTask = ApplicationManager.getApplication().executeOnPooledThread {
            LOG.debug("Background thread [${Thread.currentThread().id}]: Starting Graphviz rendering.")
            var renderedImage: BufferedImage? = null // Store result locally
            var error: Exception? = null // Store error locally

            try {
                // Check if the task was cancelled before starting the heavy work
                if (Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().id}]: Task cancelled before rendering.")
                    return@executeOnPooledThread // Exit the runnable
                }

                // Attempt to render the DOT text to a PNG image using the default engine
                renderedImage = Graphviz.fromString(dotText)
                    .render(Format.PNG)
                    .toImage() // Note: toImage() can return null in some failure cases

                // Check if rendering actually produced an image (and wasn't cancelled during render)
                if (renderedImage == null && !Thread.currentThread().isInterrupted) {
                    throw IllegalStateException("Graphviz rendering returned a null image. Check DOT syntax or Graphviz setup.")
                }

                // Successfully rendered, store the text for future comparison
                // Do this *before* updating UI in case another trigger comes quickly
                lastRenderedText = dotText
                LOG.info("Graphviz rendering successful on thread [${Thread.currentThread().id}].")

            } catch (e: Exception) {
                // Check if cancellation is the root cause
                if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                    LOG.debug("Background thread [${Thread.currentThread().id}]: Rendering cancelled/interrupted.", e)
                    // Don't treat cancellation as a rendering error to show to the user
                    return@executeOnPooledThread // Exit the runnable
                }
                // Log actual rendering errors using WARN level, include the exception
                LOG.warn("Error rendering Graphviz preview on thread [${Thread.currentThread().id}]: ${e.message}", e)
                // Store the text that caused the error to prevent immediate re-tries if unchanged
                lastRenderedText = dotText
                error = e // Store the error to pass to the EDT
            }

            // Update the UI on the Event Dispatch Thread (EDT)
            // Check again for cancellation before queuing UI update
            if (!Thread.currentThread().isInterrupted) {
                SwingUtilities.invokeLater {
                    // Check if this specific task instance is still the active one
                    // (Although cancellation should prevent this, it's an extra safety layer)
                    // if (lastRenderingTask != null && !lastRenderingTask!!.isCancelled ) { // Requires storing the Future from outside
                    if (error != null) {
                        showError(error.localizedMessage ?: error.javaClass.simpleName)
                    } else if (renderedImage != null) {
                        updateImage(renderedImage)
                    } else {
                        // This case might happen if rendering yielded null but no exception,
                        // or was cancelled but somehow reached here.
                        showError("Rendering did not produce an image or was cancelled.")
                    }
                    // } else {
                    //    LOG.debug("UI update skipped as the task was cancelled or superseded.")
                    // }
                }
            } else {
                LOG.debug("Background thread [${Thread.currentThread().id}]: UI update skipped due to cancellation.")
            }
        } // End executeOnPooledThread
    } // End triggerUpdate


    override fun dispose() {
        LOG.debug("Disposing GraphvizPreviewPanel")
        // Anuluj aktywne zadanie renderowania, jeśli istnieje
        // lastRenderingTask?.cancel(true)
        // lastRenderingTask = null

        // Wyczyść zasoby, np. ikonę
        imageLabel.icon = null
        lastRenderedText = null
        // Ew. inne czyszczenie specyficzne dla biblioteki Graphviz
    }

    /**
     * Updates the UI to display the rendered Graphviz image.
     * This method must be called on the Event Dispatch Thread (EDT).
     *
     * @param image The [BufferedImage] to display. Assumed not null by this point.
     */
    private fun updateImage(image: BufferedImage) {
        imageLabel.icon = ImageIcon(image)
        imageLabel.text = null // Clear any previous text (like error messages) from image label
        statusLabel.text = "Rendering completed successfully" // Update status label
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground() // Ensure default text color
        statusLabel.isVisible = true
        // Important: Revalidate the scroll pane in case the image size changed its preferred size
        scrollPane.revalidate()
        scrollPane.repaint()
        LOG.debug("Image updated in the preview panel.")
        // Revalidate the whole panel too, just in case
        this.revalidate()
        this.repaint()
    }

    /**
     * Updates the UI to display an error message in the status label.
     * Clears any existing image from the display.
     * This method must be called on the Event Dispatch Thread (EDT).
     *
     * @param message The error message to display.
     */
    fun showError(message: String) {
        imageLabel.icon = null // Clear the image display area
        imageLabel.text = null // Clear any text in image label
        statusLabel.text = "Error: $message" // Display the error message (English prefix)
        statusLabel.foreground = UIUtil.getErrorForeground() // Use standard IntelliJ error color
        statusLabel.isVisible = true
        LOG.debug("Error message displayed in status label: '$message'")
        // Revalidate the panel to reflect changes
        this.revalidate()
        this.repaint()
    }

    /**
     * Updates the UI to display a status message (e.g., "Rendering...", "Waiting for data...").
     * Clears any existing image from the display.
     * This method must be called on the Event Dispatch Thread (EDT).
     *
     * @param message The status message to display.
     */
    private fun showStatus(message: String) {
        imageLabel.icon = null // Clear the image display area when showing status
        imageLabel.text = null // Clear any text in image label
        statusLabel.text = message // Display the status message
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground() // Use standard text color
        statusLabel.isVisible = true
        LOG.debug("Status message displayed in status label: '$message'")
        // Revalidate the panel to reflect changes
        this.revalidate()
        this.repaint()
    }
}