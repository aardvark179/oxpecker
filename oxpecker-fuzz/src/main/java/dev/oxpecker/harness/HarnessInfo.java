package dev.oxpecker.harness;

import dev.oxpecker.util.Py;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a harness declares about itself in {@code info} (protocol 1).
 *
 * @param name         the engine it runs, e.g. "Rhino"
 * @param modes        the execution modes it can run (and be pinned to)
 * @param mode         the mode this process is pinned to, if any
 * @param capabilities optional Outcome fields it fills in
 */
public record HarnessInfo(String name, String version, List<String> modes, String mode, List<String> capabilities,
                          boolean coverage, int protocol) {

    public HarnessInfo {
        modes = List.copyOf(modes);
        capabilities = List.copyOf(capabilities);
    }

    public static HarnessInfo fromJson(Map<String, Object> d) {
        List<String> missing = new ArrayList<>();
        for (String k : List.of("name", "version", "modes")) if (!d.containsKey(k)) missing.add(k);
        if (!missing.isEmpty()) {
            throw new HarnessError("harness info does not declare " + String.join(", ", missing) + " (protocol "
                    + Py.str(d.get("protocol")) + "); rebuild the harness");
        }
        Object caps = d.containsKey("capabilities") ? d.get("capabilities") : List.of();
        Object protocol = d.containsKey("protocol") ? d.get("protocol") : 1L;
        return new HarnessInfo(Py.str(d.get("name")), Py.str(d.get("version")), strings(d.get("modes")),
                (String) d.get("mode"), strings(caps), Py.truthy(d.get("coverage")),
                protocol instanceof Number n ? n.intValue() : Integer.parseInt(Py.strip(String.valueOf(protocol))));
    }

    /** Name and version: what identifies the engine in outcomes and corpus records. */
    public String label() {
        return name + " " + version;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : (List<?>) o) out.add(Py.str(x));
        return out;
    }
}
