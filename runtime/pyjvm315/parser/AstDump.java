package pyjvm315.parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verification CLI: parses each file and prints the canonical JSON tree (or the syntax error). */
public final class AstDump {
    public static void main(String[] args) throws Exception {
        java.io.PrintStream out = new java.io.PrintStream(System.out, false, StandardCharsets.UTF_8);
        for (String arg : args) {
            out.println("#FILE\t" + arg);
            String text;
            try { text = Files.readString(Path.of(arg), StandardCharsets.UTF_8); }
            catch (java.io.IOException e) { out.println("#ERROR\tUnicodeDecodeError\t" + e.getMessage() + "\t0\t0"); continue; }
            try { out.println(AstNode.toJson(Parser.parse(text, "exec"))); }
            catch (PySyntaxError e) { out.println("#ERROR\t" + e.kind + "\t" + e.getMessage() + "\t" + e.line + "\t" + e.column); }
            catch (RuntimeException e) { out.println("#CRASH\t" + e); }
        }
        out.flush();
    }
}
