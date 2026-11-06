package dev.oxpecker.harness;

import com.google.gson.JsonObject;
import dev.oxpecker.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one mode (one harness process) reported for one case. Field names are the protocol's JSON
 * keys. Mutable like the Python dataclass: the Ensemble fills in {@code harness}, {@code role},
 * {@code engine} and {@code capabilities} after the harness answered.
 */
public final class Outcome {
    public String mode;
    public String status;
    public Boolean compiledActually;
    public String value;
    public String valueJavaTypes;
    public Map<String, Object> thrown;
    public List<String> trace = new ArrayList<>();
    public List<String> traceJavaTypes = new ArrayList<>();
    public List<String> javaTrace = new ArrayList<>();
    public List<String> javaTraceTypes = new ArrayList<>();
    public List<String> continuations = new ArrayList<>();
    public List<String> soft = new ArrayList<>();
    public List<String> output = new ArrayList<>();
    public Map<String, Object> engineException;
    public String reason;
    public long ms;
    public Integer newProbes;
    /** With coverage_new_ids: which probes were new. */
    public Map<String, List<Integer>> newProbeIds;
    /** With coverage_require: the subset reached. */
    public Map<String, List<Integer>> requiredReached;
    /** With coverage_hits: every probe hit (base64 bitmaps). */
    public Map<String, String> hitProbes;
    // Filled in by the Ensemble, not by the harness: which process produced this outcome.
    /** The harness's name, the key in RunResult.results. */
    public String harness;
    /** guide | comparator | oracle */
    public String role;
    /** e.g. "rhino 2.0.0-SNAPSHOT (Rhino Snapshot)" */
    public String engine;
    /** Optional fields the harness fills in (null: all of them). */
    public List<String> capabilities;

    /** The JSON keys, in the dataclass's field order. */
    public static final List<String> FIELDS = List.of("mode", "status", "compiledActually", "value", "valueJavaTypes",
            "thrown", "trace", "traceJavaTypes", "javaTrace", "javaTraceTypes", "continuations", "soft", "output",
            "engineException", "reason", "ms", "newProbes", "newProbeIds", "requiredReached", "hitProbes", "harness",
            "role", "engine", "capabilities");

    public Outcome(String mode, String status) {
        this.mode = mode;
        this.status = status;
    }

    public static Outcome of(String mode, String status) {
        return new Outcome(mode, status);
    }

    /** Reads the known fields and ignores the rest (a newer harness may send more). */
    public static Outcome fromJson(JsonObject d) {
        return fromJava(castMap(Json.toJava(d)));
    }

    public static Outcome fromJava(Map<String, Object> d) {
        for (String k : List.of("mode", "status")) {
            if (!d.containsKey(k)) throw new IllegalArgumentException("Outcome is missing required field '" + k + "'");
        }
        Outcome o = new Outcome((String) d.get("mode"), (String) d.get("status"));
        if (d.containsKey("compiledActually")) o.compiledActually = (Boolean) d.get("compiledActually");
        if (d.containsKey("value")) o.value = (String) d.get("value");
        if (d.containsKey("valueJavaTypes")) o.valueJavaTypes = (String) d.get("valueJavaTypes");
        if (d.containsKey("thrown")) o.thrown = castMap(d.get("thrown"));
        if (d.containsKey("trace")) o.trace = strings(d.get("trace"));
        if (d.containsKey("traceJavaTypes")) o.traceJavaTypes = strings(d.get("traceJavaTypes"));
        if (d.containsKey("javaTrace")) o.javaTrace = strings(d.get("javaTrace"));
        if (d.containsKey("javaTraceTypes")) o.javaTraceTypes = strings(d.get("javaTraceTypes"));
        if (d.containsKey("continuations")) o.continuations = strings(d.get("continuations"));
        if (d.containsKey("soft")) o.soft = strings(d.get("soft"));
        if (d.containsKey("output")) o.output = strings(d.get("output"));
        if (d.containsKey("engineException")) o.engineException = castMap(d.get("engineException"));
        if (d.containsKey("reason")) o.reason = (String) d.get("reason");
        if (d.get("ms") instanceof Number n) o.ms = n.longValue();
        if (d.get("newProbes") instanceof Number n) o.newProbes = n.intValue();
        if (d.containsKey("newProbeIds")) o.newProbeIds = probeSet(d.get("newProbeIds"));
        if (d.containsKey("requiredReached")) o.requiredReached = probeSet(d.get("requiredReached"));
        if (d.get("hitProbes") instanceof Map<?, ?> m) {
            o.hitProbes = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) o.hitProbes.put((String) e.getKey(), (String) e.getValue());
        }
        if (d.containsKey("harness")) o.harness = (String) d.get("harness");
        if (d.containsKey("role")) o.role = (String) d.get("role");
        if (d.containsKey("engine")) o.engine = (String) d.get("engine");
        if (d.containsKey("capabilities")) o.capabilities = d.get("capabilities") == null ? null : strings(d.get("capabilities"));
        return o;
    }

    /** Every field, None included, in the dataclass's order. */
    public JsonObject toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("status", status);
        m.put("compiledActually", compiledActually);
        m.put("value", value);
        m.put("valueJavaTypes", valueJavaTypes);
        m.put("thrown", thrown);
        m.put("trace", trace);
        m.put("traceJavaTypes", traceJavaTypes);
        m.put("javaTrace", javaTrace);
        m.put("javaTraceTypes", javaTraceTypes);
        m.put("continuations", continuations);
        m.put("soft", soft);
        m.put("output", output);
        m.put("engineException", engineException);
        m.put("reason", reason);
        m.put("ms", ms);
        m.put("newProbes", newProbes);
        m.put("newProbeIds", newProbeIds);
        m.put("requiredReached", requiredReached);
        m.put("hitProbes", hitProbes);
        m.put("harness", harness);
        m.put("role", role);
        m.put("engine", engine);
        m.put("capabilities", capabilities);
        return Json.toTree(m).getAsJsonObject();
    }

    /** A shallow copy with fresh lists (dataclasses.replace). */
    public Outcome copy() {
        Outcome o = new Outcome(mode, status);
        o.compiledActually = compiledActually;
        o.value = value;
        o.valueJavaTypes = valueJavaTypes;
        o.thrown = thrown;
        o.trace = copy(trace);
        o.traceJavaTypes = copy(traceJavaTypes);
        o.javaTrace = copy(javaTrace);
        o.javaTraceTypes = copy(javaTraceTypes);
        o.continuations = copy(continuations);
        o.soft = copy(soft);
        o.output = copy(output);
        o.engineException = engineException;
        o.reason = reason;
        o.ms = ms;
        o.newProbes = newProbes;
        o.newProbeIds = newProbeIds;
        o.requiredReached = requiredReached;
        o.hitProbes = hitProbes;
        o.harness = harness;
        o.role = role;
        o.engine = engine;
        o.capabilities = capabilities;
        return o;
    }

    // Fluent setters for building outcomes by hand (tests, fixtures).
    public Outcome value(String v) { value = v; return this; }
    public Outcome reason(String r) { reason = r; return this; }
    public Outcome trace(String... t) { trace = new ArrayList<>(List.of(t)); return this; }
    public Outcome output(String... t) { output = new ArrayList<>(List.of(t)); return this; }
    public Outcome continuations(String... t) { continuations = new ArrayList<>(List.of(t)); return this; }
    public Outcome javaTrace(String... t) { javaTrace = new ArrayList<>(List.of(t)); return this; }
    public Outcome valueJavaTypes(String v) { valueJavaTypes = v; return this; }
    public Outcome thrown(Map<String, Object> t) { thrown = t; return this; }
    public Outcome engineException(Map<String, Object> e) { engineException = e; return this; }
    public Outcome engine(String e) { engine = e; return this; }
    public Outcome capabilities(List<String> c) { capabilities = c; return this; }

    @Override
    public String toString() {
        return "Outcome" + toJson();
    }

    private static List<String> copy(List<String> l) {
        return l == null ? null : new ArrayList<>(l);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        for (Object x : (List<?>) o) out.add(x == null ? null : x instanceof String s ? s : String.valueOf(x));
        return out;
    }

    static Map<String, List<Integer>> probeSet(Object o) {
        if (o == null) return null;
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            List<Integer> ids = new ArrayList<>();
            for (Object x : (List<?>) e.getValue()) ids.add(((Number) x).intValue());
            out.put((String) e.getKey(), ids);
        }
        return out;
    }
}
