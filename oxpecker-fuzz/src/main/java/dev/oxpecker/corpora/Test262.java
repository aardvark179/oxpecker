package dev.oxpecker.corpora;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.util.Py;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * test262 replay (brief §10.1): the suite vendored in Rhino, filtered the way Rhino's own
 * Test262SuiteTest filters it, each test prefixed with the harness includes it declares, run in
 * both modes and compared at value level.
 * <pre>
 *     oxpecker corpus test262 --filter language/statements/try --limit 200
 *     oxpecker corpus test262 --known-divergences        # files test262.properties tags per mode
 * </pre>
 */
public final class Test262 {
    private Test262() {}

    /** The Rhino checkout beside this repository (../rhino). */
    public static Path rhino() {
        return Oxpecker.root().getParent().resolve("rhino");
    }

    public static Path test262() {
        return rhino().resolve("tests").resolve("test262");
    }

    public static Path properties() {
        return rhino().resolve("tests").resolve("testsrc").resolve("test262.properties");
    }

    /** Mirrors Test262SuiteTest.UNSUPPORTED_FEATURES at the pinned commit. */
    public static final Set<String> UNSUPPORTED_FEATURES = Set.of("Atomics", "IsHTMLDDA", "async-functions",
            "async-iteration", "class", "class-fields-private", "class-fields-public", "SharedArrayBuffer",
            "tail-call-optimization", "Temporal", "upsert", "u180e");
    /** Flags our harness cannot honour: async tests need $DONE plumbing, modules aren't supported. */
    public static final Set<String> UNSUPPORTED_FLAGS = Set.of("async", "module", "CanBlockIsFalse", "CanBlockIsTrue");
    /** Features whose tests only make sense with a real multi-realm / agent host. */
    public static final Set<String> UNSUPPORTED_HOST_FEATURES = Set.of("cross-realm");

    /** Minimal $262 host object (Rhino's runner provides a Java one). createRealm is unsupported, so
     * tests needing it throw identically in both modes. */
    public static final String SHIM_262 = """
            var $262 = {
              global: this,
              gc: function () {},
              evalScript: function (src) { return (0, eval)(src); },
              detachArrayBuffer: function (buf) { throw new Error("detachArrayBuffer unsupported in oxpecker"); },
              createRealm: function () { throw new Error("createRealm unsupported in oxpecker"); },
              agent: {}
            };
            """;

    /** test262 metadata: includes/flags/features, and negative (null if absent). */
    public record Meta(List<String> includes, List<String> flags, List<String> features, Map<String, String> negative) {}

    private static final Pattern META_RE = Py.re("/\\*---(.*?)---\\*/", Pattern.DOTALL);

    /** Enough YAML for test262 metadata: includes/flags/features (inline or block lists), negative. */
    public static Meta parseFrontmatter(String source) {
        Matcher m = META_RE.matcher(source);
        List<String> includes = new ArrayList<>(), flags = new ArrayList<>(), features = new ArrayList<>();
        Map<String, String> negative = null;
        if (!m.find()) return new Meta(includes, flags, features, null);
        List<String> lines = Py.splitlines(m.group(1));
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String stripped = Py.strip(line);
            i++;
            if (stripped.isEmpty() || stripped.startsWith("#")
                    || (line.startsWith(" ") && !stripped.contains(":") && !stripped.startsWith("-"))) continue;
            String[] kv = Py.partition(stripped, ":");
            String key = Py.strip(kv[0]), rest = Py.strip(kv[2]);
            if (key.equals("includes") || key.equals("flags") || key.equals("features")) {
                List<String> items = new ArrayList<>();
                if (rest.startsWith("[")) {
                    for (String x : Py.split(Py.strip(rest, "[]"), ",")) {
                        if (!Py.strip(x).isEmpty()) items.add(Py.strip(Py.strip(x), "'\""));
                    }
                } else {
                    while (i < lines.size() && Py.strip(lines.get(i)).startsWith("-")) {
                        items.add(Py.strip(Py.strip(Py.strip(lines.get(i)).substring(1)), "'\""));
                        i++;
                    }
                }
                switch (key) {
                    case "includes" -> includes = items;
                    case "flags" -> flags = items;
                    default -> features = items;
                }
            } else if (key.equals("negative")) {
                Map<String, String> neg = new LinkedHashMap<>();
                while (i < lines.size() && lines.get(i).startsWith(" ") && lines.get(i).contains(":")) {
                    String[] p = Py.partition(Py.strip(lines.get(i)), ":");
                    neg.put(Py.strip(p[0]), Py.strip(p[2]));
                    i++;
                }
                negative = neg;
            }
        }
        return new Meta(includes, flags, features, negative);
    }

    private static final Pattern PROP_LINE = Py.re("^(~|\\s*[!#]\\s*|\\s+)?(\\S+)(?:[^\\S\\r\\n]+(.*))?$");

    /** (excluded top-level folders, {relative test path: comment}) for the files Rhino marks as
     * failing. The comment carries the per-mode tags ("compiled", "{interpreted,debugger}", ...). */
    public record Properties(Set<String> excluded, Map<String, String> failing) {}

    public static Properties parseProperties() {
        return parseProperties(properties());
    }

    public static Properties parseProperties(Path path) {
        Set<String> excluded = new HashSet<>();
        Map<String, String> failing = new LinkedHashMap<>();
        String top = null;
        for (String raw : Py.splitlines(read(path))) {
            if (Py.strip(raw).isEmpty() || Py.lstrip(raw).startsWith("#")) continue;
            Matcher m = PROP_LINE.matcher(raw);
            if (!m.matches()) continue;
            String marker = m.group(1), p = m.group(2), comment = Py.strip(m.group(3) == null ? "" : m.group(3));
            if (marker == null || marker.equals("~")) {
                top = p;
                if ("~".equals(marker)) excluded.add(p);
            } else if (top != null && p.endsWith(".js")) {
                failing.put(top + "/" + p, comment);
            }
            // sub-folder summary lines ("RegExp/legacy-accessors/index 4/4 (100.0%)") carry no files
        }
        return new Properties(excluded, failing);
    }

    /** Modes named in a test262.properties comment, e.g. 'compiled' or '{interpreted,debugger}'. */
    public static Set<String> modeTags(String comment) {
        Set<String> tags = new HashSet<>();
        String c = comment.replace("{", " ").replace("}", " ").replace(",", " ");
        for (String tok : pySplitWhitespace(c)) {
            if (tok.equals("interpreted") || tok.equals("compiled") || tok.equals("debugger")) tags.add(tok);
        }
        return tags;
    }

    public static Map<String, Set<String>> knownDivergences() {
        return knownDivergences(properties());
    }

    /** Files Rhino marks as failing in some modes only: {relative path: failing modes}. */
    public static Map<String, Set<String>> knownDivergences(Path properties) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : parseProperties(properties).failing().entrySet()) {
            Set<String> tags = modeTags(e.getValue());
            if (!tags.isEmpty() && !tags.equals(Set.of("interpreted", "compiled", "debugger"))) out.put(e.getKey(), tags);
        }
        return out;
    }

    public static String buildSource(Path testPath, Meta meta, boolean strict) {
        return buildSource(test262(), testPath, meta, strict);
    }

    public static String buildSource(Path test262, Path testPath, Meta meta, boolean strict) {
        List<String> parts = new ArrayList<>();
        if (strict) parts.add("\"use strict\";");
        String body = readStrict(testPath);
        if (meta.flags().contains("raw")) return (parts.isEmpty() ? "" : String.join("\n", parts) + "\n") + body;
        parts.add(SHIM_262);
        List<String> incs = new ArrayList<>(List.of("assert.js", "sta.js"));
        for (String inc : meta.includes()) if (!inc.equals("assert.js") && !inc.equals("sta.js")) incs.add(inc);
        for (String inc : incs) {
            Path f = test262.resolve("harness").resolve(inc);
            if (Files.exists(f)) parts.add("// ---- include " + inc + "\n" + readStrict(f));
        }
        parts.add("// ---- " + test262.resolve("test").relativize(testPath));
        parts.add(body);
        return String.join("\n", parts);
    }

    public static Stream<Case> cases(String filter, Integer limit, boolean strictVariants, boolean onlyKnown)
            throws FileNotFoundException {
        return cases(test262(), properties(), filter, limit, strictVariants, onlyKnown);
    }

    /** The cases of the suite under {@code test262} (its test/ and harness/), lazily, in sorted
     * path order; {@code limit} counts files (a file may give a sloppy and a strict case). */
    public static Stream<Case> cases(Path test262, Path properties, String filter, Integer limit, boolean strictVariants,
                                     boolean onlyKnown) throws FileNotFoundException {
        Path root = test262.resolve("test");
        if (!Files.exists(root)) {
            throw new FileNotFoundException("test262 not found at " + root + "; run `git submodule update --init` in ../rhino");
        }
        Properties props = parseProperties(properties);
        Set<String> excluded = props.excluded();
        Map<String, String> failing = props.failing();
        Map<String, Set<String>> known = knownDivergences(properties);
        Stream<Case> s = rglob(root, ".js").stream().<Item>flatMap(f -> {
            String rel = rel(root, f);
            if (f.getFileName().toString().contains("_FIXTURE")) return null;
            if (filter != null && !filter.isEmpty() && !rel.startsWith(filter)) return null;
            if (onlyKnown && !known.containsKey(rel)) return null;
            if (excluded.stream().anyMatch(ex -> rel.startsWith(ex + "/")) && !known.containsKey(rel)) return null;
            Meta meta = parseFrontmatter(readReplace(f));
            if (meta.features().stream().anyMatch(x -> UNSUPPORTED_FEATURES.contains(x) || UNSUPPORTED_HOST_FEATURES.contains(x))) return null;
            if (meta.flags().stream().anyMatch(UNSUPPORTED_FLAGS::contains)) return null;
            return Stream.of(new Item(f, rel, meta));
        }).limit(limit == null || limit == 0 ? Long.MAX_VALUE : limit).flatMap(x -> {
            Path f = x.f();
            String rel = x.rel();
            Meta meta = x.meta();
            List<Boolean> variants = strictVariants ? List.of(false, true) : List.of(false);
            if (meta.flags().contains("onlyStrict")) variants = List.of(true);
            else if (meta.flags().contains("noStrict") || meta.flags().contains("raw")) variants = List.of(false);
            Map<String, String> tags = new LinkedHashMap<>();
            if (failing.containsKey(rel)) tags.put("rhinoFailing", failing.get(rel));
            if (known.containsKey(rel)) tags.put("knownDivergence", String.join(",", Py.sorted(known.get(rel))));
            if (meta.negative() != null && !meta.negative().isEmpty()) {
                tags.put("negative", Py.str(meta.negative().get("phase")) + ":" + Py.str(meta.negative().get("type")));
            }
            return variants.stream().map(strict -> new Case(rel + (strict ? " [strict]" : ""),
                    buildSource(test262, f, meta, strict), rel, strict, 250, tags));
        });
        return s;
    }

    private record Item(Path f, String rel, Meta meta) {}

    // ---------------------------------------------------------------------------- files

    /** {@code sorted(root.rglob("*" + suffix))}, regular files only, Python's path order. */
    static List<Path> rglob(Path root, String suffix) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix))
                    .sorted(Py.PATH_ORDER).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String rel(Path root, Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }

    static String read(Path p) {
        return readStrict(p);
    }

    static String readStrict(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code read_text(encoding="utf-8", errors="replace")}. */
    static String readReplace(Path p) {
        try {
            byte[] b = Files.readAllBytes(p);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code str.split()}: runs of whitespace, no empty pieces. */
    static List<String> pySplitWhitespace(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        s.codePoints().forEach(c -> {
            if (Py.isSpace(c)) {
                if (!cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.appendCodePoint(c);
        });
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}
