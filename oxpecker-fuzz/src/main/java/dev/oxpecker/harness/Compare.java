package dev.oxpecker.harness;

import dev.oxpecker.util.Py;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Comparison rules (brief §7): decide whether two modes' outcomes constitute a finding. */
public final class Compare {
    private Compare() {}

    public static final String HARD = "hard";
    /** Same JS behaviour, different Java representation at the host boundary. */
    public static final String JAVA_TYPES = "java-types";
    public static final String SOFT = "soft";
    public static final String EQUAL = "equal";
    /** Fallback: excluded from comparison. */
    public static final String SKIP = "skip";
    /** Both timeout/resource: a generator problem. */
    public static final String DISCARD = "discard";

    private static Object thrownName(Outcome o) {
        return Py.truthy(o.thrown) ? o.thrown.get("name") : null;
    }

    private static Object thrownValue(Outcome o) {
        return Py.truthy(o.thrown) ? o.thrown.get("value") : null;
    }

    /** Whether both harnesses report an optional field (an outcome without capabilities has all). */
    private static boolean has(Outcome a, Outcome b, String capability) {
        for (Outcome o : List.of(a, b)) if (o.capabilities != null && !o.capabilities.contains(capability)) return false;
        return true;
    }

    public static Verdict compare(Outcome a, Outcome b) {
        return compare(a, b, false, false);
    }

    /**
     * Compares the outcome of one mode against another (a is the reference, usually interpreted).
     * Optional fields (Java types, the J.* trace, continuations) are compared only when both
     * harnesses report them.
     */
    public static Verdict compare(Outcome a, Outcome b, boolean strictJavaTypes, boolean strictMessages) {
        boolean javaTypes = has(a, b, "javaTypes"), javaTrace = has(a, b, "javaTrace"),
                continuations = has(a, b, "continuations");
        // Java identity hashes in printed values are masked by the harness (IdentityHashes).
        // Each side by its mode, or by its harness name when the two come from different engines (the
        // same mode in two Rhino versions would otherwise read "interpreted vs interpreted").
        boolean sameEngine = Objects.equals(a.engine, b.engine);
        Map<Outcome, String> label = new IdentityHashMap<>();
        for (Outcome o : List.of(a, b)) label.put(o, sameEngine ? o.mode : Py.or(o.harness, o.mode));
        List<String> reasons = new ArrayList<>();
        List<String> jdiff = new ArrayList<>();
        Integer firstDiff = null;

        if ("fallback".equals(a.status) || "fallback".equals(b.status)) {
            List<String> fell = new ArrayList<>();
            for (Outcome o : List.of(a, b)) if ("fallback".equals(o.status)) fell.add(label.get(o));
            return new Verdict(SKIP, List.of(String.join("/", fell) + " fell back to the interpreter"));
        }
        if ("crash".equals(a.status) || "crash".equals(b.status)) {
            for (Outcome o : List.of(a, b)) {
                if ("crash".equals(o.status)) {
                    Map<String, Object> ee = Py.truthy(o.engineException) ? o.engineException : Map.of();
                    reasons.add(label.get(o) + " crashed: " + Py.str(ee.get("clazz")) + ": "
                            + Py.head(Py.str(ee.get("message")), 120));
                }
            }
            return new Verdict(HARD, reasons);
        }

        Set<String> slow = Set.of("timeout", "resource");
        if (slow.contains(a.status) && slow.contains(b.status)) {
            return new Verdict(DISCARD, List.of("both " + Py.str(a.status) + "/" + Py.str(b.status)));
        }
        if ("unsupported".equals(a.status) && "unsupported".equals(b.status)) {
            // neither mode ran the program to its end (a continuation captured across a non-JavaScript
            // frame, or captured at all in the compiled mode): the partial traces differ by construction
            return new Verdict(SKIP, List.of("both unsupported (" + Py.str(a.reason) + " / " + Py.str(b.reason) + ")"));
        }
        Map<String, Boolean> paused = new HashMap<>();
        for (Outcome o : List.of(a, b)) paused.put(o.mode, Py.truthy(o.continuations));
        if (continuations && !Objects.equals(a.mode, b.mode) && Boolean.TRUE.equals(paused.get("interpreted"))
                && !Boolean.TRUE.equals(paused.get("compiled"))
                && !"unsupported".equals(a.status) && !"unsupported".equals(b.status)) {
            // the interpreter paused; the compiled mode recorded no continuation and did not report the
            // refusal, so the program's own try/catch swallowed it (G42): everything after that point
            // differs by construction, like the both-unsupported case
            return new Verdict(SKIP, List.of("interpreted paused, compiled refusal caught by the program (continuations)"));
        }
        if (slow.contains(a.status) || slow.contains(b.status)) {
            Outcome o = slow.contains(a.status) ? a : b;
            Outcome other = o == a ? b : a;
            return new Verdict(SOFT, List.of(label.get(o) + " " + Py.str(o.status) + " (" + Py.str(o.reason) + ") while "
                    + label.get(other) + " " + Py.str(other.status)));
        }

        if (!Objects.equals(a.status, b.status)) {
            reasons.add("status " + label.get(a) + "=" + st(a) + " vs " + label.get(b) + "=" + st(b));
            if (Py.truthy(a.thrown) || Py.truthy(b.thrown)) {
                reasons.add("thrown " + Py.str(thrownName(a)) + " vs " + Py.str(thrownName(b)));
            }
            return new Verdict(HARD, reasons);
        }

        // Same status: ok / throw / syntax.
        Object na = thrownName(a), va = thrownValue(a), nb = thrownName(b), vb = thrownValue(b);
        if ("throw".equals(a.status) && !(Py.eq(na, nb) && Py.eq(va, vb))) {
            if (!Py.eq(na, nb)) reasons.add("thrown name " + Py.str(na) + " vs " + Py.str(nb));
            if (!Py.eq(va, vb)) reasons.add("thrown value " + shortStr(va) + " vs " + shortStr(vb));
        }
        if ("ok".equals(a.status) && !Objects.equals(a.value, b.value)) {
            reasons.add("value " + shortStr(a.value) + " vs " + shortStr(b.value));
        }
        if (!Objects.equals(a.trace, b.trace)) {
            boolean found = false;
            for (int i = 0; i < Math.min(a.trace.size(), b.trace.size()); i++) {
                String x = a.trace.get(i), y = b.trace.get(i);
                if (!Objects.equals(x, y)) {
                    firstDiff = i;
                    reasons.add("trace[" + i + "] " + argDiff(x, y));
                    found = true;
                    break;
                }
            }
            if (!found) {
                firstDiff = Math.min(a.trace.size(), b.trace.size());
                reasons.add("trace length " + a.trace.size() + " vs " + b.trace.size());
            }
        }
        if (!Objects.equals(a.output, b.output)) {
            reasons.add("output differs: " + shortStr(Py.str(a.output)) + " vs " + shortStr(Py.str(b.output)));
        }
        if (continuations && !Objects.equals(a.continuations, b.continuations)) {
            reasons.add("continuations " + shortStr(Py.str(a.continuations)) + " vs " + shortStr(Py.str(b.continuations)));
        }
        if (javaTrace && !Objects.equals(a.javaTrace, b.javaTrace)) {
            boolean found = false;
            for (int i = 0; i < Math.min(a.javaTrace.size(), b.javaTrace.size()); i++) {
                String x = a.javaTrace.get(i), y = b.javaTrace.get(i);
                if (!Objects.equals(x, y)) {
                    reasons.add("java[" + i + "] " + shortStr(x) + " vs " + shortStr(y));
                    found = true;
                    break;
                }
            }
            if (!found) reasons.add("java trace length " + a.javaTrace.size() + " vs " + b.javaTrace.size());
        }
        if (!reasons.isEmpty()) return new Verdict(HARD, reasons, List.of(), firstDiff);

        // Soft: messages and Java representation types.
        if (javaTypes && !Objects.equals(a.valueJavaTypes, b.valueJavaTypes)) {
            jdiff.add("value " + shortStr(a.valueJavaTypes) + " vs " + shortStr(b.valueJavaTypes));
        }
        if (javaTypes && !Objects.equals(a.traceJavaTypes, b.traceJavaTypes)) {
            for (int i = 0; i < Math.min(a.traceJavaTypes.size(), b.traceJavaTypes.size()); i++) {
                String x = a.traceJavaTypes.get(i), y = b.traceJavaTypes.get(i);
                if (!Objects.equals(x, y)) {
                    // Report only the first probe argument whose Java types differ.
                    List<String> xs = Py.split(x, " | "), ys = Py.split(y, " | ");
                    boolean found = false;
                    for (int j = 0; j < Math.min(xs.size(), ys.size()); j++) {
                        if (!xs.get(j).equals(ys.get(j))) {
                            jdiff.add("trace[" + i + "] arg" + j + " " + shortStr(xs.get(j), 60) + " vs " + shortStr(ys.get(j), 60));
                            found = true;
                            break;
                        }
                    }
                    if (!found) jdiff.add("trace[" + i + "] arity " + xs.size() + " vs " + ys.size());
                    break;
                }
            }
        }
        if (javaTypes && javaTrace && !Objects.equals(a.javaTraceTypes, b.javaTraceTypes)) {
            for (int i = 0; i < Math.min(a.javaTraceTypes.size(), b.javaTraceTypes.size()); i++) {
                String x = a.javaTraceTypes.get(i), y = b.javaTraceTypes.get(i);
                if (!Objects.equals(x, y)) {
                    jdiff.add("java[" + i + "] " + shortStr(x, 60) + " vs " + shortStr(y, 60));
                    break;
                }
            }
        }
        Map<String, Object> ta = Py.truthy(a.thrown) ? a.thrown : Map.of(), tb = Py.truthy(b.thrown) ? b.thrown : Map.of();
        if (javaTypes && !Py.eq(ta.get("valueJavaTypes"), tb.get("valueJavaTypes"))) {
            jdiff.add("thrown " + Py.str(ta.get("valueJavaTypes")) + " vs " + Py.str(tb.get("valueJavaTypes")));
        }
        List<String> softReasons = new ArrayList<>();
        if (!Objects.equals(a.soft, b.soft) || !Py.eq(ta.get("message"), tb.get("message"))) {
            softReasons.add("error messages differ");
        }
        if (!jdiff.isEmpty()) {
            // Always reported, never folded into "equal": embedders see these at the Java boundary.
            List<String> rs = new ArrayList<>(List.of("java types differ"));
            rs.addAll(softReasons);
            return new Verdict(strictJavaTypes ? HARD : JAVA_TYPES, rs, jdiff);
        }
        if (!softReasons.isEmpty()) return new Verdict(strictMessages ? HARD : SOFT, softReasons, jdiff);
        return new Verdict(EQUAL);
    }

    private static String st(Outcome o) {
        return Py.str(o.status) + ("unsupported".equals(o.status) ? "(" + Py.str(o.reason) + ")" : "");
    }

    static final List<String> MODE_ORDER = List.of("interpreted", "compiled", "debugger");

    /** The pair in a fixed order (by mode, then engine, then harness name), whichever is the guide,
     * so that a divergence reads, and signs, the same with an interpreted or a compiled guide. */
    static Outcome[] canonicalPair(Outcome x, Outcome y) {
        return keyCompare(x, y) <= 0 ? new Outcome[] {x, y} : new Outcome[] {y, x};
    }

    private static int keyCompare(Outcome x, Outcome y) {
        int ix = MODE_ORDER.contains(x.mode) ? MODE_ORDER.indexOf(x.mode) : MODE_ORDER.size();
        int iy = MODE_ORDER.contains(y.mode) ? MODE_ORDER.indexOf(y.mode) : MODE_ORDER.size();
        if (ix != iy) return Integer.compare(ix, iy);
        int c = Py.compare(x.mode, y.mode);
        if (c != 0) return c;
        c = Py.compare(Objects.requireNonNullElse(x.engine, ""), Objects.requireNonNullElse(y.engine, ""));
        if (c != 0) return c;
        return Py.compare(Objects.requireNonNullElse(x.harness, ""), Objects.requireNonNullElse(y.harness, ""));
    }

    /** "mode" when both outcomes come from the same engine, else which two engines differ. */
    public static String pairClass(Outcome a, Outcome b) {
        if (Objects.equals(a.engine, b.engine)) return "mode";
        return "engine:" + String.join("|", Py.sorted(List.of(Py.str(a.engine), Py.str(b.engine))));
    }

    public static Verdict compareRun(RunResult run) {
        return compareRun(run, null, false, false);
    }

    public static Verdict compareRun(RunResult run, boolean strictJavaTypes) {
        return compareRun(run, null, strictJavaTypes, false);
    }

    private static final Map<String, Integer> ORDER = Map.of(HARD, 0, JAVA_TYPES, 1, SOFT, 2, DISCARD, 3, SKIP, 4, EQUAL, 5);

    /** Compares every other outcome against the reference (default: the run's guide, else
     * interpreted); the worst verdict wins and the per-comparator verdicts are in {@code pairs}. */
    public static Verdict compareRun(RunResult run, String reference, boolean strictJavaTypes, boolean strictMessages) {
        reference = Py.or(reference, Py.or(run.guide(), "interpreted"));
        Outcome ref = run.results().get(reference);
        if (ref == null) return new Verdict(SKIP, List.of("no " + reference + " result"));
        Verdict worst = null;
        Map<String, Verdict> pairs = new LinkedHashMap<>();
        for (Map.Entry<String, Outcome> e : run.results().entrySet()) {
            String name = e.getKey();
            if (name.equals(reference)) continue;
            Outcome o = e.getValue();
            Outcome[] p = canonicalPair(ref, o);
            Verdict v = compare(p[0], p[1], strictJavaTypes, strictMessages);
            v.against = name;
            v.pairClass = pairClass(ref, o);
            pairs.put(name, v.copy());
            if (worst == null || ORDER.get(v.kind) < ORDER.get(worst.kind)) {
                worst = v;
            } else if (v.kind.equals(worst.kind)) {
                Set<String> seen = new HashSet<>(worst.reasons);
                for (String r : v.reasons) if (!seen.contains(r)) worst.reasons.add(r);
                Set<String> seenJ = new HashSet<>(worst.javaTypeDiff);
                for (String j : v.javaTypeDiff) if (!seenJ.contains(j)) worst.javaTypeDiff.add(j);
            }
        }
        if (worst == null) return new Verdict(SKIP, List.of("no comparator ran beside " + reference));
        worst.pairs = pairs;
        return worst;
    }

    private static Harness shellClient;

    /** Run JavaScript source through the harness (one long-lived client per process) and compare
     * the modes; prints the verdict and outcomes unless {@code quiet}. For the shell and the tests. */
    public static synchronized Map.Entry<Verdict, RunResult> runAndCompare(String source, RunOptions options, boolean quiet) {
        if (shellClient == null) shellClient = new HarnessClient(true).start();
        if (options.modes() == null) options = options.withModes(HarnessClient.DEFAULT_MODES);
        RunResult run = shellClient.run(source, options);
        Verdict verdict = compareRun(run);
        if (!quiet) {
            System.out.println(verdict.pretty());
            for (Map.Entry<String, Outcome> e : run.results().entrySet()) {
                Outcome o = e.getValue();
                System.out.println("  " + String.format("%-12s", e.getKey()) + " " + String.format("%-10s", o.status)
                        + " value=" + Py.repr(o.value) + (Py.truthy(o.thrown) ? " thrown=" + Py.repr(o.thrown) : ""));
                for (String t : o.trace) System.out.println("      trace: " + t);
            }
        }
        return Map.entry(verdict, run);
    }

    /** The first probe argument that differs, so reports and signatures point at the value rather
     * than the whole snapshot. */
    static String argDiff(String x, String y) {
        List<String> xs = Py.split(x, " | "), ys = Py.split(y, " | ");
        for (int j = 0; j < Math.min(xs.size(), ys.size()); j++) {
            String p = xs.get(j), q = ys.get(j);
            if (!p.equals(q)) {
                int[] pc = Py.codePoints(p), qc = Py.codePoints(q);
                int k = Math.min(pc.length, qc.length);
                for (int c = 0; c < Math.min(pc.length, qc.length); c++) {
                    if (pc[c] != qc[c]) { k = c; break; }
                }
                int lo = Math.max(0, k - 30);
                String pre = lo > 0 ? "…" : "";
                return "arg" + j + " " + pre + shortStr(Py.tail(p, lo), 70) + " vs " + pre + shortStr(Py.tail(q, lo), 70);
            }
        }
        return "arity " + xs.size() + " vs " + ys.size();
    }

    static String shortStr(Object s) {
        return shortStr(s, 80);
    }

    static String shortStr(Object o, int n) {
        if (o == null) return "None";
        String s = Py.str(o);
        return Py.len(s) <= n ? s : Py.head(s, n - 1) + "…";
    }
}
