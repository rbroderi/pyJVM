package pyjvm315.parser;

/** SyntaxError / IndentationError / TabError raised by the tokenizer or parser. */
public final class PySyntaxError extends RuntimeException {
    public final String kind;      // "SyntaxError", "IndentationError", "TabError"
    public final int line, column; // 1-based line; 1-based column as CPython reports offset
    public PySyntaxError(String kind, String message, int line, int column) {
        super(message);
        this.kind = kind; this.line = line; this.column = column;
    }
}
