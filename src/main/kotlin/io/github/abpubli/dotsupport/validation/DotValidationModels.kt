package io.github.abpubli.dotsupport.validation

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document

/**
 * Holds information collected in the UI thread for the background annotator task.
 * @param text The full text content of the file.
 * @param document The document object, needed for later line mapping in the apply phase.
 */
data class DotFileInfo(val text: String, val document: Document)

/**
 * Represents the result of the background validation task.
 * @param issues A list of issues (errors or warnings) found.
 */
data class DotValidationResult(val issues: List<DotIssueInfo>)

/**
 * Describes a single issue (error or warning) identified by Graphviz.
 * @param severity The severity level (ERROR, WARNING).
 * @param line The line number (1-based) reported by Graphviz.
 * @param message The full, original message string from Graphviz for this issue.
 */
data class DotIssueInfo(
    val severity: HighlightSeverity,
    val line: Int,
    val message: String
)