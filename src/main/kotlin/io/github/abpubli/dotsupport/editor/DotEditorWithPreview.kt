package io.github.abpubli.dotsupport.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger // Added Logger import
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewFileEditor
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The main editor for DOT files, combining a standard IntelliJ [TextEditor]
 * with a custom [GraphvizPreviewFileEditor] using [TextEditorWithPreview].
 * Handles debounced updates of the preview based on document changes and ensures
 * document access happens within read actions.
 *
 * @param project The current project.
 * @param virtualFile The DOT file being edited. Passed explicitly as `this.file` might be null during superclass init.
 */
class DotEditorWithPreview(
    project: Project,
    virtualFile: VirtualFile // File is often available earlier via constructor than via this.file
) : TextEditorWithPreview(
    // Arguments for the TextEditorWithPreview constructor:
    createPrimaryEditor(project, virtualFile), // The primary text editor component
    createPreviewEditor(project, virtualFile), // Our custom preview editor component
    "DOT Editor",                              // Display name for the editor tab
    Layout.SHOW_EDITOR_AND_PREVIEW,            // Default layout arrangement: show both editor and preview
    true                                       // Give focus to the text editor on open
) {

    // Companion object holds the factory methods and the logger instance
    private companion object {
        // Logger instance for this class
        private val LOG = Logger.getInstance(DotEditorWithPreview::class.java)

        /**
         * Creates the primary text editor component using IntelliJ's standard provider.
         * @param project The current project.
         * @param file The virtual file to be opened in the editor.
         * @return An instance of [TextEditor].
         * @throws IllegalStateException if the provider fails to create a TextEditor.
         */
        private fun createPrimaryEditor(project: Project, file: VirtualFile): TextEditor {
            LOG.debug("Creating primary TextEditor for ${file.name}...")
            val editor = TextEditorProvider.getInstance().createEditor(project, file)
            // Ensure we actually received a TextEditor instance
            if (editor is TextEditor) {
                LOG.debug("Primary TextEditor created successfully.")
                return editor
            } else {
                // This scenario is highly unlikely for text-based files but included for robustness
                LOG.error("FATAL ERROR - TextEditorProvider did not return a TextEditor for ${file.name}! Returned: ${editor.javaClass.name}")
                throw IllegalStateException("Could not create primary text editor for ${file.name}")
            }
        }

        /**
         * Creates the secondary preview editor component which wraps our custom [GraphvizPreviewPanel].
         * @param project The current project.
         * @param file The virtual file associated with the preview.
         * @return An instance of [GraphvizPreviewFileEditor].
         */
        private fun createPreviewEditor(project: Project, file: VirtualFile): FileEditor {
            LOG.debug("Creating GraphvizPreviewPanel for ${file.name}...")
            val previewPanel = GraphvizPreviewPanel() // Instantiate our custom Swing panel
            LOG.debug("Creating GraphvizPreviewFileEditor wrapper for ${file.name}...")
            // Wrap the panel in our FileEditor implementation
            val previewEditor = GraphvizPreviewFileEditor(project, file, previewPanel)
            LOG.debug("Secondary GraphvizPreviewFileEditor created for ${file.name}.")
            return previewEditor
        }
    } // End companion object

    // Alarm used for debouncing preview updates (delaying updates until typing pauses).
    // It's automatically disposed when this editor (parent Disposable) is closed.
    private val updateAlarm: Alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    // Direct reference to the GraphvizPreviewPanel from the secondary editor.
    // Cast using 'as?' for safety, in case createPreviewEditor returns an unexpected type.
    private val graphvizPreviewPanel: GraphvizPreviewPanel? =
        (previewEditor as? GraphvizPreviewFileEditor)?.previewPanel

    // Initialization block executed when an instance of DotEditorWithPreview is created.
    init {
        LOG.debug("Initializing DotEditorWithPreview for ${virtualFile.name}")

        // Verify that the preview panel was successfully retrieved from the previewEditor
        if (graphvizPreviewPanel == null) {
            // This indicates a problem either in createPreviewEditor or potentially the base class initialization order.
            LOG.error("Graphviz preview panel is null after initialization! Preview functionality will be disabled. Check createPreviewEditor logic and ensure previewEditor is correctly initialized.")
        } else {
            LOG.debug("GraphvizPreviewPanel reference obtained successfully.")
        }

        // --- Add DocumentListener to trigger updates on text changes ---
        // Attempt to get the document associated with the virtual file.
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        if (document != null) {
            // Add a listener that schedules a preview update whenever the document content changes.
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    // Avoid triggering updates for empty events or during bulk updates if needed (simple version here)
                    if (!event.isWholeTextReplaced || event.newLength > 0 || event.oldLength > 0) {
                        LOG.trace("Document changed event received for ${virtualFile.name}, scheduling preview update.")
                        schedulePreviewUpdate() // Schedule the debounced update
                    }
                }
            }, this) // Pass 'this' as the parent disposable so the listener is removed automatically on editor close.
            LOG.debug("DocumentListener added successfully to document for ${virtualFile.name}.")
        } else {
            // This is unusual if the file exists and is text-based. Log an error.
            LOG.error("Could not get document for ${virtualFile.name} during initialization. Preview updates on document change will not work.")
        }
        // --- End DocumentListener setup ---


        // Schedule the very first preview update shortly after the editor is fully initialized.
        // Use a Swing Timer for a simple, one-time delayed execution.
        val initialUpdateDelay = 300 // Delay in milliseconds before the first render attempt
        val initialUpdateTimer = Timer(initialUpdateDelay) {
            // Check if the editor hasn't been disposed before the timer's action executes.
            if (!Disposer.isDisposed(this@DotEditorWithPreview)) {
                LOG.debug("Initial preview update timer fired. Triggering update (force=true).")
                triggerPreviewUpdate(force = true) // Force the first render attempt.
            } else {
                LOG.debug("Initial preview update timer fired, but the editor was already disposed.")
            }
        }
        initialUpdateTimer.isRepeats = false // Ensure the timer runs only once.
        initialUpdateTimer.start()
        LOG.debug("Initial preview update Timer scheduled for ${virtualFile.name} (delay ${initialUpdateDelay}ms).")

        LOG.info("DotEditorWithPreview initialization complete for ${virtualFile.name}.")
    } // End init block

    /**
     * Schedules a debounced preview update using the [updateAlarm].
     * This cancels any pending update requests and adds a new one with a defined delay,
     * preventing excessive updates during continuous typing.
     */
    private fun schedulePreviewUpdate() {
        // Cancel any previously scheduled update requests to reset the debounce timer.
        updateAlarm.cancelAllRequests()
        // Add a new request to trigger the update after a specified delay.
        updateAlarm.addRequest({
            // Double-check the disposal state before actually running the update task.
            if (!Disposer.isDisposed(this@DotEditorWithPreview)) {
                LOG.debug("Debounced update request executing.")
                triggerPreviewUpdate(force = false) // Trigger the update, no forcing needed for subsequent updates.
            } else {
                LOG.debug("Scheduled preview update request running, but editor was already disposed.")
            }
        }, 500) // Debounce delay in milliseconds (e.g., 500ms).
        LOG.trace("Preview update request scheduled (debounced).") // Use trace level for frequent logs.
    }

    /**
     * Retrieves the current text from the editor's document and triggers the update
     * method in the [graphvizPreviewPanel].
     * **Important:** Document access occurs within a read action for thread safety.
     *
     * @param force If true, forces the preview panel to re-render even if the text content
     * appears unchanged compared to the last render. Used for the initial update.
     */
    private fun triggerPreviewUpdate(force: Boolean = false) {
        // Immediately exit if the editor is already disposed.
        if (Disposer.isDisposed(this)) {
            LOG.debug("Attempted to trigger preview update, but editor is disposed.")
            return
        }

        // --- Get document text safely within a read action ---
        var text: String? = null
        var documentAvailable = false
        var fileNameForLog: String? = "unknown file"

        // Reading document content MUST happen under a read action.
        ApplicationManager.getApplication().runReadAction {
            // Re-check disposal state inside the read action, as disposal might happen concurrently.
            if (Disposer.isDisposed(this@DotEditorWithPreview)) {
                LOG.debug("ReadAction started, but editor was disposed before document access.")
                return@runReadAction // Exit the lambda.
            }

            // It's safer to get the file and document again inside the action.
            val currentFile: VirtualFile? =
                this.file // Use the 'file' property now, should be non-null if not disposed.
            fileNameForLog = currentFile?.name ?: "null file" // For logging context
            val document = if (currentFile != null) FileDocumentManager.getInstance().getDocument(currentFile) else null

            if (document != null) {
                documentAvailable = true
                text = document.text // Perform the actual text read.
                LOG.debug("ReadAction: Text obtained (length=${text?.length}) for $fileNameForLog")
            } else {
                documentAvailable = false
                // Log a warning if the document is unexpectedly null.
                LOG.warn("ReadAction: Document is null for file $fileNameForLog. Cannot get text for preview update.")
            }
        } // End runReadAction
        // --- End text retrieval ---

        // Get a local reference to the preview panel.
        val previewPanel = this.graphvizPreviewPanel

        // Check if we successfully obtained the text and have a valid preview panel instance.
        if (documentAvailable && text != null && previewPanel != null) {
            // All conditions met, proceed to trigger the update in the preview panel.
            LOG.debug("Triggering previewPanel.triggerUpdate for $fileNameForLog (force=$force)")
            // Use the non-null asserted 'text!!' as we've checked for nullity.
            // The actual UI update within the panel should happen on the EDT (handled by the panel itself).
            previewPanel.triggerUpdate(text!!, force)
        } else {
            // Log details if the update cannot proceed.
            LOG.warn("Cannot trigger preview update for $fileNameForLog. Conditions: documentAvailable=$documentAvailable, text!=null=${text != null}, previewPanel!=null=${previewPanel != null}.")
            // If the document was the issue, attempt to show an error message in the preview panel's UI.
            if (!documentAvailable) {
                SwingUtilities.invokeLater { // Ensure UI interaction happens on the Event Dispatch Thread.
                    previewPanel?.showError("Unable to read file document to update preview.")
                }
            }
            // Provide additional debugging information if the panel is null but the editor wrapper exists.
            if (previewPanel == null && previewEditor != null) {
                LOG.warn("    (Preview panel is null, but previewEditor exists. Secondary editor type is: ${previewEditor.javaClass.name}. Expected GraphvizPreviewFileEditor containing GraphvizPreviewPanel)")
            }
        }
    }

    // Note on Disposal:
    // The dispose() method itself is primarily handled by the base TextEditorWithPreview class.
    // Resources initialized with 'this' as their parent Disposable (like the 'updateAlarm' and the DocumentListener)
    // are managed automatically and cleaned up when this editor is disposed.
    // If the GraphvizPreviewPanel itself requires specific cleanup logic (e.g., stopping background tasks, releasing native resources),
    // that logic should be implemented within the GraphvizPreviewFileEditor's dispose() method, which will be called
    // by the framework when the secondary editor is disposed.

} // End DotEditorWithPreview class