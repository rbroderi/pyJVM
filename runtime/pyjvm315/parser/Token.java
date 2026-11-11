package pyjvm315.parser;

/** A token with CPython tokenize-compatible type names and (line, column) positions (1-based lines, 0-based columns). */
public final class Token {
    public static final int ENDMARKER = 0, NAME = 1, NUMBER = 2, STRING = 3, NEWLINE = 4, INDENT = 5, DEDENT = 6, OP = 7,
        COMMENT = 8, NL = 9, ERRORTOKEN = 10, FSTRING_START = 11, FSTRING_MIDDLE = 12, FSTRING_END = 13,
        TSTRING_START = 14, TSTRING_MIDDLE = 15, TSTRING_END = 16;
    public static final String[] NAMES = {"ENDMARKER", "NAME", "NUMBER", "STRING", "NEWLINE", "INDENT", "DEDENT", "OP",
        "COMMENT", "NL", "ERRORTOKEN", "FSTRING_START", "FSTRING_MIDDLE", "FSTRING_END",
        "TSTRING_START", "TSTRING_MIDDLE", "TSTRING_END"};

    public final int type;
    public final String string;
    public final int startLine, startCol, endLine, endCol;
    /** Source offsets of the token (end exclusive), for slicing the original text. */
    public final int startOffset, endOffset;

    public Token(int type, String string, int startLine, int startCol, int endLine, int endCol, int startOffset, int endOffset) {
        this.type = type; this.string = string;
        this.startLine = startLine; this.startCol = startCol; this.endLine = endLine; this.endCol = endCol;
        this.startOffset = startOffset; this.endOffset = endOffset;
    }

    public String typeName() { return NAMES[type]; }
    @Override public String toString() { return typeName() + " " + string.replace("\n", "\\n") + " (" + startLine + "," + startCol + ")-(" + endLine + "," + endCol + ")"; }
}
