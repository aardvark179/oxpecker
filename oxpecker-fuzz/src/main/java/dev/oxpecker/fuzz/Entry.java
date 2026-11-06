package dev.oxpecker.fuzz;

import dev.oxpecker.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A corpus entry: a lineage stored as its encoded choices. {@code probes}: the new probes a
 * re-run reaches again (D36); {@code cap}: the lineage's step cap (G49), null in corpora from
 * before it; {@code ms}: run time when found (slowest mode), 0 in corpora from before it.
 */
public final class Entry {
    public int id;
    public String choices;
    public String template;
    public List<String> steps;
    public int newProbes;
    public Map<String, List<Integer>> probes;
    public Integer parent;
    public int instructions;
    public List<String> found;
    public double addedAt;
    public Integer cap;
    public long ms;

    public Entry(int id, String choices, String template, List<String> steps, int newProbes, Map<String, List<Integer>> probes,
                 Integer parent, int instructions, Integer cap, long ms) {
        this.id = id;
        this.choices = choices;
        this.template = template;
        this.steps = new ArrayList<>(steps);
        this.newProbes = newProbes;
        this.probes = probes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(probes);
        this.parent = parent;
        this.instructions = instructions;
        this.found = new ArrayList<>();
        this.addedAt = System.currentTimeMillis() / 1000.0;
        this.cap = cap;
        this.ms = ms;
    }

    /** The JSON fields in Python's order: what one line of corpus.jsonl holds. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("choices", choices);
        m.put("template", template);
        m.put("steps", steps);
        m.put("new_probes", newProbes);
        m.put("probes", probes);
        m.put("parent", parent);
        m.put("instructions", instructions);
        m.put("found", found);
        m.put("added_at", addedAt);
        m.put("cap", cap);
        m.put("ms", ms);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Entry fromJson(Map<String, Object> d) {
        Map<String, List<Integer>> probes = new LinkedHashMap<>();
        Object p = d.get("probes");
        if (p instanceof Map<?, ?> pm)
            for (var e : pm.entrySet()) {
                List<Integer> ids = new ArrayList<>();
                for (Object x : (List<Object>) e.getValue()) ids.add(((Number) x).intValue());
                probes.put((String) e.getKey(), ids);
            }
        Entry e = new Entry(num(d.get("id")), (String) d.get("choices"), str(d.get("template")),
                d.get("steps") instanceof List<?> l ? (List<String>) l : List.of(), num(d.get("new_probes")), probes,
                d.get("parent") == null ? null : num(d.get("parent")), num(d.get("instructions")),
                d.get("cap") == null ? null : num(d.get("cap")), d.get("ms") == null ? 0 : ((Number) d.get("ms")).longValue());
        if (d.get("found") instanceof List<?> f) e.found = new ArrayList<>((List<String>) f);
        if (d.get("added_at") instanceof Number n) e.addedAt = n.doubleValue();
        return e;
    }

    private static int num(Object o) { return o == null ? 0 : ((Number) o).intValue(); }

    private static String str(Object o) { return o == null ? "" : (String) o; }

    public String line() { return Json.pyDumps(toJson(), null, false); }
}
