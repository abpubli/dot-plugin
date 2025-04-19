// Utwórz plik np. DotPsiFile.kt w pakiecie io.github.abpubli.dotsupport.parsing

package io.github.abpubli.dotsupport.parsing // Upewnij się, że pakiet jest poprawny

import com.intellij.extapi.psi.PsiFileBase          // Potrzebny import
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElementVisitor
import io.github.abpubli.dotsupport.filetype.DotFileType  // Import Twojego FileType
import io.github.abpubli.dotsupport.filetype.DotLanguage  // Import Twojego Language

/**
 * Minimalna implementacja PsiFile dla plików języka DOT.
 * Dziedziczy z PsiFileBase, co jest standardową praktyką.
 */
class DotPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, DotLanguage) {

    // Zwraca obiekt FileType powiązany z tym plikiem PSI.
    override fun getFileType(): FileType = DotFileType // Odwołanie do obiektu DotFileType

    // Zwraca nazwę typu pliku (używane np. w UI).
    override fun toString(): String = "DOT File"

    /**
     * Metoda wymagana przez wzorzec Visitor, używana przez różne narzędzia analizy IntelliJ.
     * Standardowa implementacja dla pliku woła visitor.visitFile(this).
     */
    override fun accept(visitor: PsiElementVisitor) {
        visitor.visitFile(this)
    }
}