package io.github.abpubli.dotsupport.parsing

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.abpubli.dotsupport.filetype.DotLanguage
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.PsiBuilder // Potrzebny import

class DotParserDefinition : ParserDefinition {

    companion object {
        val FILE: IFileElementType = IFileElementType(DotLanguage)
    }

    override fun createLexer(project: Project?): Lexer = DotLexerAdapter()

    override fun getWhitespaceTokens(): TokenSet = TokenSet.EMPTY
    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createParser(project: Project?): PsiParser {
        return PsiParser { root, builder ->
            val rootMarker = builder.mark()
            // Pętla konsumująca wszystkie tokeny zwrócone przez lexer
            // (w naszym przypadku będzie tylko jeden token TEXT)
            while (!builder.eof()) {
                val tokenMarker = builder.mark()
                // Odczytaj i przetwórz następny token
                builder.advanceLexer()
                // Oznacz ten token jako element typu TEXT (lub inny, jeśli lexer by rozróżniał)
                // Używamy typu tokenu zwróconego przez lexer, jeśli jest dostępny,
                // w przeciwnym razie można by użyć domyślnego.
                val tokenType = builder.tokenType
                if (tokenType != null) {
                    tokenMarker.done(tokenType) // Użyj typu z lexera (tu: TEXT)
                } else {
                    tokenMarker.drop() // Jeśli nie ma tokenu (np. błąd), porzuć marker
                }
            }
            // Zakończ główny węzeł pliku
            rootMarker.done(root) // root to typ FILE przekazany do metody
            builder.treeBuilt
        }
    }

    override fun getFileNodeType(): IFileElementType = FILE

    override fun createElement(node: ASTNode): PsiElement {
        return ASTWrapperPsiElement(node) // Nadal OK dla minimum
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile {
        return DotPsiFile(viewProvider) // Używamy naszej klasy PsiFile
    }

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?): ParserDefinition.SpaceRequirements {
        return ParserDefinition.SpaceRequirements.MAY
    }
}