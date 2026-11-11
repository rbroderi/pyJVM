// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Debug/verification CLI: prints tokens (one per line) for each file argument, tab separated. */
public final class TokenDump {
    public static void main(String[] args) throws Exception {
        java.io.PrintStream out = new java.io.PrintStream(System.out, false, StandardCharsets.UTF_8);
        for (String arg : args) {
            out.println("#FILE\t" + arg);
            String text;
            try { text = Files.readString(Path.of(arg), StandardCharsets.UTF_8); }
            catch (java.io.IOException e) { out.println("#ERROR\tUnicodeDecodeError\t" + e.getMessage() + "\t0"); continue; }
            try {
                List<Token> tokens = Tokenizer.tokenize(text);
                for (Token t : tokens) {
                    if (t.type == Token.ERRORTOKEN) continue;
                    out.println(t.typeName() + "\t" + t.string.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                        + "\t" + t.startLine + "," + t.startCol + "\t" + t.endLine + "," + t.endCol);
                }
            } catch (PySyntaxError e) {
                out.println("#ERROR\t" + e.kind + "\t" + e.getMessage() + "\t" + e.line);
            }
        }
        out.flush();
    }
}
