package io.github.abpubli.dotsupport.validation

// Imports for Graphviz rendering and exception
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.abpubli.dotsupport.external.runDotCommand
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
            "^(Error|Warning):.*? (?:line|near line)\\s*(\\d+)(.*)",
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

        LOG.debug("Starting background annotation (direct 'dot -Tcanon' execution) for document text length ${collectedInfo.text.length}")

        val issues = mutableListOf<DotIssueInfo>()
        var stderrOutput: String? = null // for logging in case of problems

        try {
            // call dot with a format that forces parsing, such as canon (or dot)
            // use a shorter timeout for validation than for image rendering
            val result = runDotCommand(collectedInfo.text, "canon", 5) // 5 second timeout

            stderrOutput = result.errorOutput // keep stderr for logging/parsing

            // 1. check execution errors (e.g., 'dot' not found)
            if (result.executionError != null) {
                LOG.error("Failed to execute 'dot' command for validation.", result.executionError)
                issues.add(
                    DotIssueInfo(
                        HighlightSeverity.ERROR,
                        1,
                        "Failed to execute Graphviz 'dot'. Is it installed and in PATH? Error: ${result.executionError.message}"
                    )
                )
            }
            // 2. check timeout
            else if (result.timedOut) {
                LOG.warn("'dot' command timed out during validation.")
                issues.add(DotIssueInfo(HighlightSeverity.WARNING, 1, "Graphviz 'dot' validation timed out."))
            }
            // 3. if there were no execution/timeout errors, analyze stderr and exit code
            else {
                // parse stderr for errors/warnings
                if (!stderrOutput.isNullOrBlank()) {
                    stderrOutput.lines().forEach { line ->
                        val trimmedLine = line.trim()
                        val matcher = ISSUE_PATTERN.matcher(trimmedLine)
                        if (matcher.find()) {
                            try {
                                val type = matcher.group(1)
                                val lineNumberStr = matcher.group(2)
                                val message = trimmedLine
                                val lineNumber = lineNumberStr?.toIntOrNull()

                                if (lineNumber != null) {
                                    val severity = when (type.lowercase()) {
                                        "error" -> HighlightSeverity.ERROR
                                        "warning" -> HighlightSeverity.WARNING
                                        else -> HighlightSeverity.WARNING
                                    }
                                    val issueInfo = DotIssueInfo(severity, lineNumber, message)
                                    issues.add(issueInfo)
                                    LOG.debug("Issue added successfully? List size now: ${issues.size}")
                                } else {
                                    LOG.warn("to parse line number (toIntOrNull failed).")
                                }
                            } catch (parseEx: Exception) {
                                LOG.warn(
                                    "Exception during parsing matched groups for line: '$trimmedLine'",
                                    parseEx
                                )
                            }
                        } else {
                            LOG.warn("Regex did NOT match line: '$trimmedLine'") // Sprawdź, jeśli regex nie pasuje
                        }
                    }
                    LOG.debug("Parsed ${issues.size} specific issues from stderr.")
                }

                // In addition, if the output code is different from 0, and we have not parsed any ERROR errors,
                // add a generic error. Sometimes dot ends up with an error without a specific message on stderr.
                if (result.exitCode != 0 && issues.none { it.severity == HighlightSeverity.ERROR }) {
                    val genericErrorMessage = "Graphviz 'dot' failed with exit code ${result.exitCode}."
                    LOG.warn(genericErrorMessage + (if (!stderrOutput.isNullOrBlank()) " Stderr: $stderrOutput" else ""))
                    issues.add(
                        DotIssueInfo(
                            HighlightSeverity.ERROR,
                            1,
                            genericErrorMessage + " Check logs for details."
                        )
                    )
                }
                // Log stderr if it was not empty, but no specific problems found
                if (!stderrOutput.isNullOrBlank() && issues.isEmpty() && result.exitCode != 0) {
                    LOG.warn("dot stderr for exit code ${result.exitCode} (no specific issues parsed): $stderrOutput")
                }
            }

        } catch (e: Exception) {
            // catch other unexpected exceptions during the call process itself
            LOG.error("Unexpected exception during direct 'dot' execution handling.", e)
            issues.add(DotIssueInfo(HighlightSeverity.ERROR, 1, "Internal error during validation: ${e.message}"))
        }
        LOG.debug("doAnnotate returning result with ${issues.size} issues.") // Zmień na WARN/ERROR
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
                    val shortMessage = issue.message.lines().firstOrNull()?.trim()?.take(120) ?: "Graphviz issue"
                    holder.newAnnotation(issue.severity, shortMessage)
                        .range(range)
                        .tooltip(issue.message)
                        .create()
                    LOG.debug("Created annotation: ${issue.severity} on line ${issue.line}, range $range")

                } catch (e: Exception) {
                    LOG.error("Failed to apply annotation for issue on line ${issue.line}", e)
                }
            } else {
                LOG.warn("Invalid line number ${issue.line} reported by Graphviz for file ${file.name}.")
            }
        }
    }
}