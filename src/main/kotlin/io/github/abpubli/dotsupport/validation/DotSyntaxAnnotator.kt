package io.github.abpubli.dotsupport.validation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document // Required for apply method access
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
// Imports for Graphviz rendering and exception
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.engine.GraphvizException
import java.awt.image.BufferedImage // Import needed for toImage() return type
import java.util.regex.Pattern

/**
 * An external annotator that uses Graphviz via guru.nidi.graphviz-java
 * to perform syntax validation on DOT files in the background.
 * This version attempts to render a PNG image and relies on catching
 * GraphvizException to detect and parse errors.
 * Note: Error detection might be less reliable than direct stderr parsing
 * if the 'dot' process returns exit code 0 despite syntax errors for PNG output.
 */
class DotSyntaxAnnotator : ExternalAnnotator<DotFileInfo, DotValidationResult>() {

    companion object {
        private val LOG = Logger.getInstance(DotSyntaxAnnotator::class.java)
        init {
            LOG.info("DotSyntaxAnnotator CLASS LOADED")
        }

        /**
         * Regex to parse Graphviz error/warning lines from GraphvizException message.
         * Captures: Group 1=Type ("Error" or "Warning"), Group 2=Line Number, Group 3=Rest of message.
         */
        private val ISSUE_PATTERN: Pattern = Pattern.compile(
            "^(Error|Warning):\\s*(?:.*?:)?\\s*(?:line|near line)\\s*(\\d+)(.*)",
            Pattern.CASE_INSENSITIVE
        )
        // Limit message parsing length
        private const val MAX_EXCEPTION_MESSAGE_PARSE_LENGTH = 5000
    }

    init {
        LOG.info("DotSyntaxAnnotator INSTANCE CREATED")
    }

    /**
     * Collects necessary information (file text and document) from the UI thread.
     */
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): DotFileInfo? {
        LOG.debug("DotSyntaxAnnotator: collectInformation CALLED for ${file.name}")
        try {
            val document = editor.document
            val text = document.text
            val info = DotFileInfo(text, document)
            LOG.debug("DotSyntaxAnnotator: collectInformation RETURNING info for ${file.name}")
            return info
        } catch (e: Throwable) {
            LOG.error("Error inside collectInformation for ${file.name}", e)
            return null
        }
    }

    /**
     * Performs Graphviz validation by attempting to render a PNG image in a background thread.
     * Relies solely on catching GraphvizException for error detection.
     *
     * @param collectedInfo Data gathered by [collectInformation].
     * @return DotValidationResult containing issues parsed from GraphvizException, or empty list if no exception.
     */
    override fun doAnnotate(collectedInfo: DotFileInfo?): DotValidationResult? {
        LOG.debug("DotSyntaxAnnotator: doAnnotate CALLED (info is null: ${collectedInfo == null})")
        if (collectedInfo == null) {
            LOG.warn("DotSyntaxAnnotator: doAnnotate returning NULL because collectedInfo is null")
            return null
        }
        // Avoid processing potentially huge files
        if (collectedInfo.text.length > 500_000) {
            LOG.warn("Skipping annotation for large file (${collectedInfo.text.length} chars)")
            return DotValidationResult(emptyList())
        }

        LOG.debug("Starting background annotation (PNG render attempt) for document text length ${collectedInfo!!.text.length}")

        val issues = mutableListOf<DotIssueInfo>()
        var graphvizExceptionOccurred = false // Simple flag

        try {
            LOG.debug("DotSyntaxAnnotator: Entering Graphviz try block (attempting PNG render)")

            // Attempt to render to PNG. We primarily care if this throws an exception.
            // We store the result just to ensure the operation is fully attempted.
            @Suppress("UNUSED_VARIABLE") // We don't use the image, just check for exceptions
            val renderedImage: BufferedImage? =
                Graphviz.fromString(collectedInfo!!.text) // Use non-null assertion
                    .render(Format.PNG) // Render as PNG, like the preview
                    .toImage()

            // If control reaches here, the library did not throw GraphvizException.
            // Assume syntax is OK *as far as this check can tell*.
            LOG.debug("DotSyntaxAnnotator: Graphviz PNG render call finished without throwing GraphvizException.")

        } catch (e: GraphvizException) {
            // This block executes ONLY if .render(Format.PNG).toImage() throws.
            graphvizExceptionOccurred = true
            LOG.warn("DotSyntaxAnnotator: GraphvizException caught during PNG render! Parsing message...")
            val errorMessage = e.message?.take(MAX_EXCEPTION_MESSAGE_PARSE_LENGTH) ?: ""

            // --- Parse the exception message for error/warning lines ---
            errorMessage.lines().forEach { line ->
                val matcher = ISSUE_PATTERN.matcher(line.trim())
                if (matcher.find()) {
                    try {
                        val type = matcher.group(1)
                        val lineNumberStr = matcher.group(2)
                        val message = line.trim()
                        val lineNumber = lineNumberStr?.toIntOrNull()

                        if (lineNumber != null) {
                            val severity = when (type.lowercase()) {
                                "error" -> HighlightSeverity.ERROR
                                "warning" -> HighlightSeverity.WARNING
                                else -> HighlightSeverity.WEAK_WARNING
                            }
                            issues.add(DotIssueInfo(severity, lineNumber, message))
                            LOG.debug("DotSyntaxAnnotator: Found and added issue from Exception: $type on line $lineNumber")
                        } else {
                            LOG.warn("DotSyntaxAnnotator: Matched issue pattern in Exception but failed to parse line number from: '$line'")
                        }
                    } catch (parseEx: Exception) {
                        LOG.warn("DotSyntaxAnnotator: Failed to parse details from GraphvizException issue line: '$line'", parseEx)
                    }
                } // End if matcher.find()
            } // End forEach line
            LOG.debug("DotSyntaxAnnotator: Finished parsing GraphvizException message. Added ${issues.size} issues.")
            // --- End of parsing logic ---

        } catch (e: Exception) {
            // Catch other unexpected exceptions during the rendering attempt.
            LOG.error("DotSyntaxAnnotator: Unexpected exception during Graphviz PNG render call", e)
            graphvizExceptionOccurred = true // Mark that some failure occurred
        }

        // Log the final result.
        LOG.debug("DotSyntaxAnnotator: doAnnotate RETURNING result with ${issues.size} issues. (GraphvizException occurred: $graphvizExceptionOccurred)")
        return DotValidationResult(issues)
    }


    /**
     * Applies the validation results (creating annotations) back in the UI thread.
     * Ensures only one annotation (with the highest severity) is shown per line if multiple issues are reported.
     *
     * @param file The PSI file being annotated.
     * @param annotationResult The validation results from the background task ([doAnnotate]).
     * @param holder The object used to create annotations in the editor.
     */
    override fun apply(file: PsiFile, annotationResult: DotValidationResult?, holder: AnnotationHolder) {
        // Log entry includes issue count from the result for clarity
        LOG.debug("DotSyntaxAnnotator: apply CALLED for ${file.name} (result is null: ${annotationResult == null}, issue count: ${annotationResult?.issues?.size ?: "N/A"})")

        if (annotationResult == null || annotationResult.issues.isEmpty()) {
            LOG.debug("No issues found or annotation result is null for ${file.name}. No annotations to apply.")
            // Optional: Clear previous annotations from this annotator if needed,
            // though AnnotationHolder often handles this implicitly.
            return
        }
        LOG.debug("Processing ${annotationResult.issues.size} raw issues for ${file.name}.")

        val document = file.viewProvider.document
        if (document == null) {
            LOG.error("Could not get document for file ${file.name} in apply phase.")
            return
        }

        // Prioritize issues: Group by line number and select the one with the highest severity for each line.
        val issuesByLineNumber: Map<Int, List<DotIssueInfo>> = annotationResult.issues.groupBy { it.line }
        val highestSeverityIssuesPerLine: List<DotIssueInfo> = issuesByLineNumber.mapNotNull { (_, issuesOnThisLine) ->
            issuesOnThisLine.maxByOrNull { it.severity } // Relies on HighlightSeverity being Comparable
        }

        LOG.debug("Applying ${highestSeverityIssuesPerLine.size} prioritized annotations for ${file.name}.")

        // Create annotations only for the highest severity issue found for each line.
        for (issue in highestSeverityIssuesPerLine) {
            // Adjust line number from 1-based (Graphviz) to 0-based (Document).
            val line = issue.line - 1
            if (line >= 0 && line < document.lineCount) {
                try {
                    val lineStartOffset = document.getLineStartOffset(line)
                    val lineEndOffset = document.getLineEndOffset(line)
                    val range = TextRange(lineStartOffset, lineEndOffset) // Range covers the whole line.

                    // Create annotation using the determined highest severity.
                    holder.newAnnotation(issue.severity, "Graphviz issue") // Basic description
                        .range(range)
                        .tooltip(issue.message) // Tooltip shows the message of the highest severity issue.
                        .create()
                    LOG.debug("Created annotation: ${issue.severity} on line ${issue.line}, range $range")

                } catch (e: Exception) {
                    LOG.error("Failed to apply annotation for issue on line ${issue.line} in ${file.name}", e)
                }
            } else {
                LOG.warn("Invalid line number ${issue.line} reported by Graphviz for file ${file.name}.")
            }
        }
    }
}