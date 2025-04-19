package io.github.abpubli.dotsupport.parsing // Użyj swojego pakietu

import com.intellij.psi.tree.IElementType
import io.github.abpubli.dotsupport.filetype.DotLanguage

// Definiujemy prosty typ tokenu dla całej zawartości pliku
object DotTokenTypes {
    @JvmStatic // Dobra praktyka dla obiektów używanych w Java/platformie
    val TEXT: IElementType = IElementType("DOT_TEXT", DotLanguage)
}