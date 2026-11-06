package dev.oxpecker.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The bits of Python semantics the port has to reproduce byte for byte: {@code str()} and
 * {@code repr()} of the values that end up in verdict reasons, code-point (not UTF-16) string
 * lengths and slices, {@code str.splitlines}, {@code str.strip}, {@code str.split(sep)}, string
 * ordering, {@code ==} on JSON-ish values and truthiness.
 */
public final class Py {
    private Py() {}

    /** Flags for patterns ported from Python's {@code re} on str: Unicode {@code \d \w \s}, and
     * {@code .}, {@code ^}, {@code $} that know only {@code \n} as a line end. */
    public static final int RE = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    public static Pattern re(String regex) {
        return Pattern.compile(regex, RE);
    }

    public static Pattern re(String regex, int flags) {
        return Pattern.compile(regex, RE | flags);
    }

    // ---------------------------------------------------------------------------- str / repr

    /** Python's {@code str(x)} for None, bools, ints, floats, strings, lists and dicts. */
    public static String str(Object o) {
        if (o instanceof String s) return s;
        return repr(o);
    }

    /** Python's {@code repr(x)}. */
    public static String repr(Object o) {
        if (o == null) return "None";
        if (o instanceof String s) return reprStr(s);
        if (o instanceof Boolean b) return b ? "True" : "False";
        if (o instanceof Double d) return floatRepr(d);
        if (o instanceof Float f) return floatRepr(f.doubleValue());
        if (o instanceof Number n) return n.toString();
        if (o instanceof Path p) return "PosixPath(" + reprStr(p.toString()) + ")";
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(x));
            }
            return sb.append(']').toString();
        }
        return o.toString();
    }

    /** {@code repr(s)} of a str (CPython's unicode_repr). */
    public static String reprStr(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder(s.length() + 2).append(quote);
        s.codePoints().forEach(c -> {
            if (c == quote || c == '\\') sb.append('\\').appendCodePoint(c);
            else if (c == '\t') sb.append("\\t");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < ' ' || c == 0x7f) sb.append(String.format("\\x%02x", c));
            else if (c < 0x7f) sb.append((char) c);
            else if (isPrintable(c)) sb.appendCodePoint(c);
            else if (c <= 0xff) sb.append(String.format("\\x%02x", c));
            else if (c <= 0xffff) sb.append(String.format("\\u%04x", c));
            else sb.append(String.format("\\U%08x", c));
        });
        return sb.append(quote).toString();
    }

    /** {@code str.isprintable} for one code point. */
    public static boolean isPrintable(int c) {
        if (c == ' ') return true;
        return switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** {@code repr(float)}: the shortest round-tripping digits, fixed notation for decimal
     * exponents in [-4, 16), else {@code 1e+16} style. */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        String sign = d < 0 ? "-" : "";
        // Double.toString gives the shortest repr (JDK 19+), as CPython's repr does.
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        if (bd.precision() == 2) {
            // Double.toString always gives two digits (4.9E-324) where one may round-trip (5e-324).
            BigDecimal exact = new BigDecimal(Math.abs(d)), best = null;
            for (RoundingMode rm : new RoundingMode[] {RoundingMode.FLOOR, RoundingMode.CEILING}) {
                BigDecimal c = exact.round(new java.math.MathContext(1, rm));
                if (Double.parseDouble(c.toString()) == Math.abs(d)
                        && (best == null || c.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0)) best = c;
            }
            if (best != null) bd = best.stripTrailingZeros();
        }
        String digits = bd.unscaledValue().toString();
        int decpt = digits.length() - bd.scale();     // value = 0.<digits> * 10^decpt
        if (decpt > -4 && decpt <= 16) {
            if (decpt <= 0) return sign + "0." + "0".repeat(-decpt) + digits;
            if (decpt >= digits.length()) return sign + digits + "0".repeat(decpt - digits.length()) + ".0";
            return sign + digits.substring(0, decpt) + "." + digits.substring(decpt);
        }
        int exp = decpt - 1;
        String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mant + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
    }

    /** {@code f"{x:.Nf}"}: rounds the exact binary value half-even, as CPython does. */
    public static String fixed(double d, int places) {
        return new BigDecimal(d).setScale(places, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** {@code round(x, n)}. */
    public static double round(double d, int places) {
        return new BigDecimal(d).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }

    // ---------------------------------------------------------------------------- code points

    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** {@code s[start:end]} in code points, for non-negative bounds (clamped like Python). */
    public static String slice(String s, int start, int end) {
        int n = len(s);
        start = Math.min(Math.max(start, 0), n);
        end = Math.min(Math.max(end, start), n);
        int a = s.offsetByCodePoints(0, start);
        int b = s.offsetByCodePoints(a, end - start);
        return s.substring(a, b);
    }

    /** {@code s[:n]}. */
    public static String head(String s, int n) {
        return slice(s, 0, n);
    }

    /** {@code s[start:]}. */
    public static String tail(String s, int start) {
        return slice(s, start, Integer.MAX_VALUE);
    }

    public static int[] codePoints(String s) {
        return s.codePoints().toArray();
    }

    /** Python's str ordering: by code point. */
    public static int compare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i), y = b.codePointAt(j);
            if (x != y) return Integer.compare(x, y);
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    public static final Comparator<String> ORDER = Py::compare;

    /** {@code min(strings)}. */
    public static String min(Collection<String> c) {
        return c.stream().min(ORDER).orElseThrow();
    }

    public static List<String> sorted(Collection<String> c) {
        List<String> out = new ArrayList<>(c);
        out.sort(ORDER);
        return out;
    }

    // ---------------------------------------------------------------------------- str methods

    /** {@code str.isspace} for one code point. */
    public static boolean isSpace(int c) {
        if (c == ' ' || (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x1f) || c == 0x85) return true;
        int t = Character.getType(c);
        return t == Character.SPACE_SEPARATOR || t == Character.LINE_SEPARATOR || t == Character.PARAGRAPH_SEPARATOR;
    }

    public static String strip(String s) {
        return rstrip(lstrip(s));
    }

    public static String lstrip(String s) {
        int i = 0;
        while (i < s.length()) {
            int c = s.codePointAt(i);
            if (!isSpace(c)) break;
            i += Character.charCount(c);
        }
        return s.substring(i);
    }

    public static String rstrip(String s) {
        int i = s.length();
        while (i > 0) {
            int c = s.codePointBefore(i);
            if (!isSpace(c)) break;
            i -= Character.charCount(c);
        }
        return s.substring(0, i);
    }

    /** {@code s.strip(chars)}. */
    public static String strip(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    /** {@code str.splitlines()}: every line boundary Python knows, without keeping them. */
    public static List<String> splitlines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0, i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int eol;
            if (c == '\r') eol = (i + 1 < n && s.charAt(i + 1) == '\n') ? 2 : 1;
            else if (c == '\n' || c == 0x0b || c == 0x0c || c == 0x1c || c == 0x1d || c == 0x1e || c == 0x85
                     || c == 0x2028 || c == 0x2029) eol = 1;
            else { i++; continue; }
            out.add(s.substring(start, i));
            i += eol;
            start = i;
        }
        if (start < n) out.add(s.substring(start));
        return out;
    }

    /** {@code s.split(sep)} with a literal, non-empty separator (keeps empty pieces). */
    public static List<String> split(String s, String sep) {
        List<String> out = new ArrayList<>();
        int i = 0, j;
        while ((j = s.indexOf(sep, i)) >= 0) {
            out.add(s.substring(i, j));
            i = j + sep.length();
        }
        out.add(s.substring(i));
        return out;
    }

    /** {@code s.rpartition(sep)}. */
    public static String[] rpartition(String s, String sep) {
        int i = s.lastIndexOf(sep);
        if (i < 0) return new String[] {"", "", s};
        return new String[] {s.substring(0, i), sep, s.substring(i + sep.length())};
    }

    /** {@code s.partition(sep)}. */
    public static String[] partition(String s, String sep) {
        int i = s.indexOf(sep);
        if (i < 0) return new String[] {s, "", ""};
        return new String[] {s.substring(0, i), sep, s.substring(i + sep.length())};
    }

    /** {@code PurePath.suffix} of a file name. */
    public static String suffix(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0 && i < name.length() - 1) ? name.substring(i) : "";
    }

    /** Ordering of {@code sorted(paths)}: part by part, each by code point. */
    public static final Comparator<Path> PATH_ORDER = (a, b) -> {
        int n = Math.min(a.getNameCount(), b.getNameCount());
        if (a.isAbsolute() != b.isAbsolute()) return a.isAbsolute() ? -1 : 1;
        for (int i = 0; i < n; i++) {
            int c = compare(a.getName(i).toString(), b.getName(i).toString());
            if (c != 0) return c;
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    };

    // ---------------------------------------------------------------------------- values

    /** Python's {@code ==} on JSON-ish values: numbers compare by value whatever their Java type. */
    public static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number x && b instanceof Number y && !(a instanceof Boolean) && !(b instanceof Boolean)) {
            if (isIntegral(x) && isIntegral(y)) return x.longValue() == y.longValue();
            return x.doubleValue() == y.doubleValue();
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!eq(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !eq(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }

    /** Python truthiness. */
    public static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return !s.isEmpty();
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof Collection<?> c) return !c.isEmpty();
        if (o instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    /** {@code a or b} for strings. */
    public static String or(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }
}
