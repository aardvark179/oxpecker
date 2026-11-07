package dev.oxpecker.harness;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Delegator;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.SerializableCallable;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.Wrapper;

/**
 * Java port of canon.js. Produces the same {@link Host.Canonical} for the same value graph, but
 * runs as Java instead of being interpreted. Like canon.js, every builtin it calls into is
 * captured at install time; the walk itself uses no JS-visible builtins at all (canon.js
 * looked up {@code Array.prototype.push}, {@code String.prototype.slice}, {@code Error} etc.
 * at call time, so it could be perturbed by monkey-patching; this version cannot).
 *
 * <p>Object-model operations (getOwnPropertyDescriptor, ownKeys, getPrototypeOf, …) still go
 * through the captured builtins so exotic objects (typed arrays, Java wrappers, arrays,
 * arguments, …) are seen exactly as script sees them.
 */
final class JavaCanon {
    private static final int MAX_DEPTH = 8, MAX_NODES = 600, MAX_ARRAY = 200, MAX_STRING = 400;
    private static final Object[] NO_ARGS = ScriptRuntime.emptyArgs;
    private static final Set<String> EXCLUDED_ERROR_KEYS =
            Set.of("stack", "fileName", "lineNumber", "columnNumber", "rhinoException", "javaException");
    private static final String[] WELL_KNOWN = {
        "iterator", "asyncIterator", "hasInstance", "isConcatSpreadable", "match", "matchAll",
        "replace", "search", "species", "split", "toPrimitive", "toStringTag", "unscopables"
    };
    private static final String[] CTOR_NAMES = {
        "Object", "Array", "Function", "String", "Number", "Boolean", "Symbol", "BigInt",
        "Date", "RegExp", "Error", "TypeError", "RangeError", "SyntaxError", "ReferenceError",
        "EvalError", "URIError", "Map", "Set", "WeakMap", "WeakSet", "Promise", "ArrayBuffer",
        "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array",
        "Int32Array", "Uint32Array", "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array"
    };

    private final TopLevel scope;
    private final ScriptableObject global;

    private final Callable ownKeys, gopd, gpo, isArray, isExtensible, jsonStringify, symKeyFor;
    private final Callable objToString, fnToString, symToString;
    private final Callable numberValueOf, stringValueOf, booleanValueOf, bigintValueOf;
    private final Callable dateGetTime, regexpToString, mapForEach, setForEach, mapSize, setSize;
    private final Callable taLength, symDesc;
    private final Object errorProto;

    private final List<Object> wellKnownSyms = new ArrayList<>();
    private final List<String> wellKnownNames = new ArrayList<>();
    /** Builtin prototypes and their names, in canon.js's order (the first match wins). */
    private final IdentityHashMap<Object, String> protoNames = new IdentityHashMap<>();

    JavaCanon(Context cx, TopLevel scope) {
        this.scope = scope;
        this.global = scope.getGlobalThis();
        Scriptable objectCtor = obj(global, "Object");
        Scriptable reflect = obj(global, "Reflect");
        Scriptable symbol = obj(global, "Symbol");
        ownKeys = fn(reflect, "ownKeys");
        gopd = fn(objectCtor, "getOwnPropertyDescriptor");
        gpo = fn(objectCtor, "getPrototypeOf");
        isExtensible = fn(objectCtor, "isExtensible");
        isArray = fn(obj(global, "Array"), "isArray");
        jsonStringify = fn(obj(global, "JSON"), "stringify");
        symKeyFor = fn(symbol, "keyFor");
        objToString = fn(proto(global, "Object"), "toString");
        fnToString = fn(proto(global, "Function"), "toString");
        symToString = fn(proto(global, "Symbol"), "toString");
        numberValueOf = fn(proto(global, "Number"), "valueOf");
        stringValueOf = fn(proto(global, "String"), "valueOf");
        booleanValueOf = fn(proto(global, "Boolean"), "valueOf");
        Object bigint = ScriptableObject.getProperty(global, "BigInt");
        bigintValueOf = bigint instanceof Callable ? fn(proto(global, "BigInt"), "valueOf") : null;
        dateGetTime = fn(proto(global, "Date"), "getTime");
        regexpToString = fn(proto(global, "RegExp"), "toString");
        mapForEach = fn(proto(global, "Map"), "forEach");
        setForEach = fn(proto(global, "Set"), "forEach");
        mapSize = getter(cx, proto(global, "Map"), "size");
        setSize = getter(cx, proto(global, "Set"), "size");
        Object taProto = call(cx, gpo, Undefined.instance, proto(global, "Int8Array"));
        taLength = taProto instanceof Scriptable t ? getter(cx, t, "length") : null;
        symDesc = getter(cx, proto(global, "Symbol"), "description");
        errorProto = proto(global, "Error");

        for (String w : WELL_KNOWN) {
            Object s = get(symbol, w);
            if ("symbol".equals(typeOf(s))) {
                wellKnownSyms.add(s);
                wellKnownNames.add("Symbol." + w);
            }
        }
        for (String c : CTOR_NAMES) {
            Object ctor = get(global, c);
            if (ctor instanceof Callable && "function".equals(typeOf(ctor))) {
                Object p = ScriptableObject.getProperty((Scriptable) ctor, "prototype");
                if (p instanceof Scriptable && ScriptRuntime.toBoolean(p)) protoNames.putIfAbsent(p, c + ".prototype");
            }
        }
        if (taProto instanceof Scriptable && ScriptRuntime.toBoolean(taProto)) protoNames.putIfAbsent(taProto, "TypedArray.prototype");
        try {
            Object gen = cx.evaluateString(scope, "(function* () {})()", "canon-gen", 1, null);
            Object genProto = call(cx, gpo, Undefined.instance, call(cx, gpo, Undefined.instance, gen));
            if (genProto instanceof Scriptable) protoNames.putIfAbsent(genProto, "Generator.prototype");
        } catch (RuntimeException e) {
            // no generators: nothing to name
        }
    }

    // ---- errName --------------------------------------------------------------------------

    String errName(Context cx, Object v) {
        String t = typeOf(v);
        if (v == null || (!t.equals("object") && !t.equals("function"))) return t;
        Object n = dataProp(cx, v, "name");
        return n instanceof CharSequence cs ? cs.toString() : "Object";
    }

    // ---- canon ----------------------------------------------------------------------------

    Host.Canonical canon(Context cx, Object root) {
        Walk w = new Walk(cx);
        String text = w.walk(root, 0);
        return new Host.Canonical(text, String.join(",", w.jtypes), String.join("\n", w.soft));
    }

    private final class Walk {
        final Context cx;
        final List<String> jtypes = new ArrayList<>();
        final List<String> soft = new ArrayList<>();
        /** Visited objects to reference id; Java wrappers are keyed by what they wrap (=== semantics). */
        final IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
        final IdentityHashMap<Object, Integer> seenWrapped = new IdentityHashMap<>();
        int seenCount;
        int nodes;

        Walk(Context cx) {
            this.cx = cx;
        }

        Integer seenId(Object v) {
            if (v instanceof Wrapper w) return seenWrapped.get(w.unwrap());
            if (v instanceof Delegator d) {
                Integer id = seen.get(d.getDelegee());
                if (id != null) return id;
            }
            return seen.get(v);
        }

        int addSeen(Object v) {
            int id = seenCount++;
            if (v instanceof Wrapper w) seenWrapped.putIfAbsent(w.unwrap(), id);
            else seen.putIfAbsent(v, id);
            return id;
        }

        String number(Object v) {
            jtypes.add(Host.jtype(v));
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) return "NaN";
            if (d == 0) return (1 / d) < 0 ? "-0" : "0";
            return ScriptRuntime.toString(v);
        }

        String string(Object v) {
            jtypes.add(Host.jtype(v));
            CharSequence cs = (CharSequence) v;
            int len = cs.length();
            if (len > MAX_STRING) return json(cx, cs.subSequence(0, MAX_STRING).toString()) + "…[len=" + len + "]";
            return json(cx, cs.toString());
        }

        /** canon.js {@code prim}: the rendering of a primitive, or null for an object. */
        String prim(Object v) {
            switch (typeOf(v)) {
                case "undefined":
                    return "undefined";
                case "boolean":
                    return ScriptRuntime.toBoolean(v) ? "true" : "false";
                case "number":
                    return number(v);
                case "string":
                    return string(v);
                case "bigint":
                    jtypes.add(Host.jtype(v));
                    return ScriptRuntime.toString(v) + "n";
                case "symbol":
                    return symbolText(cx, v);
                default:
                    return v == null ? "null" : null;
            }
        }

        String protoText(Object v) {
            Object p;
            try {
                p = call(cx, gpo, Undefined.instance, v);
            } catch (RuntimeException e) {
                if (!catchable(cx, e)) throw e;
                return "?";
            }
            if (p == null) return "null";
            String n = protoNames.get(p);
            if (n != null) return n;
            Integer id = seenId(p);
            if (id != null) return "@" + id;
            return "{…}";
        }

        String descriptor(Object o, Object key) {
            Object d;
            try {
                d = call(cx, gopd, Undefined.instance, o, key);
            } catch (RuntimeException e) {
                if (!catchable(cx, e)) throw e;
                return "!" + errText(cx, e);
            }
            if (!truthy(d)) return "<missing>";
            Scriptable ds = (Scriptable) d;
            String flags = (truthy(get(ds, "enumerable")) ? "e" : "-") + (truthy(get(ds, "configurable")) ? "c" : "-");
            if (ScriptableObject.hasProperty(ds, "get") || ScriptableObject.hasProperty(ds, "set")) {
                return "{" + flags + (truthy(get(ds, "get")) ? "g" : "-") + (truthy(get(ds, "set")) ? "s" : "-") + "}";
            }
            return "{" + flags + (truthy(get(ds, "writable")) ? "w" : "-") + "}";
        }

        String dataValue(Object o, Object key, int depth) {
            Object d;
            try {
                d = call(cx, gopd, Undefined.instance, o, key);
            } catch (RuntimeException e) {
                if (!catchable(cx, e)) throw e;
                return "!" + errText(cx, e);
            }
            if (!truthy(d) || !ScriptableObject.hasProperty((Scriptable) d, "value")) return "";
            return walk(get((Scriptable) d, "value"), depth);
        }

        String walk(Object v, int depth) {
            String p = prim(v);
            if (p != null) return p;
            if (++nodes > MAX_NODES) return "…nodes";
            Integer prev = seenId(v);
            if (prev != null) return "@" + prev;
            int id = addSeen(v);
            String jt = Host.jtype(v);
            if (jt.equals("NativeJavaObject") || jt.equals("NativeJavaArray") || jt.equals("NativeJavaClass")) {
                return "@" + id + "[Java " + javaClass(v) + "]";
            }
            if (Host.isProxy(v)) return "@" + id + "[Proxy]";
            if (v == global) return "@" + id + "[global]";
            String pn = protoNames.get(v);
            if (pn != null) return "@" + id + "[" + pn + "]";
            boolean isFunction = typeOf(v).equals("function");
            if (isFunction) {
                String nsrc;
                try {
                    nsrc = ScriptRuntime.toString(call(cx, fnToString, v));
                } catch (RuntimeException e) {
                    if (!catchable(cx, e)) throw e;
                    nsrc = "";
                }
                if (nsrc.contains("[native code]")) {
                    Object nname = dataProp(cx, v, "name");
                    return "@" + id + "[native " + (nname instanceof CharSequence ? nname.toString() : "?") + "/"
                            + concat(cx, dataProp(cx, v, "length")) + "]";
                }
            }
            if (depth > MAX_DEPTH) return "@" + id + "…depth";
            String tag = tagOf(cx, v);
            String head = "@" + id + jsSlice(tag, 8, -1);
            List<String> parts = new ArrayList<>();
            boolean errorLike = isErrorLike(cx, v, tag);

            if (isFunction) {
                Object name = dataProp(cx, v, "name"), len = dataProp(cx, v, "length");
                head = "@" + id + "function";
                parts.add("name=" + (name instanceof CharSequence ? json(cx, name.toString()) : "?"));
                parts.add("length=" + ("number".equals(typeOf(len)) ? ScriptRuntime.toString(len) : "?"));
                String src;
                try {
                    src = ScriptRuntime.toString(call(cx, fnToString, v));
                } catch (RuntimeException e) {
                    if (!catchable(cx, e)) throw e;
                    src = "!" + errText(cx, e);
                }
                parts.add("src=" + json(cx, src.length() > MAX_STRING ? src.substring(0, MAX_STRING) + "…" : src));
            } else if (tag.equals("[object Number]") || tag.equals("[object String]")
                    || tag.equals("[object Boolean]") || tag.equals("[object BigInt]")) {
                Callable vo = tag.equals("[object Number]") ? numberValueOf
                        : tag.equals("[object String]") ? stringValueOf
                        : tag.equals("[object Boolean]") ? booleanValueOf : bigintValueOf;
                try {
                    if (vo == null) parts.add("boxed=!");
                    else parts.add("boxed=" + walk(call(cx, vo, v), depth + 1));
                } catch (RuntimeException e) {
                    if (!catchable(cx, e)) throw e;
                    parts.add("boxed=!");
                }
            } else if (tag.equals("[object Date]")) {
                try {
                    parts.add("time=" + walk(call(cx, dateGetTime, v), depth + 1));
                } catch (RuntimeException e) {
                    if (!catchable(cx, e)) throw e;
                    parts.add("time=!");
                }
            } else if (tag.equals("[object RegExp]")) {
                try {
                    parts.add("re=" + ScriptRuntime.toString(call(cx, regexpToString, v)));
                } catch (RuntimeException e) {
                    if (!catchable(cx, e)) throw e;
                    parts.add("re=!");
                }
            } else if (errorLike) {
                Object msg = dataProp(cx, v, "message");
                if (msg instanceof CharSequence) soft.add(msg.toString());
            }

            // Map / Set contents, found by brand check (the getters throw on non-instances).
            String mapLike = null;
            if (brand(mapSize, v)) mapLike = "map";
            else if (brand(setSize, v)) mapLike = "set";
            if (mapLike != null) {
                boolean isMap = mapLike.equals("map");
                List<String> ents = new ArrayList<>();
                SerializableCallable each = (c, s, t, args) -> {
                    Object val = args.length > 0 ? args[0] : Undefined.instance;
                    if (isMap) {
                        Object key = args.length > 1 ? args[1] : Undefined.instance;
                        String k = walk(key, depth + 1);
                        ents.add(k + "=>" + walk(val, depth + 1));
                    } else {
                        ents.add(walk(val, depth + 1));
                    }
                    return Undefined.instance;
                };
                try {
                    call(cx, isMap ? mapForEach : setForEach, v,
                            new LambdaFunction(scope, "", 2, each));
                } catch (RuntimeException e) {
                    if (!catchable(cx, e)) throw e;
                    ents.add("!" + errText(cx, e));
                }
                parts.add((isMap ? "entries=[" : "values=[") + String.join(",", ents) + "]");
            }

            // Typed arrays: elements are not own-key enumerable in a useful way; render them explicitly.
            Object taLen = -1;
            if (taLength != null) {
                try {
                    taLen = call(cx, taLength, v);
                } catch (RuntimeException e) {
                    if (!catchable(cx, e)) throw e;
                    taLen = -1;
                }
            }
            if ("number".equals(typeOf(taLen)) && ((Number) taLen).doubleValue() >= 0) {
                double tl = ((Number) taLen).doubleValue();
                List<String> tel = new ArrayList<>();
                double tn = tl > MAX_ARRAY ? MAX_ARRAY : tl;
                for (int ti = 0; ti < tn; ti++) tel.add(walk(ScriptRuntime.getObjectIndex(v, ti, cx, scope), depth + 1));
                if (tl > tn) tel.add("…[len=" + ScriptRuntime.toString(taLen) + "]");
                parts.add("elements=[" + String.join(",", tel) + "]");
            }

            Object keysObj;
            try {
                keysObj = call(cx, ownKeys, Undefined.instance, v);
            } catch (RuntimeException e) {
                if (!catchable(cx, e)) throw e;
                keysObj = null;
            }
            if (keysObj == null) {
                parts.add("keys=!");
            } else {
                Object[] keys = elements((Scriptable) keysObj);
                if (ScriptRuntime.toBoolean(call(cx, isArray, Undefined.instance, v))) {
                    Object len = dataProp(cx, v, "length");
                    double n = "number".equals(typeOf(len)) ? ((Number) len).doubleValue() : 0;
                    double shown = n > MAX_ARRAY ? MAX_ARRAY : n;
                    List<String> els = new ArrayList<>();
                    for (int ai = 0; ai < shown; ai++) {
                        Object d;
                        try {
                            d = call(cx, gopd, Undefined.instance, v, ai);
                        } catch (RuntimeException e) {
                            if (!catchable(cx, e)) throw e;
                            d = null;
                        }
                        if (!truthy(d)) {
                            els.add("<hole>");
                            continue;
                        }
                        Scriptable ds = (Scriptable) d;
                        if (ScriptableObject.hasProperty(ds, "get") || ScriptableObject.hasProperty(ds, "set")) {
                            els.add(descriptor(v, ai));
                        } else {
                            String ev = walk(get(ds, "value"), depth + 1);
                            boolean plain = truthy(get(ds, "writable")) && truthy(get(ds, "enumerable"))
                                    && truthy(get(ds, "configurable"));
                            els.add(ev + (plain ? "" : descriptor(v, ai)));
                        }
                    }
                    if (n > shown) els.add("…");
                    parts.add("length=" + ScriptRuntime.toString(n));
                    parts.add("[" + String.join(",", els) + "]");
                    for (Object key : keys) {
                        if (key instanceof CharSequence ks) {
                            String s = ks.toString();
                            if (s.equals("length")) continue;
                            long u = ScriptRuntime.toUint32(s);
                            if (ScriptRuntime.toString((double) u).equals(s) && u < shown) continue;
                        }
                        parts.add(keyText(cx, key) + descriptor(v, key) + ":" + dataValue(v, key, depth + 1));
                    }
                } else {
                    for (Object key : keys) {
                        if (errorLike && key instanceof CharSequence ks && EXCLUDED_ERROR_KEYS.contains(ks.toString())) continue;
                        if (errorLike && isStr(key, "message")) {
                            parts.add("\"message\"" + descriptor(v, key));
                            continue;
                        }
                        if (isFunction && (isStr(key, "arguments") || isStr(key, "caller"))) continue;
                        parts.add(keyText(cx, key) + descriptor(v, key) + ":" + dataValue(v, key, depth + 1));
                    }
                }
            }
            Object ext;
            try {
                ext = call(cx, isExtensible, Undefined.instance, v);
            } catch (RuntimeException e) {
                if (!catchable(cx, e)) throw e;
                ext = "?";
            }
            if (Boolean.FALSE.equals(ext)) parts.add("non-extensible");
            return head + "<" + protoText(v) + ">{" + String.join(";", parts) + "}";
        }

        boolean brand(Callable sizeGetter, Object v) {
            try {
                call(cx, sizeGetter, v);
                return true;
            } catch (RuntimeException e) {
                if (!catchable(cx, e)) throw e;
                return false;
            }
        }
    }

    // ---- helpers shared by canon and errName ----------------------------------------------

    private String tagOf(Context cx, Object v) {
        try {
            return ScriptRuntime.toString(call(cx, objToString, v));
        } catch (RuntimeException e) {
            if (!catchable(cx, e)) throw e;
            return "[object ?]";
        }
    }

    private boolean isErrorLike(Context cx, Object v, String tag) {
        if (tag.equals("[object Error]")) return true;
        Object p = v;
        int guard = 0;
        try {
            while (truthy(p) && guard++ < 50) {
                if (p == errorProto) return true;
                p = call(cx, gpo, Undefined.instance, p);
            }
        } catch (RuntimeException e) {
            if (!catchable(cx, e)) throw e;
        }
        return false;
    }

    /** Looks a property up along the prototype chain without invoking accessors. */
    private Object dataProp(Context cx, Object o, String key) {
        Object p = o;
        int guard = 0;
        try {
            while (p != null && !Undefined.isUndefined(p) && guard++ < 50) {
                if (Host.isProxy(p)) return Undefined.instance;
                Object d = call(cx, gopd, Undefined.instance, p, key);
                if (truthy(d)) {
                    Scriptable ds = (Scriptable) d;
                    return ScriptableObject.hasProperty(ds, "value") ? get(ds, "value") : Undefined.instance;
                }
                p = call(cx, gpo, Undefined.instance, p);
            }
        } catch (RuntimeException e) {
            if (!catchable(cx, e)) throw e;
        }
        return Undefined.instance;
    }

    private String keyText(Context cx, Object k) {
        if ("symbol".equals(typeOf(k))) return symbolText(cx, k);
        return ScriptRuntime.toString(call(cx, jsonStringify, Undefined.instance, k));
    }

    private String symbolText(Context cx, Object s) {
        for (int i = 0; i < wellKnownSyms.size(); i++) {
            if (ScriptRuntime.shallowEq(wellKnownSyms.get(i), s)) return wellKnownNames.get(i);
        }
        Object reg;
        try {
            reg = call(cx, symKeyFor, Undefined.instance, s);
        } catch (RuntimeException e) {
            if (!catchable(cx, e)) throw e;
            reg = Undefined.instance;
        }
        if (!Undefined.isUndefined(reg)) return "Symbol.for(" + json(cx, reg) + ")";
        Object d;
        try {
            d = symDesc != null ? call(cx, symDesc, s) : Undefined.instance;
        } catch (RuntimeException e) {
            if (!catchable(cx, e)) throw e;
            d = Undefined.instance;
        }
        if (Undefined.isUndefined(d)) {
            try {
                return ScriptRuntime.toString(call(cx, symToString, s));
            } catch (RuntimeException e) {
                if (!catchable(cx, e)) throw e;
                return "Symbol(?)";
            }
        }
        return "Symbol(" + json(cx, d) + ")";
    }

    private String json(Context cx, Object v) {
        return ScriptRuntime.toString(call(cx, jsonStringify, Undefined.instance, v));
    }

    private Object call(Context cx, Callable f, Object thisObj, Object... args) {
        return f.call(cx, scope, thisObj, args.length == 0 ? NO_ARGS : args);
    }

    /** {@code '' + v}: ToPrimitive with no hint, then ToString. */
    private static String concat(Context cx, Object v) {
        return ScriptRuntime.toString(ScriptRuntime.add("", v, cx));
    }

    /** The string {@code '!' + e} gives in a JS catch block for this exception. */
    private String errText(Context cx, RuntimeException e) {
        Object value;
        if (e instanceof JavaScriptException jse) {
            value = jse.getValue();
        } else {
            VarScope cs = ScriptRuntime.newCatchScope(e, null, "e", cx, scope);
            value = cs.get("e", cs);
        }
        return concat(cx, value);
    }

    /** Whether a JS {@code catch} in the interpreter would catch {@code e} (Interpreter's exState). */
    private static boolean catchable(Context cx, RuntimeException e) {
        if (e instanceof JavaScriptException || e instanceof EcmaError || e instanceof EvaluatorException) return true;
        if (e instanceof org.mozilla.javascript.ContinuationPending) return false;
        return cx.hasFeature(Context.FEATURE_ENHANCED_JAVA_ACCESS);
    }

    private static String javaClass(Object v) {
        if (v instanceof Wrapper w) return w.unwrap() == null ? "null" : w.unwrap().getClass().getName();
        return "?";
    }

    private static String typeOf(Object v) {
        return ScriptRuntime.typeof(v);
    }

    private static boolean truthy(Object v) {
        return v != Scriptable.NOT_FOUND && ScriptRuntime.toBoolean(v);
    }

    private static boolean isStr(Object v, String s) {
        return v instanceof CharSequence cs && s.contentEquals(cs);
    }

    private static Object get(Scriptable o, String name) {
        Object r = ScriptableObject.getProperty(o, name);
        return r == Scriptable.NOT_FOUND ? Undefined.instance : r;
    }

    /** JS {@code s.slice(start, end)}. */
    private static String jsSlice(String s, int start, int end) {
        int len = s.length();
        int from = start < 0 ? Math.max(len + start, 0) : Math.min(start, len);
        int to = end < 0 ? Math.max(len + end, 0) : Math.min(end, len);
        return from < to ? s.substring(from, to) : "";
    }

    private static Object[] elements(Scriptable arr) {
        long n = ScriptRuntime.toUint32(ScriptableObject.getProperty(arr, "length"));
        Object[] out = new Object[(int) n];
        for (int i = 0; i < n; i++) out[i] = get(arr, i);
        return out;
    }

    private static Object get(Scriptable o, int index) {
        Object r = ScriptableObject.getProperty(o, index);
        return r == Scriptable.NOT_FOUND ? Undefined.instance : r;
    }

    private static Scriptable obj(Scriptable o, String name) {
        return (Scriptable) ScriptableObject.getProperty(o, name);
    }

    private static Scriptable proto(Scriptable global, String ctor) {
        return (Scriptable) ScriptableObject.getProperty(obj(global, ctor), "prototype");
    }

    private static Callable fn(Scriptable o, String name) {
        return (Callable) ScriptableObject.getProperty(o, name);
    }

    private Callable getter(Context cx, Scriptable o, String name) {
        Object d = call(cx, gopd, Undefined.instance, o, name);
        if (!truthy(d)) return null;
        Object g = get((Scriptable) d, "get");
        return g instanceof Callable c ? c : null;
    }
}
