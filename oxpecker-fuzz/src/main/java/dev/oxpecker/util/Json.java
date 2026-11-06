package dev.oxpecker.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON the way the Python side reads and writes it: objects become insertion-ordered
 * {@code Map<String, Object>}, arrays {@code List<Object>}, integers {@code Long} (floats
 * {@code Double}), so values print and compare as they did in Python ({@link Py#str}, {@link Py#eq}).
 */
public final class Json {
    private Json() {}

    /** Writes nulls (Python's {@code None} fields are written) and does not HTML-escape. */
    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    public static JsonElement parse(String text) {
        return JsonParser.parseString(text);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = toJava(parse(text));
        if (!(o instanceof Map)) throw new IllegalArgumentException("not a JSON object: " + Py.head(text, 80));
        return (Map<String, Object>) o;
    }

    /** JSON tree to plain Java values (Map, List, String, Long/BigInteger, Double, Boolean, null). */
    public static Object toJava(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) m.put(x.getKey(), toJava(x.getValue()));
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) l.add(toJava(x));
            return l;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) return p.getAsString();
        String t = p.getAsString();
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0 && !t.equals("NaN") && !t.contains("Infinity")) {
            BigInteger b = new BigInteger(t);
            return b.bitLength() < 64 ? (Object) b.longValue() : b;
        }
        return p.getAsDouble();
    }

    /** Plain Java values (and JsonElements) to a JSON tree. */
    public static JsonElement toTree(Object o) {
        if (o == null) return JsonNull.INSTANCE;
        if (o instanceof JsonElement e) return e;
        if (o instanceof String s) return new JsonPrimitive(s);
        if (o instanceof Boolean b) return new JsonPrimitive(b);
        if (o instanceof Number n) return new JsonPrimitive(n);
        if (o instanceof Map<?, ?> m) {
            JsonObject j = new JsonObject();
            for (Map.Entry<?, ?> x : m.entrySet()) j.add(String.valueOf(x.getKey()), toTree(x.getValue()));
            return j;
        }
        if (o instanceof Collection<?> c) {
            JsonArray a = new JsonArray();
            for (Object x : c) a.add(toTree(x));
            return a;
        }
        if (o instanceof Object[] arr) return toTree(List.of(arr));
        return new JsonPrimitive(o.toString());
    }

    /** One line of the harness protocol: compact JSON, non-ASCII as is, lone surrogates escaped
     * (a UTF-8 pipe cannot carry them raw). */
    public static String line(Object o) {
        String s = GSON.toJson(toTree(o));
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean lone = Character.isHighSurrogate(c) ? !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                    : Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
            if (lone) {
                if (sb == null) sb = new StringBuilder(s.substring(0, i));
                sb.append(String.format("\\u%04x", (int) c));
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    // ---------------------------------------------------------------------------- json.dumps

    /** Python's {@code json.dumps(o, indent=indent, sort_keys=sortKeys)} (ensure_ascii on);
     * {@code indent} null is the compact default ({@code ", "} and {@code ": "}). */
    public static String pyDumps(Object o, Integer indent, boolean sortKeys) {
        StringBuilder sb = new StringBuilder();
        dump(sb, toJavaIfTree(o), indent, sortKeys, 0);
        return sb.toString();
    }

    private static Object toJavaIfTree(Object o) {
        return o instanceof JsonElement e ? toJava(e) : o;
    }

    private static void dump(StringBuilder sb, Object o, Integer indent, boolean sortKeys, int level) {
        o = toJavaIfTree(o);
        if (o == null) sb.append("null");
        else if (o instanceof Boolean b) sb.append(b ? "true" : "false");
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            sb.append(Double.isNaN(d) ? "NaN" : Double.isInfinite(d) ? (d > 0 ? "Infinity" : "-Infinity") : Py.floatRepr(d));
        } else if (o instanceof Number n) sb.append(n);
        else if (o instanceof String s) quote(sb, s);
        else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            List<Map.Entry<?, ?>> entries = new ArrayList<>(m.entrySet());
            if (sortKeys) entries.sort((x, y) -> Py.compare(String.valueOf(x.getKey()), String.valueOf(y.getKey())));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : entries) {
                if (!first) sb.append(indent == null ? ", " : ",");
                first = false;
                newline(sb, indent, level + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                dump(sb, e.getValue(), indent, sortKeys, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(indent == null ? ", " : ",");
                first = false;
                newline(sb, indent, level + 1);
                dump(sb, x, indent, sortKeys, level + 1);
            }
            newline(sb, indent, level);
            sb.append(']');
        } else quote(sb, o.toString());
    }

    private static void newline(StringBuilder sb, Integer indent, int level) {
        if (indent != null) sb.append('\n').append(" ".repeat(indent * level));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
