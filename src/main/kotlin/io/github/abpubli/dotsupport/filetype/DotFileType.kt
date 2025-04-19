package io.github.abpubli.dotsupport.filetype

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon // Import needed for the getIcon method signature

/**
 * Defines the file type for Graphviz DOT language files.
 * This associates .dot files with the DotLanguage and provides basic metadata.
 */
object DotFileType : LanguageFileType(DotLanguage) {
    override fun getName(): String = "DOT file"
    override fun getDescription(): String = "Graphviz DOT language file"
    override fun getDefaultExtension(): String = "dot"
    override fun getIcon(): Icon? = null
}