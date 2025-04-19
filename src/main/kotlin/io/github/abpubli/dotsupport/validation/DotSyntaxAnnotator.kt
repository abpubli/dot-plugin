package io.github.abpubli.dotsupport.validation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.engine.GraphvizException
import java.util.regex.Pattern

/**
 * An external annotator that uses Graphviz via guru.nidi.graphviz-java
 * to perform syntax validation on DOT files in the background.
 * It parses stderr output from Graphviz upon errors/warnings to create editor annotations.
 */
class DotSyntaxAnnotator : ExternalAnnotator<DotFileInfo, DotValidationResult>() {

    private companion object {
        private val LOG = Logger.getInstance(DotSyntaxAnnotator::class.java)

        /**
         * Regex to parse Graphviz error/warning lines.
         * Captures: Group 1=Type ("Error" or "Warning"), Group 2=Line Number, Group 3=Rest of message.
         * Note: This pattern might need adjustments depending on the specific Graphviz version and output format.
         */
        private val ISSUE_PATTERN: Pattern = Pattern.compile(
            "^(Error|Warning):\\s*(?:.*?:)?\\s*(?:line|near line)\\s*(\\d+)(.*)",
            Pattern.CASE_INSENSITIVE
        )
        // Limit message parsing length for performance/log readability
        private const val MAX_STDERR_PARSE_LENGTH = 5000
    }

    init {
        // Ten log pojawi się, gdy IntelliJ utworzy obiekt (instancję) annotatora.
        LOG.info("!!! DotSyntaxAnnotator INSTANCE CREATED !!!")
    }

    /**
     * Collects necessary information (file text and document) from the UI thread.
     * This runs first and requires a read action.
     * @param file The PSI file being annotated.
     * @param editor The editor instance.
     * @param hasErrors Indicates if the file has syntactic errors according to IntelliJ's parser (not used here).
     * @return DotFileInfo containing text and document, or null if annotation should be skipped.
     */
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): DotFileInfo? {
        LOG.info("!!! DotSyntaxAnnotator: collectInformation CALLED for ${file.name} !!!")
        return DotFileInfo(file.text, editor.document)
    }

    /**
     * Performs the potentially slow Graphviz validation in a background thread.
     * It attempts to render the DOT source to a simple format (like DOT itself)
     * to trigger Graphviz's parser and catches GraphvizException to extract error details.
     * @param collectedInfo Data gathered by [collectInformation].
     * @return DotValidationResult containing a list of found issues, or null.
     */
    override fun doAnnotate(collectedInfo: DotFileInfo?): DotValidationResult? {
        LOG.info("!!! DotSyntaxAnnotator: doAnnotate CALLED (info is null: ${collectedInfo == null}) !!!")
        if (collectedInfo == null) {
            LOG.debug("No information collected, skipping annotation.")
            return null
        }
        LOG.debug("Starting background annotation for document length ${collectedInfo.text.length}")

        val issues = mutableListOf<DotIssueInfo>()
        try {
            // Execute Graphviz using a format that primarily involves parsing, like Format.DOT.
            // We are interested in the exception, not the actual output string here.
            Graphviz.fromString(collectedInfo.text)
                .render(Format.DOT)
                .toString() // Force execution

            LOG.debug("Graphviz execution successful (no critical exception).")

        } catch (e: GraphvizException) {
            // Graphviz process likely failed or reported issues via stderr.
            LOG.warn("GraphvizException caught during annotation: ${e.message?.take(MAX_STDERR_PARSE_LENGTH)}")
            val errorMessage = e.message ?: ""
            // Parse the stderr captured in the exception message line by line.
            errorMessage.lines().forEach { line ->
                val matcher = ISSUE_PATTERN.matcher(line.trim())
                if (matcher.find()) {
                    try {
                        val type = matcher.group(1)
                        val lineNumber = matcher.group(2)?.toIntOrNull()
                        val message = line.trim() // Use the full line as the detailed message

                        if (lineNumber != null) {
                            val severity = when (type.lowercase()) {
                                "error" -> HighlightSeverity.ERROR
                                "warning" -> HighlightSeverity.WARNING
                                else -> HighlightSeverity.WEAK_WARNING // Default for unknown types
                            }
                            issues.add(DotIssueInfo(severity, lineNumber, message))
                            LOG.debug("Found issue: $type on line $lineNumber")
                        }
                    } catch (parseEx: Exception) {
                        // Log if a line matched the pattern but failed internal parsing (e.g., Int conversion).
                        LOG.warn("Failed to parse Graphviz issue line content: '$line'", parseEx)
                    }
                }
            }
        } catch (e: Exception) {
            // Catch unexpected errors during the process.
            LOG.error("Unexpected exception during Graphviz annotation: ${e.message}", e)
            // Optionally, add a generic error annotation for internal failures.
            // issues.add(DotIssueInfo(HighlightSeverity.ERROR, 1, "Internal error during validation: ${e.message}"))
        }

        return DotValidationResult(issues)
    }

    /**
     * Applies the validation results (creating annotations) back in the UI thread.
     * Ensures only one annotation (with the highest severity) is shown per line if multiple issues are reported.
     * @param file The PSI file being annotated.
     * @param annotationResult The validation results from the background task ([doAnnotate]).
     * @param holder The object used to create annotations in the editor.
     */
    override fun apply(file: PsiFile, annotationResult: DotValidationResult?, holder: AnnotationHolder) {
        if (annotationResult == null || annotationResult.issues.isEmpty()) {
            LOG.debug("No issues found or annotation result is null for ${file.name}.")
            return
        }
        LOG.debug("Processing ${annotationResult.issues.size} raw issues for ${file.name}.")

        val document = file.viewProvider.document
        if (document == null) {
            // Essential to log this error, as we cannot proceed without the document.
            LOG.error("Could not get document for file ${file.name} in apply phase.")
            return
        }

        // Prioritize issues: If multiple issues exist for the same line,
        // select only the one with the highest severity (e.g., Error > Warning).
        val issuesByLineNumber: Map<Int, List<DotIssueInfo>> = annotationResult.issues.groupBy { it.line }
        val highestSeverityIssuesPerLine: List<DotIssueInfo> = issuesByLineNumber.mapNotNull { (_, issuesOnThisLine) ->
            // HighlightSeverity implements Comparable, allowing maxByOrNull to find the most severe issue.
            issuesOnThisLine.maxByOrNull { it.severity }
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

                    // Create annotation using the highest severity found for this line.
                    holder.newAnnotation(issue.severity, "Graphviz issue")
                        .range(range)
                        .tooltip(issue.message) // Tooltip shows the message of the highest severity issue.
                        .create()
                    LOG.debug("Created annotation: ${issue.severity} on line ${issue.line}, range $range")

                } catch (e: Exception) {
                    // Log exceptions during annotation creation process for a specific issue.
                    LOG.error("Failed to apply annotation for issue on line ${issue.line} in ${file.name}", e)
                }
            } else {
                // Log if Graphviz reported an invalid line number relative to the document.
                LOG.warn("Invalid line number ${issue.line} reported by Graphviz for file ${file.name}.")
            }
        }
    }
}