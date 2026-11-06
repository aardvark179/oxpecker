package dev.oxpecker.cli;

import dev.oxpecker.util.Py;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** The f-string format specs the CLI and the inspector print with (widths count code points). */
public final class Fmt {
    private Fmt() {}

    /** {@code f"{s:w}"} / {@code f"{s:<w}"}. */
    public static String ljust(Object o, int w) {
        String s = Py.str(o);
        int n = Py.len(s);
        return n >= w ? s : s + " ".repeat(w - n);
    }

    /** {@code f"{n:w}"} for a number, {@code f"{s:>w}"}. */
    public static String rjust(Object o, int w) {
        String s = Py.str(o);
        int n = Py.len(s);
        return n >= w ? s : " ".repeat(w - n) + s;
    }

    /** {@code f"{x:.Nf}"}. */
    public static String f(double x, int places) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        String s = Py.fixed(x, places);
        if (x < 0 || (x == 0 && 1 / x < 0)) {
            if (!s.startsWith("-")) s = "-" + s;
        }
        return s;
    }

    /** {@code f"{x:.N%}"}. */
    public static String pct(double x, int places) {
        return f(x * 100, places) + "%";
    }

    /** A JSON number as a double. */
    public static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    /** {@code Counter.most_common()}: by count, descending, ties in insertion order. */
    public static <K> List<Map.Entry<K, Integer>> mostCommon(Map<K, Integer> counts, Integer n) {
        List<Map.Entry<K, Integer>> l = new ArrayList<>(counts.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return n == null || n >= l.size() ? l : l.subList(0, n);
    }

    /** {@code sorted(xs)} of strings, code point order. */
    public static List<String> sorted(Collection<String> xs) {
        return Py.sorted(xs);
    }
}
