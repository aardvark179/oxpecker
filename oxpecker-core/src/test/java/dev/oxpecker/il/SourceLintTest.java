package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * Guard test 2 (tests/il/test_lint.py): no JavaScript in the generation layer's sources. A scan of
 * the generator classes, templates, strategies and mutators fails on any string literal that
 * contains JavaScript syntax. String contents for JavaScript string values are allowed only inside
 * {@code loadString(...)}; registration arguments (names, families, sources, docs), registry
 * lookups and exception messages are not code. Everything else (operators, keys, builtins) comes
 * from enums and registries. The allowlist is empty and stays empty.
 */
class SourceLintTest {
    static final Path IL = Path.of(System.getProperty("oxpecker.root", "..")).resolve("oxpecker-core/src/main/java/dev/oxpecker/il");

    static List<Path> linted() throws IOException {
        List<Path> out = new ArrayList<>(List.of(IL.resolve("Strategies.java"), IL.resolve("Templates.java"), IL.resolve("Mutate.java")));
        try (Stream<Path> s = Files.list(IL.resolve("generators"))) {
            s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(out::add);
        }
        return out;
    }

    static final List<String> TOKENS = List.of("{", "}", "(", ")", ";", "=>", "===", "==", "!=", "+", "*", "/", "%", "<", ">",
            "=", "!", "&", "|", "^", "~", "?", "`");
    static final Pattern MINUS = Pattern.compile("(^|[\\s\\d(\\[])-|-(\\d|\\s|$)");
    static final Pattern KEYWORD = Pattern.compile("\\b(function|new|typeof|instanceof|var|let|const|return|if|else|for|while|this|"
            + "delete|void|in|of|yield|throw|try|catch|finally|switch|case|break|continue|with|null|undefined|true|false|class|"
            + "super|arguments|eval)\\b");
    static final Set<String> ALLOWLIST = Set.of();     // stays empty

    /** Calls whose string arguments are not code: string literal contents, registration, registry lookups, messages. */
    static final Set<String> SKIP_CALLS = Set.of("loadString", "generator", "family", "source", "doc", "toggle", "syntax",
            "valueMaker", "template", "builtin", "builtinNamed", "k", "method", "has", "get", "with", "component", "equals",
            "merge", "getOrDefault", "put", "containsKey", "contains", "draw", "requireNonNull", "reject", "Reject", "Rejected",
            "BuilderError", "IllegalArgumentException", "IllegalStateException", "UnsupportedOperationException", "InvalidProgram",
            "AssertionError", "SuppressWarnings", "Named", "Key", "withProvenance", "Instruction", "startsWith", "endsWith");

    static boolean looksLikeJavascript(String s) {
        for (String t : TOKENS) if (s.contains(t)) return true;
        return MINUS.matcher(s).find() || KEYWORD.matcher(s).find();
    }

    record Literal(int line, String value) {}

    /**
     * Every string literal that is not inside a comment or a call in {@link #SKIP_CALLS}: a small
     * Java tokenizer tracking the callee before each open parenthesis.
     */
    static List<Literal> stringLiterals(String src) {
        List<int[]> reprs = reprBodies(src);
        List<Literal> out = new ArrayList<>();
        Deque<String> callees = new ArrayDeque<>();
        String lastIdent = "";
        int line = 1;
        for (int i = 0; i < src.length(); ) {
            char c = src.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (src.startsWith("//", i)) { while (i < src.length() && src.charAt(i) != '\n') i++; continue; }
            if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                for (int k = i; k < end; k++) if (src.charAt(k) == '\n') line++;
                i = end + 2;
                continue;
            }
            if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                String v = src.substring(i + 3, end);
                if (!skipped(callees)) out.add(new Literal(line, v));
                for (char ch : v.toCharArray()) if (ch == '\n') line++;
                i = end + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                StringBuilder v = new StringBuilder();
                int k = i + 1;
                while (src.charAt(k) != c) {
                    if (src.charAt(k) == '\\') { v.append(src.charAt(k + 1)); k += 2; } else v.append(src.charAt(k++));
                }
                int at = i;
                if (c == '"' && !skipped(callees) && reprs.stream().noneMatch(r -> r[0] <= at && at < r[1]))
                    out.add(new Literal(line, v.toString()));
                i = k + 1;
                lastIdent = "";
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int k = i;
                while (k < src.length() && Character.isJavaIdentifierPart(src.charAt(k))) k++;
                lastIdent = src.substring(i, k);
                i = k;
                continue;
            }
            if (c == '(') { callees.push(lastIdent); lastIdent = ""; i++; continue; }
            if (c == ')') { if (!callees.isEmpty()) callees.pop(); lastIdent = ""; i++; continue; }
            if (!Character.isWhitespace(c)) lastIdent = "";
            i++;
        }
        return out;
    }

    /** A repr or a text diff is not code: the bodies of toString, describe, diff and unifiedDiff. */
    static final Pattern REPR_METHOD = Pattern.compile("\\b(toString|describe|diff|unifiedDiff)\\s*\\([^)]*\\)\\s*\\{");

    static List<int[]> reprBodies(String src) {
        List<int[]> out = new ArrayList<>();
        var m = REPR_METHOD.matcher(src);
        while (m.find()) {
            int depth = 0, k = m.end() - 1;
            for (; k < src.length(); k++) {
                if (src.charAt(k) == '{') depth++;
                else if (src.charAt(k) == '}' && --depth == 0) break;
            }
            out.add(new int[]{m.start(), k});
        }
        return out;
    }

    static boolean skipped(Deque<String> callees) {
        for (String c : callees) if (SKIP_CALLS.contains(c)) return true;
        return false;
    }

    @ParameterizedTest
    @MethodSource("linted")
    void noJavascriptTextInGeneratorSources(Path path) throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Literal l : stringLiterals(Files.readString(path)))
            if (looksLikeJavascript(l.value) && !ALLOWLIST.contains(l.value)) offenders.add(path.getFileName() + ":" + l.line + ": " + l.value);
        assertTrue(offenders.isEmpty(), "JavaScript-looking string literals:\n  " + String.join("\n  ", offenders));
    }

    @Test void theLintCatchesTheOldHabits() {
        for (String s : List.of("throw {0}", "new Map()", "|0", "j == 1", "e instanceof TypeError", "function (x) { return x; }",
                "1/3", "-1", "a - b", "-Infinity"))
            assertTrue(looksLikeJavascript(s), s);
        assertFalse(looksLikeJavascript("operations"));
        assertFalse(looksLikeJavascript("compiler-targeted"));
    }

    @Test void theLintSeesThroughTheSources() {
        String src = "class G {\n  /** x = 1; */\n  Integer g(Draw d, Builder b) {\n    String k = \"a + b\"; // \"c + d\"\n"
                + "    return b.loadString(\"x + y\");\n  }\n}\n";
        assertEquals(List.of("a + b"), stringLiterals(src).stream().map(Literal::value).toList());
    }
}
