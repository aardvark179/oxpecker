package dev.oxpecker.shrink;

import dev.oxpecker.util.Py;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Source-level reproducer shrinking: delta debugging over top-level statements, then over single
 * lines, while an oracle (usually "same coarse verdict signature") still holds.
 *
 * <p>Complements the choice-sequence shrinker of the fuzz loop: deleting an early <em>choice</em>
 * re-contextualises every later draw and usually loses the bug, whereas deleting a
 * <em>statement</em> leaves the rest of the program intact. Programs are built so that top-level
 * statements start at brace depth 0, which is all the structure this needs.
 */
public final class SourceShrink {
    private SourceShrink() {}

    public static final int DEFAULT_BUDGET = 300;

    /** Groups lines into top-level units: a unit ends where the brace depth returns to 0 at the end
     * of a line. Comment lines attach to the following unit. */
    public static List<List<String>> splitStatements(String source) {
        List<List<String>> units = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int depth = 0;
        for (String line : Py.splitlines(source)) {
            cur.add(line);
            depth += depthDelta(line);
            if (depth <= 0 && !Py.strip(line).isEmpty() && !Py.lstrip(line).startsWith("//")) {
                depth = 0;
                units.add(cur);
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) units.add(cur);
        return units;
    }

    // Ignore braces inside strings/regexps/comments well enough for generated code.
    private static final Pattern NOT_CODE = Py.re("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`|//.*$");

    static int depthDelta(String line) {
        String code = NOT_CODE.matcher(line).replaceAll("");
        int d = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        return d;
    }

    public static String shrinkSource(String source, Predicate<String> oracle) {
        return shrinkSource(source, oracle, DEFAULT_BUDGET);
    }

    /** Returns a smaller source for which {@code oracle} still holds (the original if nothing can go). */
    public static String shrinkSource(String source, Predicate<String> oracle, int budget) {
        int[] tries = {0};
        Predicate<String> holds = s -> {
            tries[0]++;
            return oracle.test(s);
        };

        // ---- 1. top-level units, ddmin-style chunk removal
        List<List<String>> units = splitStatements(source);
        int n = Math.max(1, units.size() / 2);
        while (n >= 1 && tries[0] < budget) {
            int i = 0;
            boolean progressed = false;
            while (i < units.size() && tries[0] < budget) {
                List<List<String>> cand = new ArrayList<>(units.subList(0, i));
                cand.addAll(units.subList(Math.min(i + n, units.size()), units.size()));
                if (!cand.isEmpty() && holds.test(join(cand))) {
                    units = cand;
                    progressed = true;
                } else {
                    i += n;
                }
            }
            if (!progressed) n /= 2;
        }

        // ---- 2. single lines inside the remaining units (keeping braces balanced)
        List<String> lines = Py.splitlines(join(units));
        int i = 0;
        while (i < lines.size() && tries[0] < budget) {
            String ln = lines.get(i);
            if (depthDelta(ln) == 0 && !Py.strip(ln).isEmpty() && !Py.strip(ln).startsWith("}")) {
                List<String> cand = new ArrayList<>(lines.subList(0, i));
                cand.addAll(lines.subList(i + 1, lines.size()));
                if (holds.test(String.join("\n", cand) + "\n")) {
                    lines = cand;
                    continue;
                }
            }
            i++;
        }
        // ---- 3. drop comment lines (they carry no behaviour; keep probe labels readable)
        List<String> kept = new ArrayList<>();
        for (String ln : lines) if (!Py.strip(ln).startsWith("//")) kept.add(ln);
        return String.join("\n", kept) + "\n";
    }

    private static String join(List<List<String>> units) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (List<String> u : units) {
            for (String ln : u) {
                if (!first) sb.append('\n');
                first = false;
                sb.append(ln);
            }
        }
        return sb.append('\n').toString();
    }
}
