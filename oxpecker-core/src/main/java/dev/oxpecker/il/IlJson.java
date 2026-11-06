package dev.oxpecker.il;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.oxpecker.il.Ops.Operation;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The IL as JSON, in the format the Python side wrote it (the Python port's scripts/parity/export_programs.py, removed with it; see git history) and
 * the corpus can store: an instruction is
 * {@code {"op": "LoadInteger", "args": {"value": 1, "radix": {"$enum": "Radix", "name": "DEC"}},
 * "io": [0], "index": 0, "gen": "", "origin": 0, "step": 0, "lift": null}}; operation fields are
 * keyed by their Python (snake_case) names. Values: numbers and booleans as JSON; floats as
 * {@code {"$f": <float.hex>}} (exact, including -0.0, inf and nan); enums {@code {"$enum", "name"}};
 * flags {@code {"$flag", "bits"}}; keys {@code {"$key", "kind"}}; builtins and methods by registry
 * attribute name ({@code {"$builtin": "PARSE_INT"}}, {@code {"$method": "ARRAY_PUSH"}}); types
 * structurally ({@code {"$type": {...}}}); parameters {@code {"$params": {...}}}; destructuring
 * patterns {@code {"$dc": "ObjectPattern", "f": {...}}}; tuples as arrays.
 */
public final class IlJson {
    private IlJson() {}

    private static final Map<String, Class<? extends Operation>> OPS = new HashMap<>();
    private static final Map<String, Class<?>> PATTERN_CLASSES = new HashMap<>();
    private static final Map<Builtin, String> BUILTIN_ATTR = new HashMap<>();
    private static final Map<Method, String> METHOD_ATTR = new HashMap<>();
    static {
        for (Class<? extends Operation> c : Ops.ALL) OPS.put(c.getSimpleName(), c);
        for (Class<?> c : Patterns.class.getDeclaredClasses()) if (c.isRecord()) PATTERN_CLASSES.put(c.getSimpleName(), c);
        for (var e : Env.BUILTINS.entrySet()) BUILTIN_ATTR.putIfAbsent(e.getValue(), e.getKey());
        for (var e : Env.METHODS.entrySet()) METHOD_ATTR.putIfAbsent(e.getValue(), e.getKey());
    }

    // ------------------------------------------------------------------------- reading
    public static List<Instruction> parseCode(String json) { return code(JsonParser.parseString(json).getAsJsonArray()); }

    public static List<Instruction> code(JsonArray a) {
        List<Instruction> out = new ArrayList<>(a.size());
        for (JsonElement e : a) out.add(instruction(e.getAsJsonObject()));
        return out;
    }

    public static Instruction instruction(JsonObject o) {
        Operation op = op(o.get("op").getAsString(), o.getAsJsonObject("args"));
        JsonArray io = o.getAsJsonArray("io");
        int[] inouts = new int[io.size()];
        for (int k = 0; k < inouts.length; k++) inouts[k] = io.get(k).getAsInt();
        JsonElement lift = o.get("lift");
        Lift l = null;
        if (lift != null && !lift.isJsonNull()) {
            for (Lift x : Lift.values()) if (x.value.equals(lift.getAsString())) l = x;
            if (l == null) throw new IllegalArgumentException("unknown lift " + lift);
        }
        return new Instruction(op, inouts, intOr(o, "index", -1), o.has("gen") ? o.get("gen").getAsString() : "",
                intOr(o, "origin", 0), intOr(o, "step", 0), l);
    }

    private static int intOr(JsonObject o, String k, int d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d;
    }

    public static Operation op(String name, JsonObject args) {
        Class<? extends Operation> c = OPS.get(name);
        if (c == null) throw new IllegalArgumentException("unknown operation " + name);
        return (Operation) record(c, args);
    }

    private static Object record(Class<?> c, JsonObject fields) {
        RecordComponent[] cs = c.getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int k = 0; k < cs.length; k++) {
            String key = Repr.snake(cs[k].getName());
            if (!fields.has(key)) throw new IllegalArgumentException(c.getSimpleName() + ": missing field " + key);
            values[k] = value(fields.get(key), cs[k].getGenericType());
            types[k] = cs[k].getType();
        }
        if (fields.size() != cs.length) throw new IllegalArgumentException(c.getSimpleName() + ": fields " + fields.keySet());
        try {
            Constructor<?> ctor = c.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(values);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One value, decoded for the Java type it is to become. */
    public static Object value(JsonElement e, java.lang.reflect.Type target) {
        if (e == null || e.isJsonNull()) return null;
        if (target instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                java.lang.reflect.Type el = pt.getActualTypeArguments()[0];
                List<Object> out = new ArrayList<>();
                for (JsonElement x : e.getAsJsonArray()) out.add(value(x, el));
                return out;
            }
            throw new IllegalArgumentException("unsupported type " + target);
        }
        Class<?> c = (Class<?>) target;
        if (c == long.class || c == Long.class) return e.getAsLong();
        if (c == int.class || c == Integer.class) return e.getAsInt();
        if (c == boolean.class || c == Boolean.class) return e.getAsBoolean();
        if (c == double.class || c == Double.class) return decodeFloat(e);
        if (c == String.class) return e.getAsString();
        if (c == BigInteger.class) return e.getAsBigInteger();
        JsonObject o = e.getAsJsonObject();
        if (c.isEnum()) {
            String name = o.get("name").getAsString();
            for (Object k : c.getEnumConstants()) if (((Enum<?>) k).name().equals(name)) return k;
            throw new IllegalArgumentException("no " + c.getSimpleName() + "." + name);
        }
        if (c == Flags.class) {
            String type = o.get("$flag").getAsString();
            int bits = o.get("bits").getAsInt();
            return switch (type) {
                case "RegExpFlag" -> RegExpFlag.of(bits);
                case "PropertyFlag" -> PropertyFlag.of(bits);
                default -> throw new IllegalArgumentException("unknown flag type " + type);
            };
        }
        if (c == Key.class) return key(o.get("$key").getAsString(), o.get("kind").getAsString());
        if (c == Builtin.class) return Env.builtin(o.get("$builtin").getAsString());
        if (c == Method.class) return Env.method(o.get("$method").getAsString());
        if (c == Type.class) return type(o.get("$type"));
        if (c == Parameters.class) return parameters(o.getAsJsonObject("$params"));
        if (o.has("$dc")) {
            Class<?> pc = PATTERN_CLASSES.get(o.get("$dc").getAsString());
            if (pc == null || !c.isAssignableFrom(pc)) throw new IllegalArgumentException("unexpected " + o.get("$dc") + " for " + c);
            return record(pc, o.getAsJsonObject("f"));
        }
        throw new IllegalArgumentException("cannot decode " + e + " as " + c);
    }

    static double decodeFloat(JsonElement e) {
        String s = e.getAsJsonObject().get("$f").getAsString();
        return switch (s) {
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            case "nan" -> Double.NaN;
            default -> Double.parseDouble(s);
        };
    }

    private static Key key(String name, String kindValue) {
        for (KeyKind k : KeyKind.values()) if (k.value.equals(kindValue)) return new Key(name, k);
        throw new IllegalArgumentException("unknown key kind " + kindValue);
    }

    public static Type type(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        JsonObject o = e.getAsJsonObject();
        Signature sig = null;
        if (o.has("signature") && !o.get("signature").isJsonNull()) {
            JsonObject so = o.getAsJsonObject("signature");
            List<Param> ps = new ArrayList<>();
            for (JsonElement p : so.getAsJsonArray("params")) {
                JsonArray pa = p.getAsJsonArray();
                ParamMode mode = null;
                for (ParamMode m : ParamMode.values()) if (m.value.equals(pa.get(1).getAsString())) mode = m;
                ps.add(new Param(type(pa.get(0)), mode));
            }
            sig = new Signature(ps, type(so.get("returns")));
        }
        Set<Key> props = new TreeSet<>();
        for (JsonElement p : o.getAsJsonArray("props")) {
            JsonArray pa = p.getAsJsonArray();
            props.add(key(pa.get(0).getAsString(), pa.get(1).getAsString()));
        }
        JsonElement g = o.get("group");
        return new Type(o.get("possible").getAsInt(), g == null || g.isJsonNull() ? null : g.getAsString(), sig,
                type(o.get("element")), props);
    }

    private static Parameters parameters(JsonObject o) {
        List<Integer> defaults = new ArrayList<>();
        for (JsonElement d : o.getAsJsonArray("default_indices")) defaults.add(d.getAsInt());
        List<Map.Entry<Integer, Patterns.Pattern>> destructuring = new ArrayList<>();
        for (JsonElement d : o.getAsJsonArray("destructuring")) {
            JsonArray pair = d.getAsJsonArray();
            destructuring.add(new AbstractMap.SimpleImmutableEntry<>(pair.get(0).getAsInt(),
                    (Patterns.Pattern) value(pair.get(1), Patterns.Pattern.class)));
        }
        return new Parameters(o.get("count").getAsInt(), o.get("has_rest").getAsBoolean(), defaults, destructuring);
    }

    public static GenOptions options(JsonObject o) {
        GenOptions.Builder b = GenOptions.builder()
                .minStatements(o.get("min_statements").isJsonNull() ? null : o.get("min_statements").getAsInt())
                .maxStatements(o.get("max_statements").getAsInt())
                .maxDepth(o.get("max_depth").getAsInt())
                .probeEvery(o.get("probe_every").getAsInt())
                .probeWidth(o.get("probe_width").getAsInt())
                .strict(o.get("strict").getAsBoolean())
                .languageVersion(o.get("language_version").getAsInt())
                .toggles(strings(o.get("toggles")))
                .families(strings(o.get("families")))
                .generators(strings(o.get("generators")));
        Map<String, Double> weights = new LinkedHashMap<>();
        if (o.has("weights")) for (var w : o.getAsJsonObject("weights").entrySet()) weights.put(w.getKey(), w.getValue().getAsDouble());
        return b.weights(weights).build();
    }

    private static Set<String> strings(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        Set<String> out = new TreeSet<>();
        for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        return out;
    }

    // ------------------------------------------------------------------------- writing
    public static String writeCode(List<Instruction> code) { return toJson(code).toString(); }

    public static JsonArray toJson(List<Instruction> code) {
        JsonArray a = new JsonArray();
        for (Instruction i : code) a.add(toJson(i));
        return a;
    }

    public static JsonObject toJson(Instruction i) {
        JsonObject o = new JsonObject();
        o.addProperty("op", i.op().name());
        o.add("args", fields((Record) i.op()));
        JsonArray io = new JsonArray();
        for (int v : i.inouts()) io.add(v);
        o.add("io", io);
        o.addProperty("index", i.index());
        o.addProperty("gen", i.gen());
        o.addProperty("origin", i.origin());
        o.addProperty("step", i.step());
        o.add("lift", i.lift() == null ? JsonNull.INSTANCE : new JsonPrimitive(i.lift().value));
        return o;
    }

    private static JsonObject fields(Record r) {
        JsonObject o = new JsonObject();
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            try {
                o.add(Repr.snake(c.getName()), toJson(c.getAccessor().invoke(r)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return o;
    }

    /** One value in the format {@link #value} reads. */
    public static JsonElement toJson(Object x) {
        if (x == null) return JsonNull.INSTANCE;
        if (x instanceof Boolean b) return new JsonPrimitive(b);
        if (x instanceof Double d) {
            JsonObject o = new JsonObject();
            o.addProperty("$f", d.isNaN() ? "nan" : d.isInfinite() ? (d > 0 ? "inf" : "-inf") : Double.toHexString(d));
            return o;
        }
        if (x instanceof Number n) return new JsonPrimitive(n);
        if (x instanceof String s) return new JsonPrimitive(s);
        if (x instanceof Enum<?> e) {
            JsonObject o = new JsonObject();
            o.addProperty("$enum", e.getDeclaringClass().getSimpleName().equals("CompareOperator") ? "Comparator"
                    : e.getDeclaringClass().getSimpleName());
            o.addProperty("name", e.name());
            return o;
        }
        if (x instanceof Flags f) {
            JsonObject o = new JsonObject();
            o.addProperty("$flag", f.type());
            o.addProperty("bits", f.bits());
            return o;
        }
        if (x instanceof Key k) {
            JsonObject o = new JsonObject();
            o.addProperty("$key", k.name());
            o.addProperty("kind", k.kind().value);
            return o;
        }
        if (x instanceof Builtin b) {
            JsonObject o = new JsonObject();
            String attr = BUILTIN_ATTR.get(b);
            if (attr == null) throw new IllegalArgumentException("builtin not in the registry: " + b);
            o.addProperty("$builtin", attr);
            return o;
        }
        if (x instanceof Method m) {
            JsonObject o = new JsonObject();
            String attr = METHOD_ATTR.get(m);
            if (attr == null) throw new IllegalArgumentException("method not in the registry: " + m);
            o.addProperty("$method", attr);
            return o;
        }
        if (x instanceof Type t) {
            JsonObject o = new JsonObject();
            o.add("$type", typeJson(t));
            return o;
        }
        if (x instanceof Parameters p) {
            JsonObject inner = new JsonObject();
            inner.addProperty("count", p.count());
            inner.addProperty("has_rest", p.hasRest());
            JsonArray d = new JsonArray();
            for (int k : p.defaultIndices()) d.add(k);
            inner.add("default_indices", d);
            JsonArray ds = new JsonArray();
            for (var e : p.destructuring()) {
                JsonArray pair = new JsonArray();
                pair.add(e.getKey());
                pair.add(toJson(e.getValue()));
                ds.add(pair);
            }
            inner.add("destructuring", ds);
            JsonObject o = new JsonObject();
            o.add("$params", inner);
            return o;
        }
        if (x instanceof List<?> l) {
            JsonArray a = new JsonArray();
            for (Object y : l) a.add(toJson(y));
            return a;
        }
        if (x instanceof Record r && PATTERN_CLASSES.containsKey(r.getClass().getSimpleName())) {
            JsonObject o = new JsonObject();
            o.addProperty("$dc", r.getClass().getSimpleName());
            o.add("f", fields(r));
            return o;
        }
        throw new IllegalArgumentException("cannot encode " + x);
    }

    private static JsonObject typeJson(Type t) {
        JsonObject o = new JsonObject();
        o.addProperty("possible", t.possible());
        o.add("group", t.group() == null ? JsonNull.INSTANCE : new JsonPrimitive(t.group()));
        if (t.signature() == null) {
            o.add("signature", JsonNull.INSTANCE);
        } else {
            JsonObject s = new JsonObject();
            JsonArray ps = new JsonArray();
            for (Param p : t.signature().params()) {
                JsonArray pa = new JsonArray();
                pa.add(typeJson(p.type()));
                pa.add(p.mode().value);
                ps.add(pa);
            }
            s.add("params", ps);
            s.add("returns", typeJson(t.signature().returns()));
            o.add("signature", s);
        }
        o.add("element", t.element() == null ? JsonNull.INSTANCE : typeJson(t.element()));
        JsonArray props = new JsonArray();
        for (Key k : t.props()) {
            JsonArray pa = new JsonArray();
            pa.add(k.name());
            pa.add(k.kind().value);
            props.add(pa);
        }
        o.add("props", props);
        return o;
    }

    public static JsonObject toJson(GenOptions o) {
        JsonObject j = new JsonObject();
        j.addProperty("min_statements", o.minStatements());
        j.addProperty("max_statements", o.maxStatements());
        j.addProperty("max_depth", o.maxDepth());
        j.addProperty("probe_every", o.probeEvery());
        j.addProperty("probe_width", o.probeWidth());
        j.addProperty("strict", o.strict());
        j.addProperty("language_version", o.languageVersion());
        j.add("toggles", stringsJson(o.toggles()));
        j.add("families", stringsJson(o.families()));
        j.add("generators", stringsJson(o.generators()));
        JsonObject w = new JsonObject();
        for (var e : o.weights().entrySet()) w.addProperty(e.getKey(), e.getValue());
        j.add("weights", w);
        return j;
    }

    private static JsonElement stringsJson(Set<String> s) {
        if (s == null) return JsonNull.INSTANCE;
        JsonArray a = new JsonArray();
        for (String x : new TreeSet<>(s)) a.add(x);
        return a;
    }
}
