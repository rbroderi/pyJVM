// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** Where a SyntaxError happened: the attributes CPython exposes as {@code filename}, {@code lineno}, {@code offset}, {@code text}. */
final class SyntaxInfo {
    final String filename, text;
    final long lineno, offset, endLineno, endOffset;

    SyntaxInfo(String filename, long lineno, long offset, String text) {
        this.filename = filename; this.lineno = lineno; this.offset = offset; this.text = text;
        this.endLineno = lineno; this.endOffset = offset;
    }

    /** The error CPython raises for a parser failure at {@code line}:{@code column} of {@code source}. */
    static PyException error(String kind, String message, String filename, String source, int line, int column) {
        PyException e = new PyException(kind, message);
        String text = null;
        if (source != null && line >= 1) {
            String[] lines = source.split("\r\n|\r|\n", -1);
            if (line <= lines.length) text = lines[line - 1] + "\n";
        }
        e.syntax = new SyntaxInfo(filename, line, column, text);
        return e;
    }

    /** str(SyntaxError): the message with its location, as CPython's SyntaxError.__str__ renders it. */
    String describe(String message) {
        String name = filename;
        if (name != null) {
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) name = name.substring(slash + 1);
        }
        if (name != null && lineno > 0) return message + " (" + name + ", line " + lineno + ")";
        if (lineno > 0) return message + " (line " + lineno + ")";
        if (name != null) return message + " (" + name + ")";
        return message;
    }

    Object attribute(String name, Object message) {
        return switch (name) {
            case "msg" -> message;
            case "filename" -> filename;
            case "lineno" -> lineno;
            case "offset" -> offset;
            case "text" -> text;
            case "end_lineno" -> endLineno;
            case "end_offset" -> endOffset;
            case "print_file_and_line" -> null;
            default -> NONE;
        };
    }

    static final Object NONE = new Object();
}
