package dev.oxpecker.fuzz;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.harness.Harness;
import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.harness.Verdict;
import dev.oxpecker.related.KnownIndex;
import dev.oxpecker.related.Related;
import dev.oxpecker.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recording findings (findings.py): one jsonl log per verdict kind, deduplicated by signature,
 * plus a {@code .js} reproducer per hard signature.
 */
public final class Findings {
    private Findings() {}

    /** What a finding records: a source and the template it came from (a Lineage, or a shrunk Reproducer). */
    public interface Recordable {
        String source();

        String template();

        static Recordable of(String source, String template) {
            return new Recordable() {
                @Override public String source() { return source; }
                @Override public String template() { return template; }
            };
        }

        static Recordable of(dev.oxpecker.il.Mutate.Lineage l) { return of(l.source(), l.template()); }
    }

    public static Path defaultDir() { return Oxpecker.root().resolve("tests").resolve("findings"); }

    public static String shortSig(Verdict v) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(v.signature().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 10);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signatures of the checked-in regressions (run through the harness) plus tests/known_signatures.txt. */
    public static Set<String> knownSignatures(Harness client) { return KnownIndex.knownIndex(client).signatures; }

    /**
     * Appends to {@code <kind>.jsonl} once per signature. With {@code reproducer}, (over)writes
     * {@code <kind>-<sig>.js} with the probed source (trace findings need the probes) and returns its
     * path. {@code related} names the regressions the finding probably connects to.
     */
    public static Path record(Recordable prog, RunResult run, Verdict verdict, Path outDir, boolean reproducer,
                              List<Integer> choices, String replayCmd, List<Related> related) {
        try {
            Files.createDirectories(outDir);
            Path log = outDir.resolve(verdict.kind + ".jsonl");
            String sig = verdict.signature();
            Set<String> seen = new HashSet<>();
            if (Files.exists(log))
                for (String ln : Files.readAllLines(log, StandardCharsets.UTF_8))
                    if (!ln.isBlank()) seen.add((String) Json.parseObject(ln).get("signature"));
            List<Related> rel = related == null ? List.of() : related;
            Object results = run != null ? Json.toJava(run.toJson().get("results")) : Map.of();
            if (!seen.contains(sig)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("signature", sig);
                m.put("verdict", Json.toJava(verdict.toJson()));
                m.put("source", prog.source());
                m.put("template", prog.template());
                m.put("choices", choices);
                m.put("guide", run != null ? run.guide() : null);
                List<Object> rs = new ArrayList<>();
                for (Related r : rel) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("regression", r.name());
                    x.put("score", r.score());
                    x.put("shared", r.shared());
                    rs.add(x);
                }
                m.put("related", rs);
                m.put("results", results);
                m.put("against", verdict.against);
                Files.writeString(log, Json.pyDumps(m, null, false) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (!reproducer) return null;
            Path path = outDir.resolve(verdict.kind + "-" + shortSig(verdict) + ".js");
            StringBuilder body = new StringBuilder("// oxpecker-expect: ").append(verdict.kind).append("\n// ")
                    .append(verdict.pretty().replace("\n", " ")).append("\n");
            Map<String, Object> statuses = new LinkedHashMap<>();
            if (run != null)
                for (Map.Entry<String, Outcome> e : run.results().entrySet()) {
                    Map<String, Object> sv = new LinkedHashMap<>();
                    sv.put("status", e.getValue().status);
                    sv.put("value", e.getValue().value);
                    statuses.put(e.getKey(), sv);
                }
            body.append("// ").append(Json.pyDumps(statuses, null, false)).append("\n");
            for (Related r : rel) body.append("// Related: ").append(r).append("\n");
            body.append("// Replay: ").append(replayCmd).append(" ").append(path).append("\n").append(prog.source());
            Files.writeString(path, body.toString(), StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path record(Recordable prog, RunResult run, Verdict verdict, Path outDir) {
        return record(prog, run, verdict, outDir, false, null, "oxpecker diff", null);
    }
}
