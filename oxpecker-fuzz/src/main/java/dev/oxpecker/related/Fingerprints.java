package dev.oxpecker.related;

import dev.oxpecker.harness.Outcome;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.util.Py;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which checked-in regressions a new finding is probably connected to.
 *
 * <p>Signatures only deduplicate exact repeats: the same Rhino bug reached with other values,
 * another probe or another symptom (throw in one mode instead of a value difference) gets a new
 * signature and is recorded as a new finding. Rather than merge such findings away (a looser match
 * would also hide new bugs that share a symptom class), each new finding is labelled with the
 * regressions it shares evidence with, so triage starts from the likely duplicate:
 * <ul>
 *   <li>the Rhino frames an engine crash was thrown from (strongest: same code path),</li>
 *   <li>an error message, digits masked ({@code Cannot convert BigInt to a number}),</li>
 *   <li>the source constructs both programs use, weighted by how rare they are among the
 *       regressions ({@code super}, {@code __proto__}, {@code ||=}, {@code ...} count;
 *       {@code var}, {@code function} do not; {@code <<=} and {@code +=} also count as a
 *       compound assignment),</li>
 *   <li>the coarse signature (kind of difference only; weak on its own).</li>
 * </ul>
 * This class has the evidence extractors; {@link KnownIndex} ranks.
 */
public final class Fingerprints {
    private Fingerprints() {}

    /** Shared rarity mass at which the source evidence reaches 1 - 1/e. */
    public static final double SOURCE_SCALE = 8.0;

    /** Names the generators give variables, parameters and loop indices (v12, n3, arr4, big0, f18,
     * p19, i48, e13, s2, b6, o5): they say nothing about the construct, only about the generator. */
    private static final Pattern GENERATED_NAME = Py.re("(?:v|n|o|arr|f|p|i|e|s|big|b)\\d+");
    private static final Pattern TOKEN = Py.re("\\.\\.\\.|>>>=|\\*\\*=|&&=|\\|\\|=|\\?\\?=|<<=|>>=|>>>|\\?\\?|\\?\\.|\\*\\*|=>|[A-Za-z_$][\\w$]*|[-+*/%&|^]=");
    private static final Set<String> NOISE = Set.of("__probe", "var", "let", "const", "function", "return", "true", "false",
            "undefined", "null");
    private static final Pattern STRING = Py.re("\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`");
    private static final Pattern COMMENT = Py.re("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern RHINO_FRAME = Py.re("^(org\\.mozilla\\.javascript\\.(?!gen\\.)[\\w.$]+)\\(");
    private static final Pattern TYPED_ARRAY = Py.re("(?:Big)?(?:Int|Uint|Float)\\d+(?:Clamped)?Array");
    private static final Pattern DIGITS = Py.re("\\d+");

    /** Class tokens beside the spelled one, so {@code super.x <<= 0} and {@code super.x += 1} share
     * the construct (a compound assignment) and Float64Array and Int8Array share TypedArray. */
    static List<String> classes(String token) {
        if (token.equals("&&=") || token.equals("||=") || token.equals("??=")) return List.of("<logical-assign>");
        if (Py.len(token) >= 2 && token.endsWith("=") && !token.equals("=>")) return List.of("<compound-assign>");
        if (TYPED_ARRAY.matcher(token).matches()) return List.of("<TypedArray>");
        return List.of();
    }

    /** The identifiers and operators of a program, without comments, string contents, numbers,
     * generated names, single-letter names (hand-written regressions use them like the generator
     * uses v12) and the most common keywords; plus class tokens ({@link #classes}). */
    public static Set<String> sourceTokens(String source) {
        String src = STRING.matcher(COMMENT.matcher(source).replaceAll(" ")).replaceAll("\"\"");
        Set<String> out = new HashSet<>();
        Matcher m = TOKEN.matcher(src);
        while (m.find()) {
            String t = m.group();
            if (NOISE.contains(t) || GENERATED_NAME.matcher(t).matches()
                    || (Py.len(t) == 1 && Character.isLowerCase(t.codePointAt(0)))) continue;
            out.add(t);
            out.addAll(classes(t));
        }
        return Set.copyOf(out);
    }

    private static String mask(String text) {
        return Py.strip(DIGITS.matcher(text).replaceAll("#"));
    }

    /** Thrown error name+message and engine exception class+message per mode, digits masked. */
    public static Set<String> runMessages(RunResult run) {
        Set<String> out = new HashSet<>();
        for (Outcome o : run.results().values()) {
            if (Py.truthy(o.thrown) && Py.truthy(o.thrown.get("message"))) {
                out.add(Py.head(mask(Py.str(o.thrown.get("name")) + ": " + Py.str(o.thrown.get("message"))), 160));
            }
            if (Py.truthy(o.engineException)) {
                out.add(Py.head(mask(Py.str(o.engineException.get("clazz")) + ": "
                        + Py.str(o.engineException.get("message"))), 160));
            }
        }
        return Set.copyOf(out);
    }

    public static Set<String> runFrames(RunResult run) {
        return runFrames(run, 2);
    }

    /** The top Rhino frames (method names, no line numbers) each mode's engine exception came from. */
    public static Set<String> runFrames(RunResult run, int depth) {
        Set<String> out = new HashSet<>();
        for (Outcome o : run.results().values()) {
            Object st = o.engineException == null ? null : o.engineException.get("stack");
            String stack = Py.truthy(st) ? Py.str(st) : "";
            List<String> frames = new ArrayList<>();
            for (String ln : Py.splitlines(stack)) {
                Matcher m = RHINO_FRAME.matcher(ln);
                if (m.lookingAt()) frames.add(m.group(1));
            }
            out.addAll(frames.subList(0, Math.min(depth, frames.size())));
        }
        return Set.copyOf(out);
    }
}
