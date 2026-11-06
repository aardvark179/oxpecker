package dev.oxpecker.il;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Python-style reprs, so dumps, signatures and messages read the way the Python port's did:
 * records as {@code Name(field=value, ...)} with snake_case field names, enums as
 * {@code <Enum.NAME: value>}, strings single-quoted.
 */
public final class Repr {
    private Repr() {}

    /** Enums with a Python value expose it through this interface. */
    public interface Valued { Object value(); }

    public static String of(Object o) {
        if (o == null) return "None";
        if (o instanceof String s) return str(s);
        if (o instanceof Boolean b) return b ? "True" : "False";
        if (o instanceof Double d) return floatRepr(d);
        if (o instanceof Enum<?> e) {
            Object v = o instanceof Valued val ? val.value() : e.name().toLowerCase();
            return "<" + pythonName(e.getDeclaringClass()) + "." + e.name() + ": " + of(v) + ">";
        }
        if (o instanceof List<?> l) {
            String inner = l.stream().map(Repr::of).collect(Collectors.joining(", "));
            return "(" + inner + (l.size() == 1 ? ",)" : ")");
        }
        if (o instanceof Collection<?> c) {
            // Python orders a frozenset by hash (run to run, with string hashing); sorted here
            if (c.isEmpty()) return "frozenset()";
            return "frozenset({" + c.stream().map(Repr::of).sorted().collect(Collectors.joining(", ")) + "})";
        }
        if (o instanceof Type t) return type(t);
        if (o instanceof Builtin || o instanceof Method || o instanceof Key || o instanceof Parameters || o instanceof Flags)
            return o.toString();
        if (o instanceof Record r) return record(r);
        return o.toString();
    }

    /** The Python class name of a ported enum (CompareOperator is ops.py's Comparator). */
    public static String pythonName(Class<?> c) {
        return c == CompareOperator.class ? "Comparator" : c.getSimpleName();
    }

    private static final String[] KIND_NAMES = {"UNDEFINED", "NULL", "BOOLEAN", "INTEGER", "FLOAT", "BIGINT", "STRING",
            "SYMBOL", "OBJECT", "FUNCTION"};

    /** The dataclass repr of a Type, with {@code possible} as the Kind flag Python prints. */
    public static String type(Type t) {
        StringBuilder kinds = new StringBuilder();
        for (int i = 0; i < KIND_NAMES.length; i++) {
            if ((t.possible() & (1 << i)) != 0) {
                if (!kinds.isEmpty()) kinds.append('|');
                kinds.append(KIND_NAMES[i]);
            }
        }
        String possible = t.possible() == 0 ? "<Kind: 0>" : "<Kind." + kinds + ": " + t.possible() + ">";
        return "Type(possible=" + possible + ", group=" + of(t.group()) + ", signature=" + of(t.signature())
                + ", element=" + of(t.element()) + ", props=" + of(t.props()) + ")";
    }

    public static String record(Record r) {
        StringBuilder sb = new StringBuilder(r.getClass().getSimpleName()).append('(');
        RecordComponent[] cs = r.getClass().getRecordComponents();
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) sb.append(", ");
            try {
                sb.append(snake(cs[i].getName())).append('=').append(of(cs[i].getAccessor().invoke(r)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.append(')').toString();
    }

    public static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('_').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Python's repr of a float: shortest round-trip digits, always with a '.' or exponent. */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        return PyFloat.repr(d);
    }

    /** Python's repr of a str. */
    public static String str(String s) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == q || c == '\\') sb.append('\\').appendCodePoint(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", c));
            else if (isPrintable(c)) sb.appendCodePoint(c);
            else if (c <= 0xff) sb.append(String.format("\\x%02x", c));
            else if (c <= 0xffff) sb.append(String.format("\\u%04x", c));
            else sb.append(String.format("\\U%08x", c));
        }
        return sb.append(q).toString();
    }

    private static boolean isPrintable(int c) {
        int t = Character.getType(c);
        return !(t == Character.CONTROL || t == Character.FORMAT || t == Character.SURROGATE
                || t == Character.PRIVATE_USE || t == Character.UNASSIGNED || t == Character.LINE_SEPARATOR
                || t == Character.PARAGRAPH_SEPARATOR || (t == Character.SPACE_SEPARATOR && c != ' '));
    }
}
