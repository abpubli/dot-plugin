package io.github.abpubli.dotsupport.parsing

import com.intellij.extapi.psi.PsiFileBase          // Potrzebny import
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElementVisitor
import io.github.abpubli.dotsupport.filetype.DotFileType  // Import Twojego FileType
import io.github.abpubli.dotsupport.filetype.DotLanguage  // Import Twojego Language

/**
 * Minimal implementation of PsiFile for DOT language files.
 * Inherits from PsiFileBase, which is a standard practice.
 */
class DotPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, DotLanguage) {

    // Returns the FileType object associated with this PSI file.
    override fun getFileType(): FileType = DotFileType // Reference to the DotFileType object

    // Returns the name of the file type (used e.g. in the UI).
    override fun toString(): String = "Dot File"

    /**
     * Method required by the Visitor pattern, used by various IntelliJ analysis tools.
     * The standard file implementation calls visitor.visitFile(this).
     */
    override fun accept(visitor: PsiElementVisitor) {
        visitor.visitFile(this)
    }
}