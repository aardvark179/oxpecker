package dev.oxpecker.corpora;

import dev.oxpecker.util.Py;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rhino's own JS tests as replay corpora (brief §10.2):
 * <ul>
 *   <li>{@code rhino-jstests}: tests/testsrc/jstests/**&#47;*.js|*.jstest; {@code load("testsrc/x.js")}
 *       lines are inlined.</li>
 *   <li>{@code rhino-mozilla}: the legacy Mozilla suite tests/testsrc/tests/&lt;suite&gt;/&lt;dir&gt;/*.js,
 *       each prefixed with the shell.js chain (tests/shell.js, &lt;suite&gt;/shell.js,
 *       &lt;dir&gt;/shell.js) like jsDriver does. Its reportCompare() prints PASSED/FAILED lines,
 *       so the output comparison is value-level.</li>
 * </ul>
 * Doctests (tests/testsrc/doctests) use a shell transcript format and are not replayed.
 */
public final class RhinoTests {
    private RhinoTests() {}

    /** Suites needing LiveConnect ({@code Packages}, {@code java.*}), E4X, or a browser; and driver files. */
    public static final Set<String> MOZILLA_SKIP_DIRS = Set.of("e4x", "lc2", "lc3", "src");
    public static final Set<String> MOZILLA_SKIP_FILES = Set.of("shell.js", "browser.js", "template.js", "importList.js",
            "menuhead.js", "userhookeach.js", "js-test-driver-begin.js", "js-test-driver-end.js");

    private static final Pattern LOAD = Py.re("^\\s*load\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*;?\\s*$", Pattern.MULTILINE);

    public static Path testsrc(Path rhino) {
        return rhino.resolve("tests").resolve("testsrc");
    }

    static String inlineLoads(Path rhino, String source, Path base, int depth) {
        Matcher m = LOAD.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rel = m.group(1);
            Path f = !rel.startsWith("testsrc/") ? base.resolve(rel) : rhino.resolve("tests").resolve(rel);
            String repl;
            if (!Files.exists(f) || depth > 3) repl = "// oxpecker: could not inline load(" + Py.repr(rel) + ")";
            else repl = "// ---- load " + rel + "\n" + inlineLoads(rhino, Test262.readReplace(f), base, depth + 1);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static Stream<Case> jstests(String filter, Integer limit) {
        return jstests(Test262.rhino(), filter, limit);
    }

    public static Stream<Case> jstests(Path rhino, String filter, Integer limit) {
        Path jstests = testsrc(rhino).resolve("jstests");
        List<Path> files;
        try (Stream<Path> s = Files.walk(jstests)) {
            files = s.filter(p -> {
                String suf = Py.suffix(p.getFileName().toString());
                return (suf.equals(".js") || suf.equals(".jstest")) && Files.isRegularFile(p);
            }).sorted(Py.PATH_ORDER).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files.stream().filter(f -> {
            String rel = Test262.rel(jstests, f);
            if (filter != null && !filter.isEmpty() && !rel.startsWith(filter)) return false;
            return !f.getFileName().toString().contains("helpers");
        }).limit(limit == null || limit == 0 ? Long.MAX_VALUE : limit).map(f -> {
            String rel = Test262.rel(jstests, f);
            String src = inlineLoads(rhino, Test262.readReplace(f), rhino.resolve("tests"), 0);
            return new Case(rel, src, rel, false, 250, Map.of("corpus", "rhino-jstests"));
        });
    }

    public static Stream<Case> mozilla(String filter, Integer limit) {
        return mozilla(Test262.rhino(), filter, limit);
    }

    public static Stream<Case> mozilla(Path rhino, String filter, Integer limit) {
        Path mozilla = testsrc(rhino).resolve("tests");
        Path rootShell = mozilla.resolve("shell.js");
        return Test262.rglob(mozilla, ".js").stream().filter(f -> {
            Path rel = mozilla.relativize(f);
            if (MOZILLA_SKIP_FILES.contains(f.getFileName().toString()) || MOZILLA_SKIP_DIRS.contains(rel.getName(0).toString())
                    || rel.getNameCount() < 2) return false;
            return filter == null || filter.isEmpty() || Test262.rel(mozilla, f).startsWith(filter);
        }).limit(limit == null || limit == 0 ? Long.MAX_VALUE : limit).map(f -> {
            Path rel = mozilla.relativize(f);
            List<Path> chain = new ArrayList<>(List.of(rootShell));
            for (int i = 1; i < rel.getNameCount(); i++) chain.add(mozilla.resolve(rel.subpath(0, i)).resolve("shell.js"));
            List<String> srcParts = new ArrayList<>();
            for (Path shell : chain) {
                if (Files.exists(shell)) srcParts.add("// ---- " + Test262.rel(mozilla, shell) + "\n" + Test262.readReplace(shell));
            }
            String relStr = Test262.rel(mozilla, f);
            srcParts.add("// ---- " + relStr + "\n" + Test262.readReplace(f));
            // The driver's reportCompare counts and prints; make the summary deterministic.
            srcParts.add("\nif (typeof jsTestDriverEnd === 'function') { try { jsTestDriverEnd(); } catch (e) { print('driver-end threw ' + e); } }");
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("corpus", "rhino-mozilla");
            tags.put("suite", rel.getName(0).toString());
            return new Case(relStr, String.join("\n", srcParts), relStr, false, 250, tags);
        });
    }
}
