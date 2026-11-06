package dev.oxpecker.harness;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.util.Json;
import dev.oxpecker.util.Py;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Environment model: what builtins this Rhino build exposes (constructors, prototype methods,
 * namespaces, syntax), discovered by running probe.js through the harness once and cached in
 * rhino-capabilities.json next to it (environment/ at the repository root). The API generators draw from it, so
 * a Rhino upgrade only needs {@code oxpecker probe} to refresh the model.
 */
public final class CapabilityProbe {
    private CapabilityProbe() {}

    public static Path dir() {
        return Oxpecker.root().resolve("environment");
    }

    public static Path probeJs() {
        return dir().resolve("probe.js");
    }

    public static Path cache() {
        return dir().resolve("rhino-capabilities.json");
    }

    /** Builtins the generators should not poke at: host/harness objects, and things whose calls are
     * either meaningless or nondeterministic here. */
    public static final Set<String> EXCLUDED_GLOBALS = Set.of("J", "print", "__probe", "__isProxy", "__jtype", "__pause",
            "globalThis", "eval", "Function", "Continuation", "WeakRef", "FinalizationRegistry", "Packages", "java",
            "javax", "org", "com", "net", "edu", "JavaAdapter", "JavaImporter", "XML", "XMLList", "Namespace", "QName",
            "gc", "uneval", "Iterator", "StopIteration", "InternalError", "ConsString", "CallSite", "Script",
            "JavaException", "AggregateError");

    /** Syntax capabilities. Each snippet is its own two-mode harness case: a crash or a mode
     * difference in <em>parsing</em> is a finding in itself, and cannot take the whole probe down. */
    public static final Map<String, String> SYNTAX = syntax(
            "let", "let a = 1;", "const", "const a = 1;", "arrow", "var f = () => 1;", "class", "class A {}",
            "generator", "function* g() { yield 1; }", "for-of", "for (var x of []) {}", "spread-call", "Math.max(...[1])",
            "spread-array", "var a = [...[1]];", "object-spread", "var o = {...{}};", "destructuring", "var [a, {b}] = [1, {}];",
            "default-params", "function f(a = 1) {}", "rest-params", "function f(...a) {}", "template", "var s = `a${1}`;",
            "computed-key", "var o = {[1]: 1};", "shorthand-method", "var o = {m() {}};", "getter", "var o = {get x() { return 1; }};",
            "super-in-object", "var o = {m() { return super.x; }};", "new-target", "function f() { return new.target; }",
            "async", "async function f() {}", "await", "async function f() { await 1; }", "optional-chaining", "var a = {}; a?.b;",
            "nullish", "var a = null ?? 1;", "exponent", "var a = 2 ** 3;", "bigint", "var a = 1n;", "numeric-separator", "var a = 1_000;",
            "logical-assign", "var a = 1; a ||= 2;", "regexp-named-groups", "/(?<x>a)/.exec('a').groups.x",
            "regexp-lookbehind", "/(?<=a)b/.test('ab')", "regexp-dotall", "/./s.test('\\n')", "regexp-unicode", "/\\u{1F600}/u.test('x')",
            "label-block", "L: { break L; }", "with", "with ({}) {}", "trailing-comma-call", "Math.max(1,)",
            "getter-numeric", "var o = {get 0() { return 1; }};", "unicode-escape-id", "var \\u0061 = 1;",
            "hashbang", "#!x\n1", "private-field", "class A { #x; }", "static-block", "class A { static {} }",
            "import-meta", "import.meta", "top-level-await", "await 1", "import-call", "import('x')",
            "tagged-template", "String.raw`a${1}`", "octal-literal", "var a = 0o17;", "binary-literal", "var a = 0b101;",
            "legacy-octal", "var a = 017;", "comma-in-for", "for (var i = 0, j = 1; i < 1; i++, j++) {}",
            "label-continue", "L: for (var i = 0; i < 2; i++) { continue L; }", "debugger", "debugger;", "getter-computed", "var o = {get [1]() { return 1; }};",
            "generator-method", "var o = {*g() {}};", "arrow-destructure", "var f = ({a}) => a;",
            // Rhino 1.9.2 parses rest parameters on functions but not on arrows, and no rest element in patterns
            "arrow-rest", "var f = (a, ...r) => r;", "array-rest", "var [a, ...r] = [1, 2];", "object-rest", "var {a, ...r} = {a: 1, b: 2};");

    private static Map<String, String> syntax(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** {name: {&lt;mode&gt;: status..., supported: bool, verdict, detail}} per snippet. */
    public static Map<String, Map<String, Object>> probeSyntax(Harness client, int languageVersion) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : SYNTAX.entrySet()) {
            String name = e.getKey();
            RunResult run = client.run(e.getValue(), RunOptions.DEFAULT.withLanguageVersion(languageVersion)
                    .withId("syntax-" + name).withSourceName("syntax-" + name + ".js").withContinuations(false));
            Map<String, Object> st = new LinkedHashMap<>();
            run.results().forEach((m, o) -> st.put(m, o.status));
            Verdict v = Compare.compareRun(run);
            Map<String, Object> row = new LinkedHashMap<>(st);
            row.put("supported", st.values().stream().allMatch("ok"::equals));
            row.put("verdict", v.kind);
            row.put("detail", v.kind.equals("equal") ? "" : v.pretty());
            out.put(name, row);
        }
        return out;
    }

    public static Map<String, Object> runProbe(Harness client) {
        return runProbe(client, 250);
    }

    /** Runs probe.js in interpreted mode and returns the decoded capability table. */
    public static Map<String, Object> runProbe(Harness client, int languageVersion) {
        String src = read(probeJs());
        RunResult run = client.run(src, RunOptions.DEFAULT.withModes("interpreted").withLanguageVersion(languageVersion)
                .withId("capability-probe").withSourceName("probe.js").withWallMs(30000).withContinuations(false));
        Outcome o = run.results().get("interpreted");
        if (!"ok".equals(o.status) || o.output.isEmpty()) {
            Object why = Py.truthy(o.thrown) ? o.thrown : o.engineException;
            throw new IllegalStateException("probe failed: " + Py.str(o.status) + " " + Py.str(why));
        }
        Map<String, Object> table = Json.parseObject(o.output.get(0));
        table.put("syntax", probeSyntax(client, languageVersion));
        table.put("languageVersion", (long) languageVersion);
        table.put("rhino", client.info().get("rhino"));
        return table;
    }

    public static void save(Map<String, Object> table) {
        save(table, cache());
    }

    /** {@code json.dumps(table, indent=1, sort_keys=True)}, byte for byte. */
    public static void save(Map<String, Object> table, Path path) {
        try {
            Files.writeString(path, Json.pyDumps(table, 1, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Object> load() {
        return load(cache());
    }

    public static Map<String, Object> load(Path path) {
        if (!Files.exists(path)) return null;
        return Json.parseObject(read(path));
    }

    /** A function or method and its {@code length}. */
    public record Member(String name, int arity) {}

    /** {constructor name: [(method, arity)]} for prototype methods, excluding accessors/symbols. */
    public static Map<String, List<Member>> methodTable(Map<String, Object> table) {
        Map<String, List<Member>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : section(table, "protos").entrySet()) {
            if (EXCLUDED_GLOBALS.contains(e.getKey())) continue;
            List<Member> ms = new ArrayList<>();
            for (Object x : (List<?>) e.getValue()) {
                String m = (String) x;
                if (m.contains("/") && !m.startsWith("Symbol(")) ms.add(member(m));
            }
            if (!ms.isEmpty()) out.put(e.getKey(), ms);
        }
        return out;
    }

    /** {namespace or constructor: [(function, arity)]} for static functions. */
    public static Map<String, List<Member>> staticTable(Map<String, Object> table) {
        Map<String, List<Member>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : section(table, "statics").entrySet()) {
            if (EXCLUDED_GLOBALS.contains(e.getKey())) continue;
            List<Member> fs = new ArrayList<>();
            for (Object x : (List<?>) e.getValue()) {
                String m = (String) x;
                if (m.contains("/")) fs.add(member(m));
            }
            if (!fs.isEmpty()) out.put(e.getKey(), fs);
        }
        return out;
    }

    private static Member member(String m) {
        String[] p = Py.rpartition(m, "/");
        return new Member(p[0], Integer.parseInt(Py.strip(p[2])));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> table, String key) {
        Object s = table.get(key);
        return s == null ? Map.of() : (Map<String, Object>) s;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
