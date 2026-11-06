package dev.oxpecker.harness;

import com.google.gson.JsonObject;
import dev.oxpecker.util.Json;
import dev.oxpecker.util.Py;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Whether two modes' outcomes constitute a finding, and why. Mutable like the Python dataclass
 * ({@link Compare#compareRun} fills in {@code against}, {@code pairClass} and {@code pairs}). */
public final class Verdict {
    /** equal | hard | java-types | soft | skip | discard */
    public String kind;
    public List<String> reasons;
    /** Integer-vs-Double style differences. */
    public List<String> javaTypeDiff;
    public Integer firstTraceDiff;
    /** The harness compared with the guide (compareRun). */
    public String against;
    /** "mode": same engine; "engine:&lt;a&gt;|&lt;b&gt;": two engines. */
    public String pairClass = "mode";
    /** compareRun: the verdict per comparator. */
    public Map<String, Verdict> pairs = new LinkedHashMap<>();

    public Verdict(String kind, List<String> reasons, List<String> javaTypeDiff, Integer firstTraceDiff) {
        this.kind = kind;
        this.reasons = new ArrayList<>(reasons);
        this.javaTypeDiff = new ArrayList<>(javaTypeDiff);
        this.firstTraceDiff = firstTraceDiff;
    }

    public Verdict(String kind, List<String> reasons, List<String> javaTypeDiff) {
        this(kind, reasons, javaTypeDiff, null);
    }

    public Verdict(String kind, List<String> reasons) {
        this(kind, reasons, List.of(), null);
    }

    public Verdict(String kind) {
        this(kind, List.of(), List.of(), null);
    }

    public boolean isFinding() {
        return kind.equals(Compare.HARD) || kind.equals(Compare.JAVA_TYPES) || kind.equals(Compare.SOFT);
    }

    /** dataclasses.replace(v, reasons=list(v.reasons), java_type_diff=list(v.java_type_diff)):
     * fresh lists, the same pairs map. */
    public Verdict copy() {
        Verdict v = new Verdict(kind, reasons, javaTypeDiff, firstTraceDiff);
        v.against = against;
        v.pairClass = pairClass;
        v.pairs = pairs;
        return v;
    }

    private static final Pattern TRACE_IDX = Py.re("trace\\[\\d+\\]");
    private static final Pattern ARG_IDX = Py.re("arg\\d+");
    private static final Pattern JAVA_IDX = Py.re("java\\[\\d+\\]");
    private static final Pattern AT_IDX = Py.re("@\\d+");
    private static final Pattern NUM_KEY = Py.re("\"-?\\d+\"");
    private static final Pattern VALUE_OR_ARG = Py.re("^(value|trace\\[\\] arg) ");
    private static final Pattern NUMBER = Py.re("(?<![A-Za-z_#])(-?)(\\d+(\\.\\d+)?(e[+-]?\\d+)?)");
    private static final Pattern COARSE_HEAD = Py.re("(status \\S+ vs \\S+|thrown name|thrown value|value|trace\\[\\d+\\]( arg\\d+)?|trace length|output differs"
            + "|java\\[\\d+\\]|java trace length|continuations|([\\w.:]+) crashed: [\\w.$]+|java types differ|error messages differ)");
    private static final Pattern BRACKET_IDX = Py.re("\\[\\d+\\]");

    /** Dedup key: the kind and the first reason / first Java-type difference, with probe indices
     * and variable names stripped so the same divergence groups across programs. */
    public String signature() {
        String first;
        if (kind.equals(Compare.JAVA_TYPES)) first = firstOf(javaTypeDiff, reasons);
        else first = firstOf(reasons, javaTypeDiff);
        first = TRACE_IDX.matcher(first).replaceAll("trace[]");
        first = ARG_IDX.matcher(first).replaceAll("arg");
        first = JAVA_IDX.matcher(first).replaceAll("java[]");
        first = AT_IDX.matcher(first).replaceAll("@");
        first = NUM_KEY.matcher(first).replaceAll("\"#\"");          // numeric property keys
        // The same divergence surfaces as `value` (completion) or `trace[] arg` (a probe): one class.
        first = VALUE_OR_ARG.matcher(first).replaceFirst("diff ");
        // Numbers become '#', except zero: -0 vs 0 is its own class of bug.
        first = NUMBER.matcher(first).replaceAll(m -> Matcher.quoteReplacement(
                isZero(m.group(2)) ? m.group(1) + "0" : "#"));
        return kindAndPair() + ":" + first;
    }

    /** float(text) == 0 for a match of {@code \d+(\.\d+)?(e[+-]?\d+)?} (digits may be any Unicode Nd). */
    private static boolean isZero(String number) {
        StringBuilder sb = new StringBuilder();
        number.codePoints().forEach(c -> {
            if (Character.isDigit(c)) sb.append((char) ('0' + Character.digit(c, 10)));
            else sb.appendCodePoint(c);
        });
        return Double.parseDouble(sb.toString()) == 0;
    }

    private static String firstOf(List<String> a, List<String> b) {
        if (!a.isEmpty()) return a.get(0);
        if (!b.isEmpty()) return b.get(0);
        return "";
    }

    /** The kind, qualified by the pair class unless the pair differs only in mode (the case every
     * signature so far was recorded under, so those stay as they were). */
    private String kindAndPair() {
        return pairClass.equals("mode") ? kind : kind + "@" + pairClass;
    }

    public JsonObject toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("reasons", reasons);
        m.put("javaTypeDiff", javaTypeDiff);
        m.put("firstTraceDiff", firstTraceDiff);
        m.put("signature", signature());
        m.put("against", against);
        m.put("pairClass", pairClass);
        Map<String, Object> ps = new LinkedHashMap<>();
        pairs.forEach((n, v) -> ps.put(n, v.kind));
        m.put("pairs", ps);
        return Json.toTree(m).getAsJsonObject();
    }

    /** kind + the <em>type</em> of the first difference, without any values: what a shrinker should
     * hold constant (values and lengths change while shrinking; the bug class should not). */
    public String coarseSignature() {
        String first = firstOf(reasons, javaTypeDiff);
        Matcher m = COARSE_HEAD.matcher(first);
        String head = m.lookingAt() ? m.group(0) : Py.head(first, 20);
        head = BRACKET_IDX.matcher(head).replaceAll("[]");
        head = ARG_IDX.matcher(head).replaceAll("arg");
        return kindAndPair() + ":" + head;
    }

    public String pretty() {
        String s = kind.toUpperCase(Locale.ROOT);
        if (!reasons.isEmpty()) s += ": " + String.join("; ", reasons);
        if (!javaTypeDiff.isEmpty()) s += " [java types: " + String.join("; ", javaTypeDiff) + "]";
        return s;
    }

    @Override
    public String toString() {
        return pretty();
    }
}
