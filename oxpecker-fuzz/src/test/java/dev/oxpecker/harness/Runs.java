package dev.oxpecker.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test helpers: RunResults built by hand. */
final class Runs {
    private Runs() {}

    /** {@code _run(guide, **outcomes)}: outcomes by harness name (set on each), in order. */
    static RunResult run(String guide, Object... nameOutcome) {
        Map<String, Outcome> rs = new LinkedHashMap<>();
        for (int i = 0; i < nameOutcome.length; i += 2) {
            String name = (String) nameOutcome[i];
            Outcome o = (Outcome) nameOutcome[i + 1];
            o.harness = name;
            rs.put(name, o);
        }
        return new RunResult("x", rs, false, guide);
    }

    /** interpreted and compiled, no guide. */
    static RunResult pair(Outcome interpreted, Outcome compiled) {
        Map<String, Outcome> rs = new LinkedHashMap<>();
        rs.put("interpreted", interpreted);
        rs.put("compiled", compiled);
        return new RunResult("t", rs);
    }
}
