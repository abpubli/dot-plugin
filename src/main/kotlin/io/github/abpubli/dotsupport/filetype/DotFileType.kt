package io.github.abpubli.dotsupport.filetype

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import io.github.abpubli.dotsupport.icons.DotSupportIcons
import javax.swing.Icon // Import needed for the getIcon method signature

/**
 * Defines the file type for Graphviz DOT language files.
 * This associates .dot files with the DotLanguage and provides basic metadata.
 */
object DotFileType : LanguageFileType(DotLanguage) {
    override fun getName(): String = "Dot file"
    override fun getDescription(): String = "Graphviz DOT language file"
    override fun getDefaultExtension(): String = "dot"
    override fun getIcon(): Icon? = IconLoader.getIcon("/icons/dotFile.svg", DotSupportIcons::class.java)
}