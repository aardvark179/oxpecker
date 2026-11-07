package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Analysis, verify, the lifter and the builder against the Python implementation, over the
 * programs the Python port's scripts/parity/export_programs.py generated (generated with option mixes, templates,
 * mutated lineages, the golden seeds, and broken variants for the error paths).
 */
class ProgramParityTest {
    static final List<JsonObject> PROGRAMS = new ArrayList<>();
    static {
        try (var in = new GZIPInputStream(ProgramParityTest.class.getResourceAsStream("/parity/programs.json.gz"))) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("programs")) PROGRAMS.add(e.getAsJsonObject());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-1 of the UTF-8 bytes, lone surrogates encoded as Python's "surrogatepass" does. */
    static String sha(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c < 0x80) {
                b.write(c);
            } else if (c < 0x800) {
                b.write(0xC0 | (c >> 6));
                b.write(0x80 | (c & 0x3F));
            } else if (c < 0x10000) {
                b.write(0xE0 | (c >> 12));
                b.write(0x80 | ((c >> 6) & 0x3F));
                b.write(0x80 | (c & 0x3F));
            } else {
                b.write(0xF0 | (c >> 18));
                b.write(0x80 | ((c >> 12) & 0x3F));
                b.write(0x80 | ((c >> 6) & 0x3F));
                b.write(0x80 | (c & 0x3F));
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b.toByteArray()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String err(Throwable e) {
        if (e instanceof InvalidProgram) return "InvalidProgram: " + e.getMessage();
        return "error";
    }

    /** Compares a {"text"} or {"sha"} holder with a computed text. */
    static boolean same(JsonObject expected, String actual) {
        if (expected.has("text")) return expected.get("text").getAsString().equals(actual);
        return expected.get("sha").getAsString().equals(sha(actual));
    }

    static <K, V> String joined(Map<K, V> m, Function<V, String> f) {
        return new TreeMap<>(m).entrySet().stream().map(e -> e.getKey() + "=" + f.apply(e.getValue())).collect(Collectors.joining(";"));
    }

    /** {@link #joined(Map, Function)} over an array indexed by key, skipping null slots. */
    static <V> String joined(V[] xs, Function<V, String> f) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < xs.length; k++) if (xs[k] != null) out.add(k + "=" + f.apply(xs[k]));
        return String.join(";", out);
    }

    /** {@link #joined(Map, Function)} over an array indexed by key, skipping -1 slots. */
    static String joined(int[] xs) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < xs.length; k++) if (xs[k] != -1) out.add(k + "=" + xs[k]);
        return String.join(";", out);
    }

    static String pyList(List<Integer> xs) { return "[" + xs.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "]"; }

    static List<Integer> ints(JsonArray a) {
        List<Integer> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsInt());
        return out;
    }

    static final class Report {
        final Map<String, Integer> failures = new TreeMap<>();
        final List<String> examples = new ArrayList<>();
        int checked = 0;

        void check(boolean ok, String what, JsonObject p, String detail) {
            checked++;
            if (ok) return;
            failures.merge(what, 1, Integer::sum);
            if (examples.size() < 12) examples.add(what + " in " + p.get("recipe").getAsString() + (detail == null ? "" : "\n" + detail));
        }

        void assertClean(String name) {
            assertTrue(failures.isEmpty(), name + ": " + failures + " of " + checked + " checks\n" + String.join("\n---\n", examples));
        }
    }

    @Test void analysisAndVerify() {
        Report r = new Report();
        for (JsonObject p : PROGRAMS) {
            List<Instruction> code = IlJson.code(p.getAsJsonArray("code"));
            GenOptions options = IlJson.options(p.getAsJsonObject("options"));
            String verify;
            try {
                Analysis.verify(code, options.strict());
                verify = "ok";
            } catch (RuntimeException e) {
                verify = err(e);
            }
            r.check(verify.equals(p.get("verify").getAsString()), "verify", p, verify + " vs " + p.get("verify"));
            JsonObject a = p.getAsJsonObject("analysis");
            Analysis an;
            try {
                an = Analysis.analyse(code, options.strict());
            } catch (RuntimeException e) {
                r.check(a.has("error") && a.get("error").getAsString().equals(err(e)), "analyse error", p, err(e) + " vs " + a);
                continue;
            }
            if (a.has("error")) {
                r.check(false, "analyse succeeded", p, a.toString());
                continue;
            }
            String explain = an.explain();
            r.check(same(a.getAsJsonObject("explain"), explain), "explain", p,
                    a.getAsJsonObject("explain").has("text") ? diff(a.getAsJsonObject("explain").get("text").getAsString(), explain) : explain);
            r.check(sha(joined(an.storage(), Storage::name)).equals(a.get("storage").getAsString()), "storage", p, null);
            r.check(sha(joined(an.types(), Type::toString)).equals(a.get("types").getAsString()), "types", p, null);
            r.check(sha(joined(an.returns(), Type::toString)).equals(a.get("returns").getAsString()), "returns", p, null);
            r.check(sha(joined(an.finalTypes(), Type::toString)).equals(a.get("final").getAsString()), "final", p, null);
            List<String> du = new ArrayList<>();
            for (int v = 0; v < an.defs().length; v++)
                du.add(v + ":" + an.defs()[v] + ":" + pyList(an.uses().getOrDefault(v, List.of())) + ":"
                        + pyList(an.assignments().getOrDefault(v, List.of())));
            r.check(sha(String.join(";", du)).equals(a.get("defuse").getAsString()), "defuse", p, null);
            List<String> frames = new ArrayList<>();
            for (int i = 0; i < code.size(); i++)
                frames.add(an.functionOf().get(i) + "," + (an.strictAt().get(i) ? 1 : 0) + "," + an.contextAt().get(i));
            r.check(sha(String.join(";", frames)).equals(a.get("frames").getAsString()), "frames", p, null);
            r.check(sha(joined(an.labels())).equals(a.get("labels").getAsString()), "labels", p, null);
            r.check(sha(an.visibleAt().stream().map(vs -> vs.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .collect(Collectors.joining("\n"))).equals(a.get("visible").getAsString()), "visible", p, null);
            List<String> decl = new ArrayList<>();
            for (int i = 0; i < code.size(); i++)
                decl.add(an.declaredVisibleAt(i).stream().map(String::valueOf).collect(Collectors.joining(",")));
            r.check(sha(String.join("\n", decl)).equals(a.get("declared").getAsString()), "declared", p, null);
            r.check(new ArrayList<>(an.assignable()).equals(ints(a.getAsJsonArray("assignable"))), "assignable", p, null);
            List<String> ta = new ArrayList<>();
            for (int i = 0; i < code.size(); i++) {
                Instruction instr = code.get(i);
                final int ii = i;
                ta.add(i + ":" + java.util.stream.IntStream.of(instr.inouts()).mapToObj(v -> v + "=" + an.typeAt(ii, v)).collect(Collectors.joining(","))
                        + "|" + java.util.stream.IntStream.of(instr.inouts()).mapToObj(v -> v + "=" + an.typeAfter(ii, v)).collect(Collectors.joining(",")));
            }
            r.check(sha(String.join("\n", ta)).equals(a.get("typeAt").getAsString()), "typeAt", p, null);
            JsonArray act = new JsonArray();
            for (var e : new TreeMap<>(an.rhino().activation()).entrySet()) {
                JsonArray pair = new JsonArray();
                pair.add(e.getKey());
                JsonArray names = new JsonArray();
                e.getValue().forEach(x -> names.add(x.name()));
                pair.add(names);
                act.add(pair);
            }
            r.check(act.equals(a.getAsJsonArray("activation")), "activation", p, act + " vs " + a.get("activation"));
            r.check(new ArrayList<>(an.rhino().numberVar()).equals(ints(a.getAsJsonArray("numberVar"))), "numberVar", p,
                    an.rhino().numberVar() + " vs " + a.get("numberVar"));
            r.check(new ArrayList<>(an.rhino().directCallTarget()).equals(ints(a.getAsJsonArray("directTarget"))), "directTarget", p, null);
            r.check(new ArrayList<>(an.rhino().directCallSite()).equals(ints(a.getAsJsonArray("directSite"))), "directSite", p, null);
            r.check(sha(joined(an.rhino().exprType(), RhinoType::name)).equals(a.get("exprType").getAsString()), "exprType", p, null);
        }
        System.out.println("analysis parity: " + PROGRAMS.size() + " programs, " + r.checked + " checks, failures " + r.failures);
        r.assertClean("analysis");
    }

    static LiftOptions variant(String name, GenOptions o) {
        return switch (name) {
            case "source" -> new LiftOptions(true, true, o.strict(), o.probeWidth());
            case "plain" -> new LiftOptions(false, false, o.strict(), 6);
            case "snippet" -> new LiftOptions(false, true, false, 6);
            case "alt" -> new LiftOptions(true, false, !o.strict(), 2);
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test void lifter() {
        Report r = new Report();
        int programs = 0;
        for (JsonObject p : PROGRAMS) {
            if (!p.has("lift")) continue;
            programs++;
            List<Instruction> code = IlJson.code(p.getAsJsonArray("code"));
            GenOptions options = IlJson.options(p.getAsJsonObject("options"));
            for (var e : p.getAsJsonObject("lift").entrySet()) {
                JsonObject expected = e.getValue().getAsJsonObject();
                String text;
                try {
                    text = Lifter.lift(code, variant(e.getKey(), options));
                } catch (RuntimeException ex) {
                    r.check(expected.has("error") && expected.get("error").getAsString().equals(err(ex)), "lift " + e.getKey() + " error",
                            p, ex + " vs " + expected);
                    continue;
                }
                if (expected.has("error")) {
                    r.check(false, "lift " + e.getKey() + " succeeded", p, expected + "\n" + text);
                    continue;
                }
                r.check(same(expected, text), "lift " + e.getKey(), p,
                        expected.has("text") ? diff(expected.get("text").getAsString(), text) : text);
            }
        }
        System.out.println("lift parity: " + programs + " programs, " + r.checked + " lifts, failures " + r.failures);
        r.assertClean("lift");
    }

    @Test void builderReplay() {
        Report r = new Report();
        for (JsonObject p : PROGRAMS) {
            if (!p.has("builder")) continue;
            List<Instruction> code = IlJson.code(p.getAsJsonArray("code"));
            GenOptions options = IlJson.options(p.getAsJsonObject("options"));
            List<String> lines = new ArrayList<>();
            try {
                Builder b = new Builder(options);
                int[] mapping = new int[code.stream().flatMapToInt(x -> java.util.stream.IntStream.of(x.allOutputs())).max().orElse(-1) + 1];
                for (Instruction instr : code) {
                    List<Integer> ins = new ArrayList<>();
                    for (int v : instr.inputs()) ins.add(mapping[v]);
                    Instruction nw = b.emit(instr.op(), ins, instr.lift());
                    int[] olds = instr.allOutputs(), news = nw.allOutputs();
                    for (int k = 0; k < olds.length; k++) mapping[olds[k]] = news[k];
                    lines.add(java.util.stream.IntStream.of(news).mapToObj(v -> v + "=" + b.typeOf(v)).collect(Collectors.joining(","))
                            + "|" + b.context() + "|" + (b.strict() ? 1 : 0) + "|" + b.currentFunction() + "|"
                            + b.visible().stream().map(String::valueOf).collect(Collectors.joining(",")));
                }
                RhinoModel rm = b.rhino();
                lines.add("rhino " + pyList(new ArrayList<>(rm.numberVar())) + " " + pyList(new ArrayList<>(rm.directCallSite())) + " "
                        + pyList(new ArrayList<>(rm.directCallTarget())));
                b.finish();
                int pos = code.size() / 2;
                Builder b2 = Builder.at(code, pos, options);
                lines.add("at " + b2.visible().stream().map(v -> v + "=" + b2.typeOf(v)).collect(Collectors.joining(",")) + "|" + b2.context());
                Builder.Pool pool = b2.pool(Type.NUMBER);
                lines.add("pool " + pool.exact().stream().map(String::valueOf).collect(Collectors.joining(",")) + "/"
                        + pool.maybe().stream().map(String::valueOf).collect(Collectors.joining(",")));
            } catch (BuilderError | InvalidProgram e) {
                lines.add("error " + (e instanceof BuilderError ? "BuilderError" : "InvalidProgram"));
            }
            r.check(sha(String.join("\n", lines)).equals(p.get("builder").getAsString()), "builder", p, String.join("\n", lines));
        }
        System.out.println("builder parity: " + r.checked + " programs, failures " + r.failures);
        r.assertClean("builder");
    }

    /** The hand-written builder sequences: the Java Builder emits exactly the IL the Python one did. */
    @Test void builderScenarios() {
        int n = 0;
        for (JsonObject p : PROGRAMS) {
            if (!p.get("kind").getAsString().equals("scenario")) continue;
            n++;
            String recipe = p.get("recipe").getAsString();
            GenOptions options = IlJson.options(p.getAsJsonObject("options"));
            List<Instruction> expected = IlJson.code(p.getAsJsonArray("code"));
            List<Instruction> actual = Scenarios.build(recipe.replace(" strict", ""), options);
            assertEquals(Program.dump(expected), Program.dump(actual), recipe);
            assertEquals(expected, actual, recipe);
        }
        assertEquals(2 * Scenarios.ALL.size(), n);
    }

    /** The Python port's golden twenty (parity/golden-python): the Java lifter reproduces them from the Python IL. */
    @Test void goldenFiles() throws Exception {
        int n = 0;
        for (JsonObject p : PROGRAMS) {
            if (!p.get("kind").getAsString().equals("golden")) continue;
            String recipe = p.get("recipe").getAsString();
            int seed = Integer.parseInt(recipe.substring("generate(".length(), recipe.indexOf(',')));
            java.nio.file.Path file = java.nio.file.Path.of(System.getProperty("oxpecker.root"), "oxpecker-core/src/test/resources/parity/golden-python",
                    String.format("seed_%02d.js", seed));
            GenOptions o = IlJson.options(p.getAsJsonObject("options"));
            assertEquals(java.nio.file.Files.readString(file), Lifter.lift(IlJson.code(p.getAsJsonArray("code")), variant("source", o)), recipe);
            n++;
        }
        assertEquals(20, n);
    }

    /** The fixture exercises every operation class (the scenarios cover what the generator never emits). */
    @Test void everyOperationIsCovered() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonObject p : PROGRAMS) for (JsonElement i : p.getAsJsonArray("code")) seen.add(i.getAsJsonObject().get("op").getAsString());
        List<String> missing = Ops.ALL.stream().map(Class::getSimpleName).filter(n -> !seen.contains(n)).toList();
        assertEquals(List.of(), missing);
    }

    @Test void ilJsonRoundTrips() {
        for (JsonObject p : PROGRAMS) {
            List<Instruction> code = IlJson.code(p.getAsJsonArray("code"));
            assertEquals(code, IlJson.parseCode(IlJson.writeCode(code)), p.get("recipe").getAsString());
            GenOptions o = IlJson.options(p.getAsJsonObject("options"));
            assertEquals(o, IlJson.options(IlJson.toJson(o)));
        }
    }

    static String diff(String expected, String actual) {
        String[] e = expected.split("\n", -1), a = actual.split("\n", -1);
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String x = i < e.length ? e[i] : "<none>", y = i < a.length ? a[i] : "<none>";
            if (!x.equals(y)) return "line " + (i + 1) + "\n  python: " + x + "\n  java:   " + y;
        }
        return "(same lines)";
    }
}
