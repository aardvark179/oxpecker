// The registry shape follows Fuzzilli's JavaScriptEnvironment.swift (Copyright 2019-2025 Google
// LLC, Apache License 2.0; commit be2811d00282320381fc8007d64873745772011d): object groups with
// typed properties and method signatures, builtins registered with types. The data comes from
// oxpecker's own probe of Rhino (rhino-capabilities.json) plus the hand-typed overlay below.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.BOOLEAN;
import static dev.oxpecker.il.Type.FLOAT;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NULL;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.STRING;
import static dev.oxpecker.il.Type.SYMBOL;
import static dev.oxpecker.il.Type.UNDEFINED;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Typed registries of what Rhino exposes: builtins, property keys, methods, value pools.
 *
 * <p>Generators never spell a builtin, key or method as JavaScript; they look them up here by
 * registry name ({@link #builtin}, {@link #key}, {@link #method}) and the lifter is the only reader
 * of {@code .name()}. The registry is built from the capability table on the classpath (refreshed by
 * {@code oxpecker probe}); system property {@code oxpecker.capabilities} names another file.
 * Every map keeps the table's order, so pools drawn from it are deterministic.
 */
public final class Env {
    private Env() {}

    public static final String CTOR = "Ctor";

    // Builtins the generators should not poke at (CapabilityProbe.EXCLUDED_GLOBALS in oxpecker-fuzz).
    public static final Set<String> EXCLUDED_GLOBALS = Set.of("J", "print", "__probe", "__isProxy", "__jtype", "__pause",
            "globalThis", "eval", "Function", "Continuation", "WeakRef", "FinalizationRegistry", "Packages", "java",
            "javax", "org", "com", "net", "edu", "JavaAdapter", "JavaImporter", "XML", "XMLList", "Namespace", "QName",
            "gc", "uneval", "Iterator", "StopIteration", "InternalError", "ConsString", "CallSite", "Script",
            "JavaException", "AggregateError");

    // ------------------------------------------------------------------------------- overlay
    static final List<String> TYPED_ARRAYS = List.of("Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array",
            "Uint16Array", "Int32Array", "Uint32Array", "Float16Array", "Float32Array", "Float64Array", "BigInt64Array",
            "BigUint64Array");
    static final List<String> ERRORS = List.of("Error", "EvalError", "RangeError", "ReferenceError", "SyntaxError",
            "TypeError", "URIError", "InternalError", "AggregateError");

    public static final Map<String, Type> GLOBAL_TYPES = table(
            "NaN", FLOAT, "Infinity", FLOAT, "undefined", UNDEFINED,
            "Math", Type.object("Math"), "JSON", Type.object("JSON"), "Reflect", Type.object("Reflect"));
    static final Map<String, String> INSTANCE_GROUP = new HashMap<>();
    static final Set<String> NOT_CONSTRUCTIBLE = Set.of("Symbol", "BigInt");
    static final Map<String, Type> INSTANCE_TYPE = Map.of("Continuation", Type.function("Continuation"));
    static final Map<String, Type> CALLS = new HashMap<>();
    public static final Map<String, Type> RETURNS = new HashMap<>();
    public static final Map<String, List<Type>> PARAMS = new HashMap<>();

    static {
        for (String ta : TYPED_ARRAYS) INSTANCE_GROUP.put(ta, "TypedArray");
        Object[] calls = {"String", STRING, "Number", NUMBER, "Boolean", BOOLEAN, "BigInt", BIGINT, "Symbol", SYMBOL,
                "Array", ARRAY, "Object", OBJECT, "RegExp", Type.object("RegExp"), "Date", STRING,
                "parseInt", NUMBER, "parseFloat", NUMBER, "isNaN", BOOLEAN, "isFinite", BOOLEAN,
                "encodeURI", STRING, "decodeURI", STRING, "encodeURIComponent", STRING, "decodeURIComponent", STRING,
                "escape", STRING, "unescape", STRING, "eval", ANY};
        for (int i = 0; i < calls.length; i += 2) CALLS.put((String) calls[i], (Type) calls[i + 1]);
        for (String e : ERRORS) CALLS.put(e, Type.object(e));

        Object[] returns = {
                "Array.push", INTEGER, "Array.pop", ANY, "Array.shift", ANY, "Array.unshift", INTEGER, "Array.map", ARRAY,
                "Array.filter", ARRAY, "Array.slice", ARRAY, "Array.concat", ARRAY, "Array.join", STRING, "Array.indexOf", INTEGER,
                "Array.includes", BOOLEAN, "Array.reverse", ARRAY, "Array.sort", ARRAY, "Array.flat", ARRAY, "Array.fill", ARRAY,
                "Array.splice", ARRAY, "Array.forEach", UNDEFINED, "Array.reduce", ANY, "Array.some", BOOLEAN, "Array.every", BOOLEAN,
                "Array.find", ANY, "Array.findIndex", INTEGER, "Array.keys", OBJECT, "Array.values", OBJECT, "Array.entries", OBJECT,
                "Array.at", ANY, "Array.toString", STRING, "Array.lastIndexOf", INTEGER,
                "String.charAt", STRING, "String.charCodeAt", NUMBER, "String.codePointAt", NUMBER, "String.concat", STRING,
                "String.indexOf", INTEGER, "String.lastIndexOf", INTEGER, "String.slice", STRING, "String.substring", STRING,
                "String.substr", STRING, "String.toUpperCase", STRING, "String.toLowerCase", STRING, "String.trim", STRING,
                "String.split", ARRAY, "String.replace", STRING, "String.replaceAll", STRING, "String.repeat", STRING,
                "String.padStart", STRING, "String.padEnd", STRING, "String.includes", BOOLEAN, "String.startsWith", BOOLEAN,
                "String.endsWith", BOOLEAN, "String.normalize", STRING, "String.at", STRING, "String.localeCompare", INTEGER,
                "String.match", ANY, "String.search", INTEGER, "String.toString", STRING, "String.valueOf", STRING,
                "Number.toString", STRING, "Number.toFixed", STRING, "Number.toPrecision", STRING, "Number.toExponential", STRING,
                "Number.valueOf", NUMBER,
                "Boolean.valueOf", BOOLEAN, "Boolean.toString", STRING,
                "Object.hasOwnProperty", BOOLEAN, "Object.toString", STRING, "Object.valueOf", ANY, "Object.isPrototypeOf", BOOLEAN,
                "Object.propertyIsEnumerable", BOOLEAN,
                "Map.get", ANY, "Map.set", Type.object("Map"), "Map.has", BOOLEAN, "Map.delete", BOOLEAN, "Map.clear", UNDEFINED,
                "Map.keys", OBJECT, "Map.values", OBJECT, "Map.entries", OBJECT, "Map.forEach", UNDEFINED,
                "Set.add", Type.object("Set"), "Set.has", BOOLEAN, "Set.delete", BOOLEAN, "Set.clear", UNDEFINED, "Set.values", OBJECT,
                "WeakMap.get", ANY, "WeakMap.set", Type.object("WeakMap"), "WeakMap.has", BOOLEAN, "WeakSet.add", Type.object("WeakSet"),
                "WeakSet.has", BOOLEAN,
                "RegExp.test", BOOLEAN, "RegExp.exec", ANY, "RegExp.toString", STRING,
                "Function.call", ANY, "Function.apply", ANY, "Function.bind", FUNCTION, "Function.toString", STRING,
                "Date.getTime", NUMBER, "Date.toISOString", STRING, "Date.valueOf", NUMBER, "Date.getFullYear", INTEGER,
                "TypedArray.subarray", Type.object("TypedArray"), "TypedArray.slice", Type.object("TypedArray"), "TypedArray.join", STRING,
                "TypedArray.indexOf", INTEGER, "TypedArray.includes", BOOLEAN, "TypedArray.fill", Type.object("TypedArray"),
                "TypedArray.set", UNDEFINED, "TypedArray.at", ANY,
                "ArrayBuffer.slice", Type.object("ArrayBuffer"),
                "Symbol.toString", STRING, "Symbol.valueOf", SYMBOL,
                "BigInt.toString", STRING, "BigInt.valueOf", BIGINT,
                "Error.toString", STRING,
                "Promise.then", Type.object("Promise"), "Promise.catch", Type.object("Promise"), "Promise.finally", Type.object("Promise"),
                "ArrayCtor.isArray", BOOLEAN, "ArrayCtor.from", ARRAY, "ArrayCtor.of", ARRAY,
                "ObjectCtor.keys", ARRAY, "ObjectCtor.values", ARRAY, "ObjectCtor.entries", ARRAY, "ObjectCtor.assign", OBJECT,
                "ObjectCtor.freeze", OBJECT, "ObjectCtor.seal", OBJECT, "ObjectCtor.preventExtensions", OBJECT,
                "ObjectCtor.isFrozen", BOOLEAN, "ObjectCtor.isSealed", BOOLEAN, "ObjectCtor.isExtensible", BOOLEAN,
                "ObjectCtor.getPrototypeOf", ANY, "ObjectCtor.setPrototypeOf", OBJECT, "ObjectCtor.create", OBJECT,
                "ObjectCtor.defineProperty", OBJECT, "ObjectCtor.getOwnPropertyNames", ARRAY, "ObjectCtor.getOwnPropertyDescriptor", ANY,
                "ObjectCtor.is", BOOLEAN, "ObjectCtor.fromEntries", OBJECT,
                "NumberCtor.isInteger", BOOLEAN, "NumberCtor.isSafeInteger", BOOLEAN, "NumberCtor.isNaN", BOOLEAN,
                "NumberCtor.isFinite", BOOLEAN, "NumberCtor.parseInt", NUMBER, "NumberCtor.parseFloat", NUMBER,
                "StringCtor.fromCharCode", STRING, "StringCtor.fromCodePoint", STRING, "StringCtor.raw", STRING,
                "SymbolCtor.for", SYMBOL, "SymbolCtor.keyFor", ANY,
                "BigIntCtor.asIntN", BIGINT, "BigIntCtor.asUintN", BIGINT,
                "JSON.stringify", ANY, "JSON.parse", ANY,
                "Reflect.get", ANY, "Reflect.set", BOOLEAN, "Reflect.has", BOOLEAN, "Reflect.ownKeys", ARRAY, "Reflect.apply", ANY,
                "Reflect.construct", OBJECT, "Reflect.getPrototypeOf", ANY, "Reflect.setPrototypeOf", BOOLEAN,
                "Reflect.defineProperty", BOOLEAN, "Reflect.deleteProperty", BOOLEAN, "Reflect.getOwnPropertyDescriptor", ANY,
                "DateCtor.now", NUMBER};
        for (int i = 0; i < returns.length; i += 2) RETURNS.put((String) returns[i], (Type) returns[i + 1]);
        for (String m : List.of("abs", "acos", "acosh", "asin", "asinh", "atan", "atanh", "atan2", "ceil", "cbrt", "expm1",
                "cos", "cosh", "exp", "floor", "fround", "hypot", "log", "log1p", "log2", "log10", "max", "min", "pow",
                "random", "round", "sign", "sin", "sinh", "sqrt", "tan", "tanh", "trunc"))
            RETURNS.put("Math." + m, NUMBER);
        RETURNS.put("Math.imul", INTEGER);
        RETURNS.put("Math.clz32", INTEGER);

        Object[] params = {
                "Array.push", List.of(ANY), "Array.map", List.of(FUNCTION), "Array.filter", List.of(FUNCTION), "Array.forEach", List.of(FUNCTION),
                "Array.reduce", List.of(FUNCTION, ANY), "Array.join", List.of(STRING), "Array.indexOf", List.of(ANY), "Array.slice", List.of(NUMBER, NUMBER),
                "Array.some", List.of(FUNCTION), "Array.every", List.of(FUNCTION), "Array.find", List.of(FUNCTION), "Array.sort", List.of(FUNCTION),
                "String.charAt", List.of(NUMBER), "String.charCodeAt", List.of(NUMBER), "String.codePointAt", List.of(NUMBER), "String.slice", List.of(NUMBER, NUMBER),
                "String.indexOf", List.of(STRING), "String.repeat", List.of(NUMBER), "String.split", List.of(STRING), "String.padStart", List.of(NUMBER, STRING),
                "Number.toString", List.of(NUMBER), "Number.toFixed", List.of(NUMBER), "Number.toPrecision", List.of(NUMBER),
                "Map.get", List.of(ANY), "Map.set", List.of(ANY, ANY), "Map.has", List.of(ANY), "Set.add", List.of(ANY), "Set.has", List.of(ANY),
                "Function.call", List.of(ANY), "Function.apply", List.of(ANY, ARRAY), "Function.bind", List.of(ANY),
                "ObjectCtor.keys", List.of(OBJECT), "ObjectCtor.values", List.of(OBJECT), "ObjectCtor.entries", List.of(OBJECT), "ObjectCtor.freeze", List.of(OBJECT),
                "ObjectCtor.seal", List.of(OBJECT), "ObjectCtor.preventExtensions", List.of(OBJECT), "ObjectCtor.getPrototypeOf", List.of(OBJECT),
                "ObjectCtor.setPrototypeOf", List.of(OBJECT, ANY), "ObjectCtor.is", List.of(ANY, ANY), "ObjectCtor.assign", List.of(OBJECT, ANY),
                "ObjectCtor.getOwnPropertyNames", List.of(OBJECT), "ObjectCtor.defineProperty", List.of(OBJECT, ANY, OBJECT),
                "JSON.stringify", List.of(ANY), "JSON.parse", List.of(STRING),
                "Reflect.get", List.of(OBJECT, ANY), "Reflect.set", List.of(OBJECT, ANY, ANY), "Reflect.has", List.of(OBJECT, ANY), "Reflect.ownKeys", List.of(OBJECT),
                "Reflect.apply", List.of(FUNCTION, ANY, ARRAY), "Reflect.construct", List.of(FUNCTION, ARRAY),
                "Math.imul", List.of(NUMBER, NUMBER), "Math.max", List.of(NUMBER, NUMBER), "Math.min", List.of(NUMBER, NUMBER), "Math.pow", List.of(NUMBER, NUMBER),
                "Math.floor", List.of(NUMBER), "Math.abs", List.of(NUMBER), "Math.sqrt", List.of(NUMBER), "Math.round", List.of(NUMBER), "Math.trunc", List.of(NUMBER),
                "StringCtor.fromCharCode", List.of(NUMBER), "NumberCtor.isInteger", List.of(ANY), "SymbolCtor.for", List.of(STRING),
                "BigIntCtor.asIntN", List.of(NUMBER, BIGINT), "BigIntCtor.asUintN", List.of(NUMBER, BIGINT)};
        for (int i = 0; i < params.length; i += 2) {
            @SuppressWarnings("unchecked") List<Type> ps = (List<Type>) params[i + 1];
            PARAMS.put((String) params[i], ps);
        }
    }

    private record JMethod(String name, int arity, List<Type> params, Type returns) {}

    /** The Java boundary (harness .../JavaProbe.java): methods of the {@code J} host object. */
    private static final List<JMethod> J_METHODS = List.of(
            new JMethod("takesInt", 1, List.of(NUMBER), INTEGER), new JMethod("takesLong", 1, List.of(NUMBER), NUMBER),
            new JMethod("takesShort", 1, List.of(NUMBER), INTEGER), new JMethod("takesByte", 1, List.of(NUMBER), INTEGER),
            new JMethod("takesChar", 1, List.of(STRING), STRING), new JMethod("takesFloat", 1, List.of(NUMBER), FLOAT),
            new JMethod("takesDouble", 1, List.of(NUMBER), FLOAT), new JMethod("takesBoolean", 1, List.of(BOOLEAN), BOOLEAN),
            new JMethod("takesString", 1, List.of(STRING), STRING), new JMethod("takesInteger", 1, List.of(NUMBER), INTEGER),
            new JMethod("takesNumber", 1, List.of(NUMBER), NUMBER), new JMethod("takesBigInteger", 1, List.of(BIGINT), BIGINT),
            new JMethod("takesCharSequence", 1, List.of(STRING), STRING),
            new JMethod("echo", 1, List.of(ANY), ANY), new JMethod("typeOf", 1, List.of(ANY), STRING), new JMethod("over", 1, List.of(ANY), STRING),
            new JMethod("anInt", 0, List.of(), INTEGER), new JMethod("aLong", 0, List.of(), NUMBER), new JMethod("maxLong", 0, List.of(), NUMBER),
            new JMethod("aFloat", 0, List.of(), FLOAT), new JMethod("aDouble", 0, List.of(), FLOAT), new JMethod("negZero", 0, List.of(), FLOAT),
            new JMethod("nan", 0, List.of(), FLOAT), new JMethod("aShort", 0, List.of(), INTEGER), new JMethod("aByte", 0, List.of(), INTEGER),
            new JMethod("aChar", 0, List.of(), STRING), new JMethod("boxedInt", 0, List.of(), INTEGER), new JMethod("boxedLong", 0, List.of(), NUMBER),
            new JMethod("bigInteger", 0, List.of(), BIGINT), new JMethod("aString", 0, List.of(), STRING), new JMethod("nothing", 0, List.of(), NULL),
            new JMethod("intArray", 1, List.of(NUMBER), Type.object("JavaArray")), new JMethod("doubleArray", 1, List.of(NUMBER), Type.object("JavaArray")),
            new JMethod("objectArray", 1, List.of(NUMBER), Type.object("JavaArray")), new JMethod("stringArray", 0, List.of(), Type.object("JavaArray")),
            new JMethod("list", 0, List.of(), Type.object("JavaList")), new JMethod("map", 0, List.of(), Type.object("JavaMap")),
            new JMethod("sum", 1, List.of(ANY), NUMBER), new JMethod("size", 1, List.of(OBJECT), INTEGER), new JMethod("get", 2, List.of(OBJECT, ANY), ANY),
            new JMethod("bump", 0, List.of(), INTEGER));
    private static final List<Map.Entry<String, Type>> J_PROPERTIES = List.of(
            Map.entry("field", INTEGER), Map.entry("PI_ISH", FLOAT), Map.entry("staticCounter", INTEGER));
    /** Function.prototype: excluded from the API generators, but calls, apply and bind are language-level. */
    private static final List<JMethod> FUNCTION_METHODS = List.of(
            new JMethod("call", 1, List.of(ANY), ANY), new JMethod("apply", 2, List.of(ANY, ARRAY), ANY),
            new JMethod("bind", 1, List.of(ANY), FUNCTION), new JMethod("toString", 0, List.of(), STRING));
    public static final Set<String> HAND_WRITTEN_RECEIVERS = Set.of("J", "Function");

    private static List<Builtin> hostBuiltins() {
        Set<Key> jProps = new LinkedHashSet<>();
        for (var e : J_PROPERTIES) jProps.add(new Key(e.getKey()));
        return List.of(
                new Builtin("J", Type.object("J", jProps), null, null, null, "J"),
                new Builtin("__pause", FUNCTION, null, ANY, new Signature(List.of(new Param(ANY)), ANY), null),
                new Builtin("Continuation", Type.function("Continuation" + CTOR), INSTANCE_TYPE.get("Continuation"), null, null, "Continuation"),
                new Builtin("eval", FUNCTION, null, ANY, new Signature(List.of(new Param(STRING)), ANY), null),
                new Builtin("Function", FUNCTION, FUNCTION, FUNCTION, null, "Function"),
                new Builtin("globalThis", OBJECT, null, null, null, "GlobalThis"));
    }

    // ------------------------------------------------------------------------------- naming
    private static final Map<String, String> ATTR_OVERRIDES = Map.of("isNaN", "IS_NAN", "NaN", "NAN", "isFinite", "IS_FINITE",
            "isXMLName", "IS_XML_NAME", "escape", "ESCAPE", "unescape", "UNESCAPE", "encodeURI", "ENCODE_URI",
            "decodeURI", "DECODE_URI", "encodeURIComponent", "ENCODE_URI_COMPONENT", "decodeURIComponent", "DECODE_URI_COMPONENT");

    private static String snake(String name) {
        String s = name.replaceAll("(.)([A-Z][a-z]+)", "$1_$2");
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return s.replace("__", "_").toUpperCase();
    }

    /** The registry (attribute) name of a JavaScript name: {@code isNaN -> IS_NAN}, {@code BigInt64Array -> BIG_INT64_ARRAY}. */
    public static String attrName(String name) {
        String o = ATTR_OVERRIDES.get(name);
        if (o != null) return o;
        return strip(snake(name), '_');
    }

    private static String strip(String s, char c) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == c) a++;
        while (b > a && s.charAt(b - 1) == c) b--;
        return s.substring(a, b);
    }

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    static boolean isIdentifier(String name) { return IDENT.matcher(name).matches(); }

    // ------------------------------------------------------------------------------- the table
    public static final Map<String, Builtin> BUILTINS;          // by attribute name
    public static final Map<String, Key> KEYS;                  // by attribute name
    public static final Map<String, Method> METHODS;            // by attribute name, e.g. ARRAY_PUSH
    public static final Map<String, List<Method>> METHODS_BY_GROUP;
    public static final Map<String, SortedSet<Key>> PROPS_BY_GROUP;
    public static final String PROBED_RHINO;
    public static final Set<String> SYNTAX_SUPPORTED;
    public static final JsonObject TABLE;

    static final Set<String> WELL_KNOWN_NAMES = Set.of("constructor", "toString", "valueOf", "prototype", "name",
            "message", "hasOwnProperty", "toJSON", "then", "next", "done", "value", "get", "set", "has");
    public static final List<Key> INTERESTING_KEYS;

    static {
        List<Key> ks = new ArrayList<>();
        for (String n : List.of("a", "b", "c", "d", "e", "f", "x", "y", "m", "n", "g", "foo", "!")) ks.add(new Key(n));
        for (String n : List.of("0", "1", "2", "10", "42")) ks.add(new Key(n, KeyKind.INDEX));
        ks.add(new Key("length", KeyKind.LENGTH));
        ks.add(new Key("__proto__", KeyKind.PROTO));
        for (String n : new TreeSet<>(WELL_KNOWN_NAMES)) ks.add(new Key(n, KeyKind.WELL_KNOWN));
        INTERESTING_KEYS = List.copyOf(ks);

        TABLE = loadTable();
        Map<String, Builtin> builtins = new LinkedHashMap<>();
        JsonObject globals = TABLE.getAsJsonObject("globals");
        JsonObject ctors = TABLE.getAsJsonObject("constructors");
        for (var e : globals.entrySet()) {
            String name = e.getKey();
            if (EXCLUDED_GLOBALS.contains(name) || !isIdentifier(name)) continue;
            JsonObject info = ctors.has(name) ? ctors.getAsJsonObject(name) : new JsonObject();
            Builtin b = builtinFromTable(name, info, globals);
            if (b != null) builtins.put(attrName(name), b);
        }
        for (Builtin b : hostBuiltins()) builtins.put(attrName(b.name()), b);

        Map<String, Method> methods = new LinkedHashMap<>();
        Map<String, List<Method>> byGroup = new LinkedHashMap<>();
        Map<String, SortedSet<Key>> propsByGroup = new HashMap<>();
        Map<String, Key> keys = new LinkedHashMap<>();

        JsonObject protos = TABLE.has("protos") ? TABLE.getAsJsonObject("protos") : new JsonObject();
        for (var e : protos.entrySet()) {
            String group = e.getKey();
            if (EXCLUDED_GLOBALS.contains(group) || !isIdentifier(group)) continue;
            for (JsonElement entry : e.getValue().getAsJsonArray())
                addMember(group, entry.getAsString(), methods, byGroup, propsByGroup, keys);
        }
        JsonObject statics = TABLE.has("statics") ? TABLE.getAsJsonObject("statics") : new JsonObject();
        for (var e : statics.entrySet()) {
            String owner = e.getKey();
            if (EXCLUDED_GLOBALS.contains(owner) || !isIdentifier(owner)) continue;
            String receiver = GLOBAL_TYPES.containsKey(owner) ? owner : owner + CTOR;
            for (JsonElement entry : e.getValue().getAsJsonArray())
                addMember(receiver, entry.getAsString(), methods, byGroup, propsByGroup, keys);
        }
        for (JMethod j : J_METHODS) {
            Method m = new Method("J", j.name, j.arity, j.params, j.returns);
            methods.put("J_" + attrName(j.name), m);
            byGroup.computeIfAbsent("J", g -> new ArrayList<>()).add(m);
        }
        for (JMethod j : FUNCTION_METHODS) {
            Method m = new Method("Function", j.name, j.arity, j.params, j.returns);
            methods.put("FUNCTION_" + attrName(j.name), m);
            byGroup.computeIfAbsent("Function", g -> new ArrayList<>()).add(m);
            keys.putIfAbsent(j.name, key(j.name));
        }
        for (var p : J_PROPERTIES) propsByGroup.computeIfAbsent("J", g -> new TreeSet<>()).add(key(p.getKey()));
        for (Key k : INTERESTING_KEYS) keys.putIfAbsent(k.name(), k);
        Map<String, Key> keyNs = new LinkedHashMap<>();
        for (Key k : keys.values()) keyNs.put(keyAttr(k), k);

        BUILTINS = Collections.unmodifiableMap(builtins);
        KEYS = Collections.unmodifiableMap(keyNs);
        METHODS = Collections.unmodifiableMap(methods);
        Map<String, List<Method>> bg = new LinkedHashMap<>();
        byGroup.forEach((g, ms) -> bg.put(g, List.copyOf(ms)));
        METHODS_BY_GROUP = Collections.unmodifiableMap(bg);
        Map<String, SortedSet<Key>> pg = new HashMap<>();
        propsByGroup.forEach((g, ps) -> pg.put(g, Collections.unmodifiableSortedSet(ps)));
        PROPS_BY_GROUP = Collections.unmodifiableMap(pg);
        PROBED_RHINO = TABLE.has("rhino") && !TABLE.get("rhino").isJsonNull() ? TABLE.get("rhino").getAsString() : "";
        Set<String> syntax = new TreeSet<>();
        if (TABLE.has("syntax"))
            for (var e : TABLE.getAsJsonObject("syntax").entrySet()) {
                JsonObject v = e.getValue().getAsJsonObject();
                if (v.has("supported") && v.get("supported").getAsBoolean()) syntax.add(e.getKey());
            }
        SYNTAX_SUPPORTED = Collections.unmodifiableSet(syntax);
    }

    private static JsonObject loadTable() {
        String override = System.getProperty("oxpecker.capabilities");
        try {
            if (override != null) {
                try (Reader r = Files.newBufferedReader(Path.of(override), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(r).getAsJsonObject();
                }
            }
            try (InputStream in = Env.class.getResourceAsStream("rhino-capabilities.json")) {
                if (in == null) throw new IllegalStateException("capability table missing from the classpath; run `oxpecker probe`");
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the capability table", e);
        }
    }

    private static Builtin builtinFromTable(String name, JsonObject info, JsonObject globals) {
        if (!isIdentifier(name)) return null;
        Type gt = GLOBAL_TYPES.get(name);
        if (gt != null) return new Builtin(name, gt, null, null, null, gt.group());
        String kind = globals.has(name) ? globals.get(name).getAsString() : null;
        if (info.has("isCtor") && info.get("isCtor").getAsBoolean()) {
            String group = INSTANCE_GROUP.getOrDefault(name, name);
            return new Builtin(name, Type.function(name + CTOR),
                    NOT_CONSTRUCTIBLE.contains(name) ? null : Type.object(group), CALLS.get(name), null, group);
        }
        if ("function".equals(kind))
            return new Builtin(name, Type.function(name + CTOR), null, CALLS.getOrDefault(name, ANY), null, null);
        return new Builtin(name, Type.object(name), null, null, null, name);
    }

    private static void addMember(String receiver, String entry, Map<String, Method> methods,
                                  Map<String, List<Method>> byGroup, Map<String, SortedSet<Key>> propsByGroup,
                                  Map<String, Key> keys) {
        // 'push/1' -> method push of arity 1; 'size:acc' -> accessor; 'x=' -> data property
        if (entry.startsWith("Symbol(")) return;
        String what, name;
        int arity = 0;
        int slash = entry.lastIndexOf('/');
        if (slash >= 0) {
            what = "method";
            name = entry.substring(0, slash);
            arity = Integer.parseInt(entry.substring(slash + 1));
        } else if (entry.endsWith(":acc")) {
            what = "accessor";
            name = entry.substring(0, entry.length() - 4);
        } else if (entry.endsWith("=")) {
            what = "data";
            name = entry.substring(0, entry.length() - 1);
        } else {
            return;
        }
        if (!isIdentifier(name)) return;
        if (what.equals("method")) {
            String full = receiver + "." + name;
            Method m = new Method(receiver, name, arity, PARAMS.getOrDefault(full, List.of()), RETURNS.getOrDefault(full, ANY));
            methods.put(attrName(receiver) + "_" + attrName(name), m);
            byGroup.computeIfAbsent(receiver, g -> new ArrayList<>()).add(m);
        } else {
            propsByGroup.computeIfAbsent(receiver, g -> new TreeSet<>()).add(key(name));
        }
        keys.putIfAbsent(name, key(name));
    }

    static Key key(String name) {
        if (name.equals("length")) return new Key(name, KeyKind.LENGTH);
        if (name.equals("__proto__")) return new Key(name, KeyKind.PROTO);
        if (Key.isDigits(name)) return new Key(name, KeyKind.INDEX);
        if (WELL_KNOWN_NAMES.contains(name)) return new Key(name, KeyKind.WELL_KNOWN);
        return new Key(name, KeyKind.ORDINARY);
    }

    static String keyAttr(Key k) {
        if (k.kind() == KeyKind.INDEX) return "INDEX_" + k.name();
        if (k.name().equals("!")) return "BANG";
        return isIdentifier(k.name()) ? attrName(k.name()) : "KEY_" + k.name().replaceAll("\\W", "_").toUpperCase();
    }

    // ------------------------------------------------------------------------------- lookups
    private static <T> T need(Map<String, T> m, String attr, String what) {
        T v = m.get(attr);
        if (v == null) throw new IllegalArgumentException("no " + what + " " + attr + " in the registry");
        return v;
    }

    /** A builtin by registry name ({@code "ARRAY"}, {@code "MATH"}, {@code "IS_NAN"}). */
    public static Builtin builtin(String attr) { return need(BUILTINS, attr, "builtin"); }

    /** A builtin by JavaScript name ({@code "Array"}): Python's {@code builtin_named}. */
    public static Builtin builtinNamed(String jsName) {
        Builtin b = BUILTINS.get(attrName(jsName));
        if (b == null) throw new IllegalArgumentException(jsName);
        return b;
    }


    /** A key by registry name: {@code Env.k("LENGTH")}. */
    public static Key k(String attr) { return need(KEYS, attr, "key"); }

    /** A method by registry name ({@code "ARRAY_PUSH"}, {@code "MATH_IMUL"}, {@code "J_ECHO"}). */
    public static Method method(String attr) { return need(METHODS, attr, "method"); }

    public static List<Method> methodsOf(String group) { return METHODS_BY_GROUP.getOrDefault(group == null ? "" : group, List.of()); }

    public static SortedSet<Key> propsOf(String group) {
        SortedSet<Key> s = PROPS_BY_GROUP.get(group == null ? "" : group);
        return s != null ? s : Collections.emptySortedSet();
    }

    // ------------------------------------------------------------------------------- pools (simplest first)
    public static final List<Long> INTERESTING_INTEGERS = List.of(
            0L, 1L, 2L, 3L, 4L, 5L, 7L, 8L, 10L, 16L, 64L, 127L, 128L, 255L, 256L, 1000L, 1024L, 4096L, 65535L, 65536L,
            1L << 30, (1L << 31) - 1, 1L << 31, (1L << 32) - 1, 1L << 32, (1L << 53) - 1, 1L << 53,
            0x3FFFFFFFL, 0x40000000L, 0x7FFFFFFEL, 0xBFFFFFFFL, 0xC0000000L, 0xFFFFFFFEL);
    public static final List<Double> INTERESTING_FLOATS = List.of(
            0.0, 0.5, 1.0, 1.5, 0.1, 2.0, 3.0, 1e3, 1e6, 1e9, 1e12, 1e21, 1e-15, 5e-324, 2.2250738585072014e-308,
            1.7976931348623157e308, 4503599627370496.0, 9007199254740992.0);
    public static final List<String> INTERESTING_STRINGS = List.of("", "a", "0", "1", "-0", "10", "abc", "1e3", " 1", "x",
            "length", "undefined", "NaN", "true", "é", "😀", "\ud800", "\n", "\\", "\"", "'");
    public static final List<String> JS_TYPE_NAMES = List.of("undefined", "boolean", "number", "string", "symbol",
            "function", "object", "bigint");
    public static final List<Key> ORDINARY_KEYS = INTERESTING_KEYS.stream().filter(k -> k.kind() == KeyKind.ORDINARY).toList();
    public static final List<Key> INDEX_KEYS = INTERESTING_KEYS.stream().filter(k -> k.kind() == KeyKind.INDEX).toList();

    /** (pattern, flags the pattern is incompatible with): from Fuzzilli's interestingRegExps, the part Rhino parses. */
    public record RegExpPattern(String pattern, Flags incompatible) {}

    public static final List<RegExpPattern> REGEXP_PATTERNS;
    static {
        List<RegExpPattern> rs = new ArrayList<>();
        for (String p : List.of("a", "ab", "a|b", "a*", "a+", "a?", "a{2}", "a{1,3}", "[abc]", "[^abc]", "[a-z]",
                "\\d+", "\\w*", "\\s", ".", "^a", "a$", "(a)(b)?", "(?:ab)+", "(a)\\1", "(?<n>a)", "(?=a)", "(?!a)",
                "(?<=a)b", "(?<!a)b", "\\b", "a*?", "[\\u0041-\\u005A]"))
            rs.add(new RegExpPattern(p, RegExpFlag.NONE));
        rs.add(new RegExpPattern("\\u{1F600}", RegExpFlag.of(RegExpFlag.IGNORE_CASE)));
        REGEXP_PATTERNS = List.copyOf(rs);
    }
    public static final List<String> REGEXP_QUANTIFIERS = List.of("*", "+", "?");

    public static final List<Builtin> TYPED_ARRAY_CONSTRUCTORS = TYPED_ARRAYS.stream()
            .filter(n -> BUILTINS.containsKey(attrName(n))).map(n -> BUILTINS.get(attrName(n))).toList();
    public static final List<Builtin> ERROR_CONSTRUCTORS = ERRORS.stream()
            .filter(n -> BUILTINS.containsKey(attrName(n))).map(n -> BUILTINS.get(attrName(n))).toList();
    public static final List<Builtin> BUILTIN_POOL = BUILTINS.values().stream()
            .filter(b -> !List.of("J", "__pause", "Continuation").contains(b.name()))
            .sorted(java.util.Comparator.comparing(Builtin::name)).toList();

    public static Set<String> overlayKeys() {
        Set<String> out = new TreeSet<>();
        for (String k : RETURNS.keySet()) if (!HAND_WRITTEN_RECEIVERS.contains(k.split("\\.")[0])) out.add(k);
        for (String k : PARAMS.keySet()) if (!HAND_WRITTEN_RECEIVERS.contains(k.split("\\.")[0])) out.add(k);
        return out;
    }

    public static Set<String> tableMethodKeys() {
        Set<String> out = new TreeSet<>();
        for (Method m : METHODS.values()) if (!HAND_WRITTEN_RECEIVERS.contains(m.receiver())) out.add(m.receiver() + "." + m.name());
        return out;
    }

    private static Map<String, Type> table(Object... kv) {
        Map<String, Type> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Type) kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }
}
