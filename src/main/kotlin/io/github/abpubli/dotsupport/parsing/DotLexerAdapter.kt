package io.github.abpubli.dotsupport.parsing // Użyj swojego pakietu

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Poprawiony minimalny Lexer. Zwraca JEDEN token typu TEXT
 * obejmujący całą zawartość pliku.
 */
class DotLexerAdapter : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var state = 0 // 0 = początek, 1 = zwrócono TEXT, 2 = koniec

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.state = 0 // Resetuj stan przy starcie
        // Ważne: Nie ustawiamy od razu currentOffset na koniec
    }

    override fun getState(): Int = state

    override fun getTokenType(): IElementType? {
        // Zwróć TEXT, jeśli jesteśmy w stanie początkowym i jest co lexować
        return if (state == 0 && startOffset < endOffset) DotTokenTypes.TEXT else null
    }

    override fun getTokenStart(): Int = startOffset // Początek bufora

    override fun getTokenEnd(): Int = endOffset // Koniec bufora (dla tego jednego tokenu)

    override fun advance() {
        // Przesuwamy się do stanu "zwrócono token" lub "koniec"
        if (state == 0) {
            state = 1
        } else {
            state = 2
        }
    }

    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset
}