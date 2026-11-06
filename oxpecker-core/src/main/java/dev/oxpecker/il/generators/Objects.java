// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/CodeGen/CodeGenerators.swift at commit be2811d00282320381fc8007d64873745772011d
// (object literals and members 861-1078, property/element/computed operations 1938-2170,
// destructuring 2493-2578, super 2585-2707, prototype/callback/proxy/hierarchy 3141-3251, 3274-3290,
// 3508-3556, 3837). Weights from CodeGeneratorWeights.swift.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il.generators;

import static dev.oxpecker.il.Strategies.assignable;
import static dev.oxpecker.il.Strategies.generator;
import static dev.oxpecker.il.Strategies.nested;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.statements;
import static dev.oxpecker.il.Strategies.syntaxOk;
import static dev.oxpecker.il.Strategies.variable;
import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.NULLISH;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.il.BinaryOperator;
import dev.oxpecker.il.Builder;
import dev.oxpecker.il.Context;
import dev.oxpecker.il.DeclarationKind;
import dev.oxpecker.il.Env;
import dev.oxpecker.il.Flags;
import dev.oxpecker.il.Key;
import dev.oxpecker.il.Method;
import dev.oxpecker.il.Parameters;
import dev.oxpecker.il.Patterns.ArrayElement;
import dev.oxpecker.il.Patterns.ArrayPattern;
import dev.oxpecker.il.Patterns.Binding;
import dev.oxpecker.il.Patterns.ObjectPattern;
import dev.oxpecker.il.Patterns.ObjectProperty;
import dev.oxpecker.il.PropertyFlag;
import dev.oxpecker.il.PropertyType;
import dev.oxpecker.il.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Objects (objects.py): literals and their members, properties, elements, computed properties,
 * destructuring, prototypes, proxies, and {@code super} inside object-literal methods
 * (self-scaffolding).
 */
public final class Objects {
    private Objects() {}

    static final List<Key> TRAP_KEYS = List.of(Env.k("GET"), Env.k("SET"), Env.k("HAS"), new Key("deleteProperty"),
            new Key("ownKeys"), new Key("getOwnPropertyDescriptor"), new Key("defineProperty"), new Key("getPrototypeOf"),
            new Key("setPrototypeOf"), new Key("apply"), new Key("construct"), new Key("isExtensible"),
            new Key("preventExtensions"));
    static final List<BinaryOperator> BINARY_OPERATORS = List.of(BinaryOperator.values());
    static final List<PropertyType> PROPERTY_TYPES = List.of(PropertyType.values());
    static final List<DeclarationKind> DECLARATION_KINDS = List.of(DeclarationKind.values());
    /** {@code tuple(PropertyFlag(n) for n in range(8))}. */
    static final List<Flags> PROPERTY_FLAG_SETS;

    static {
        List<Flags> sets = new ArrayList<>();
        for (int n = 0; n < 8; n++) sets.add(PropertyFlag.of(n));
        PROPERTY_FLAG_SETS = List.copyOf(sets);
    }

    /** {@code KEY_POOL = st.sampled_from(INTERESTING_KEYS)}. */
    static Key keyPool(Draw d) { return d.sampled(Env.INTERESTING_KEYS); }

    /** {@code INDEX_POOL = st.integers(-2, 10)}. */
    static long indexPool(Draw d) { return d.ints(-2, 10); }

    /**
     * The object's known keys sorted by name. Python sorts by name only, so two keys of one name
     * and different kinds keep the order of a frozenset (latent nondeterminism); here the tie
     * breaks by kind.
     */
    static List<Key> knownKeys(Builder b, int obj) {
        Type t = b.typeOf(obj);
        TreeSet<Key> all = new TreeSet<>(t.props());
        all.addAll(Env.propsOf(t.group()));
        return new ArrayList<>(all);
    }

    /** An existing key of the object with a drawn weight, else an interesting key. */
    static Key aKey(Draw d, Builder b, int obj) {
        return d.draw(dd -> {
            List<Key> keys = knownKeys(b, obj);
            boolean useKnown = dd.maybe(0.5);
            if (!keys.isEmpty() && useKnown) return pick(dd, keys);
            return keyPool(dd);
        });
    }

    /** Ordinary keys not yet in the literal, else all of them. */
    private static List<Key> freshKeys(Builder.ObjectLiteralFrame frame) {
        List<Key> candidates = new ArrayList<>();
        for (Key k : Env.ORDINARY_KEYS) if (!frame.keys.contains(k)) candidates.add(k);
        return candidates.isEmpty() ? Env.ORDINARY_KEYS : candidates;
    }

    static void register() {
        // ------------------------------------------------------------------ object literals and members
        generator("object_literal").family("objects").weight(10).block().produces(OBJECT).source("CodeGenerators.swift:861")
                .doc("An object literal whose members come from the member pool.")
                .register((d, b, in) -> {
                    var o = b.objectLiteral();
                    try (o) {
                        statements(d, b, 0, 5);
                    }
                    return o.result();
                });

        generator("literal_property").family("members").weight(20).context(Context.OBJECT_LITERAL).source("CodeGenerators.swift:876")
                .doc("`key: value` with a key not yet in the literal.")
                .register((d, b, in) -> {
                    var frame = b.currentLiteral();
                    List<Key> candidates = freshKeys(frame);
                    Key key = pick(d, candidates);
                    frame.addProperty(key, variable(d, b));
                    return null;
                });

        generator("literal_element").family("members").weight(5).context(Context.OBJECT_LITERAL).source("CodeGenerators.swift:887")
                .doc("`0: value`.")
                .register((d, b, in) -> {
                    var frame = b.currentLiteral();
                    long index = d.ints(0, 10);
                    while (frame.elements.contains(index)) index += 1;
                    frame.addElement(index, variable(d, b));
                    return null;
                });

        generator("literal_method").family("members").weight(5).context(Context.OBJECT_LITERAL).block().source("CodeGenerators.swift:925")
                .doc("`key(params) {...}`, sometimes a generator method.")
                .register((d, b, in) -> {
                    var frame = b.currentLiteral();
                    List<Key> candidates = freshKeys(frame);
                    boolean generatorMethod = d.maybe(0.1) && b.options.toggles().contains("generators");
                    Key key = pick(d, candidates);
                    Parameters params = new Parameters(d.ints(0, 2));
                    try (var m = frame.method(key, params, Builder.fn().generator(generatorMethod))) {
                        nested(d, b);
                        if (!b.visible().isEmpty() && d.maybe(0.8)) b.doReturn(pick(d, b.visible()));
                    }
                    return null;
                });

        generator("literal_getter").family("members").weight(3).context(Context.OBJECT_LITERAL).block().source("CodeGenerators.swift:994")
                .doc("`get key() { ...; return v }`.")
                .register((d, b, in) -> {
                    var frame = b.currentLiteral();
                    List<Key> candidates = freshKeys(frame);
                    try (var g = frame.getter(pick(d, candidates))) {
                        nested(d, b);
                        b.doReturn(pick(d, b.visible()));
                    }
                    return null;
                });

        generator("literal_setter").family("members").weight(3).context(Context.OBJECT_LITERAL).block().source("CodeGenerators.swift:1018")
                .doc("`set key(v) {...}`.")
                .register((d, b, in) -> {
                    var frame = b.currentLiteral();
                    List<Key> candidates = freshKeys(frame);
                    try (var s = frame.setter(pick(d, candidates))) {
                        nested(d, b);
                    }
                    return null;
                });

        generator("literal_prototype").family("members").weight(5).context(Context.OBJECT_LITERAL).required(OBJECT)
                .source("CodeGenerators.swift:917")
                .doc("`__proto__: obj`.")
                .register((d, b, in) -> {
                    var frame = b.currentLiteral();
                    if (!frame.hasPrototype()) frame.setPrototype(in[0]);
                    return null;
                });

        // ------------------------------------------------------------------ properties
        generator("property_retrieval").family("properties").weight(20).inputs(OBJECT).source("CodeGenerators.swift:1938")
                .doc("`obj.key`, with `?.` when the receiver may be nullish.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    boolean optional = d.maybe(0.5) && b.typeOf(obj).mayBe(NULLISH);
                    return b.getProperty(obj, aKey(d, b, obj), optional);
                });

        generator("property_assignment").family("properties").weight(20).inputs(OBJECT).source("CodeGenerators.swift:1961")
                .doc("`obj.key = value`.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    Key key = aKey(d, b, obj);
                    b.setProperty(obj, key, variable(d, b));
                    return null;
                });

        generator("property_update").family("properties").weight(10).inputs(OBJECT).source("CodeGenerators.swift:1983")
                .doc("`obj.key op= value`.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    Key key = aKey(d, b, obj);
                    int value = variable(d, b, NUMBER);
                    b.updateProperty(obj, key, value, d.sampled(BINARY_OPERATORS));
                    return null;
                });

        generator("property_removal").family("properties").weight(5).inputs(OBJECT).source("CodeGenerators.swift:1999")
                .doc("`delete obj.key`.")
                .register((d, b, in) -> b.deleteProperty(in[0], aKey(d, b, in[0])));

        generator("property_configuration").family("properties").weight(10).inputs(OBJECT).source("CodeGenerators.swift:2007")
                .doc("`Object.defineProperty(obj, key, {...})` as a value, a getter, a setter or both.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    Key key = aKey(d, b, obj);
                    Flags flags = d.sampled(PROPERTY_FLAG_SETS);
                    PropertyType kind = d.sampled(PROPERTY_TYPES);
                    if (kind == PropertyType.VALUE) {
                        b.configureProperty(obj, key, flags, kind, variable(d, b));
                        return null;
                    }
                    List<Integer> fns = b.pool(FUNCTION).exact();
                    if (fns.isEmpty()) return null;
                    flags = flags.with(flags.bits() & ~PropertyFlag.WRITABLE);
                    if (kind == PropertyType.GETTER_SETTER) {
                        int getter = pick(d, fns);
                        int setter = pick(d, fns);
                        b.configureProperty(obj, key, flags, kind, getter, setter);
                    } else {
                        b.configureProperty(obj, key, flags, kind, pick(d, fns));
                    }
                    return null;
                });

        generator("element_retrieval").family("properties").weight(20).inputs(OBJECT).source("CodeGenerators.swift:2051")
                .doc("`obj[i]`.")
                .register((d, b, in) -> b.getElement(in[0], indexPool(d)));

        generator("element_assignment").family("properties").weight(20).inputs(OBJECT).source("CodeGenerators.swift:2058")
                .doc("`obj[i] = value`.")
                .register((d, b, in) -> {
                    long index = indexPool(d);
                    b.setElement(in[0], index, variable(d, b));
                    return null;
                });

        generator("element_update").family("properties").weight(7).inputs(OBJECT).source("CodeGenerators.swift:2066")
                .doc("`obj[i] op= value`.")
                .register((d, b, in) -> {
                    long index = indexPool(d);
                    int value = variable(d, b, NUMBER);
                    b.updateElement(in[0], index, value, d.sampled(BINARY_OPERATORS));
                    return null;
                });

        generator("element_removal").family("properties").weight(5).inputs(OBJECT).source("CodeGenerators.swift:2078")
                .doc("`delete obj[i]`.")
                .register((d, b, in) -> b.deleteElement(in[0], indexPool(d)));

        generator("computed_property_retrieval").family("properties").weight(20).inputs(OBJECT, ANY).source("CodeGenerators.swift:2120")
                .doc("`obj[key]`.")
                .register((d, b, in) -> b.getComputed(in[0], in[1]));

        generator("computed_property_assignment").family("properties").weight(20).inputs(OBJECT, ANY, ANY).source("CodeGenerators.swift:2128")
                .doc("`obj[key] = value`.")
                .register((d, b, in) -> {
                    b.setComputed(in[0], in[1], in[2]);
                    return null;
                });

        generator("computed_property_update").family("properties").weight(7).inputs(OBJECT, ANY).source("CodeGenerators.swift:2137")
                .doc("`obj[key] op= value` (the compound and logical assignments on an element: a recorded Rhino bug area).")
                .register((d, b, in) -> {
                    int value = variable(d, b, NUMBER);
                    b.updateComputed(in[0], in[1], value, d.sampled(BINARY_OPERATORS));
                    return null;
                });

        generator("computed_property_removal").family("properties").weight(5).inputs(OBJECT, ANY).source("CodeGenerators.swift:2150")
                .doc("`delete obj[key]`.")
                .register((d, b, in) -> b.deleteComputed(in[0], in[1]));

        generator("prototype_access").family("properties").weight(10).inputs(OBJECT).source("CodeGenerators.swift:3159")
                .doc("`obj.__proto__`.")
                .register((d, b, in) -> b.getProperty(in[0], Env.k("PROTO")));

        generator("prototype_overwrite").family("properties").weight(10).inputs(OBJECT, OBJECT).source("CodeGenerators.swift:3164")
                .doc("`obj.__proto__ = proto` or `Object.setPrototypeOf(obj, proto)`.")
                .register((d, b, in) -> {
                    int obj = in[0], proto = in[1];
                    if (obj == proto) proto = b.construct(b.builtin(Env.builtin("OBJECT")));
                    if (d.maybe(0.5)) {
                        b.setProperty(obj, Env.k("PROTO"), proto);
                    } else {
                        b.callMethod(b.builtin(Env.builtin("OBJECT")), Env.method("OBJECT_CTOR_SET_PROTOTYPE_OF"), List.of(obj, proto));
                    }
                    return null;
                });

        List<Key> callbackKeys = List.of(Env.k("VALUE_OF"), Env.k("TO_STRING"));
        generator("callback_property").family("properties").weight(10).inputs(OBJECT, FUNCTION).source("CodeGenerators.swift:3171")
                .doc("`obj.valueOf = fn` or `obj.toString = fn`.")
                .register((d, b, in) -> {
                    b.setProperty(in[0], d.sampled(callbackKeys), in[1]);
                    return null;
                });

        List<Long> lengths = List.of(0L, 1L, 2L, 3L, 10L, 100L, (1L << 32) - 2);
        generator("length_change").family("properties").weight(5).inputs(OBJECT).source("CodeGenerators.swift:3274")
                .doc("`obj.length = n`.")
                .register((d, b, in) -> {
                    b.setProperty(in[0], Env.k("LENGTH"), b.loadInt(d.sampled(lengths)));
                    return null;
                });

        List<Method> integrity = List.of(Env.method("OBJECT_CTOR_FREEZE"), Env.method("OBJECT_CTOR_SEAL"),
                Env.method("OBJECT_CTOR_PREVENT_EXTENSIONS"));
        generator("object_integrity_level").family("properties").weight(1).inputs(OBJECT).source("CodeGenerators.swift:386")
                .doc("`Object.freeze / seal / preventExtensions (obj)`.")
                .register((d, b, in) -> {
                    Method m = d.sampled(integrity);
                    b.callMethod(b.builtin(Env.builtin("OBJECT")), m, List.of(in[0]));
                    return null;
                });

        List<Method> statics = List.of(Env.method("OBJECT_CTOR_KEYS"), Env.method("OBJECT_CTOR_VALUES"),
                Env.method("OBJECT_CTOR_ENTRIES"), Env.method("OBJECT_CTOR_GET_OWN_PROPERTY_NAMES"));
        generator("object_static").family("properties").weight(4).inputs(OBJECT)
                .doc("`Object.keys/values/entries/getOwnPropertyNames(obj)` or a JSON round trip.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    int kind = d.ints(0, 4);
                    int o = b.builtin(Env.builtin("OBJECT"));
                    if (kind == 4) {
                        int json = b.builtin(Env.builtin("JSON"));
                        return b.callMethod(json, Env.method("JSON_PARSE"),
                                List.of(b.callMethod(json, Env.method("JSON_STRINGIFY"), List.of(obj))));
                    }
                    return b.callMethod(o, statics.get(kind), List.of(obj));
                });

        // ------------------------------------------------------------------ destructuring
        generator("destruct_array").family("objects").weight(5).inputs(ARRAY).toggle("destructuring").source("CodeGenerators.swift:2493")
                .doc("`var [a, , b, ...rest] = arr`.")
                .register((d, b, in) -> {
                    int n = d.ints(1, 4);
                    List<ArrayElement> elements = new ArrayList<>();
                    for (int i = 0; i < n; i++) elements.add(new ArrayElement(d.maybe(0.7) ? new Binding() : null));
                    Binding rest = syntaxOk("array-rest") && d.maybe(0.3) ? new Binding() : null;
                    b.destruct(in[0], new ArrayPattern(elements, rest), d.sampled(DECLARATION_KINDS));
                    return null;
                });

        generator("destruct_object").family("objects").weight(5).inputs(OBJECT).toggle("destructuring").source("CodeGenerators.swift:2534")
                .doc("`var {a, b, ...rest} = obj`.")
                .register((d, b, in) -> {
                    int obj = in[0];
                    List<Key> keys = d.uniqueLists(1, 3, dd -> aKey(dd, b, obj));
                    List<ObjectProperty> props = new ArrayList<>();
                    for (Key k : keys) props.add(new ObjectProperty(k, new Binding()));
                    b.destruct(obj, new ObjectPattern(props, syntaxOk("object-rest") && d.maybe(0.3)));
                    return null;
                });

        generator("destruct_array_reassign").family("objects").weight(5).inputs(ARRAY).toggle("destructuring").source("CodeGenerators.swift:2513")
                .doc("`[a, b] = arr` into existing assignable variables.")
                .register((d, b, in) -> {
                    List<Integer> targets = d.lists(1, 3, dd -> assignable(dd, b));
                    List<ArrayElement> elements = new ArrayList<>();
                    for (int i = 0; i < targets.size(); i++) elements.add(new ArrayElement(new Binding()));
                    b.destructAndReassign(in[0], new ArrayPattern(elements), targets);
                    return null;
                });

        // ------------------------------------------------------------------ proxies, hierarchies
        generator("proxy").family("objects").weight(10).inputs(OBJECT).block().source("CodeGenerators.swift:3206")
                .doc("`new Proxy(target, handler)` with a handler of drawn traps bound to visible functions.")
                .register((d, b, in) -> {
                    List<Integer> fns = b.pool(FUNCTION).exact();
                    List<Key> traps = !fns.isEmpty() ? d.uniqueLists(0, 4, dd -> dd.sampled(TRAP_KEYS)) : List.of();
                    var handler = b.objectLiteral();
                    try (handler) {
                        for (Key trap : traps) handler.addProperty(trap, pick(d, fns));
                    }
                    return b.construct(b.builtin(Env.builtin("PROXY")), List.of(in[0], handler.result()));
                });

        generator("object_hierarchy").family("objects").weight(10).block().source("CodeGenerators.swift:3525")
                .doc("Four objects with nested property shapes {p0}, {p0,p1}, {p0,p1,p2}, {p0,p1,p3}.")
                .register((d, b, in) -> {
                    List<Key> keys = d.uniqueLists(4, 4, dd -> dd.sampled(Env.ORDINARY_KEYS));
                    List<Integer> objs = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        var o = b.objectLiteral();
                        try (o) {
                            // empty
                        }
                        objs.add(o.result());
                    }
                    int[][] shapes = {{0}, {0, 1}, {0, 1, 2}, {0, 1, 3}};
                    for (int i = 0; i < 4; i++)
                        for (int k : shapes[i]) b.setProperty(objs.get(i), keys.get(k), variable(d, b));
                    return null;
                });

        generator("element_kind_change").family("properties").weight(5).inputs(ARRAY).source("CodeGenerators.swift:3290")
                .doc("Store a value of another kind into an array element.")
                .register((d, b, in) -> {
                    long index = d.ints(0, 9);
                    b.setElement(in[0], index, variable(d, b));
                    return null;
                });

        // ------------------------------------------------------------------ super (scaffolding)
        generator("super_property_retrieval").family("objects").weight(20).context(Context.METHOD).scaffolds().block()
                .source("CodeGenerators.swift:2663")
                .doc("`super.key` inside an object-literal method.")
                .register((d, b, in) -> {
                    scaffold(d, b, () -> b.getSuperProperty(keyPool(d)));
                    return null;
                });

        generator("super_property_assignment").family("objects").weight(20).context(Context.METHOD).scaffolds().block()
                .source("CodeGenerators.swift:2671")
                .doc("`super.key = value`.")
                .register((d, b, in) -> {
                    scaffold(d, b, () -> {
                        Key key = keyPool(d);
                        b.setSuperProperty(key, variable(d, b));
                    });
                    return null;
                });

        generator("super_property_update").family("objects").weight(10).context(Context.METHOD).scaffolds().block()
                .source("CodeGenerators.swift:2700")
                .doc("`super.key op= value`.")
                .register((d, b, in) -> {
                    scaffold(d, b, () -> {
                        Key key = keyPool(d);
                        int value = variable(d, b, NUMBER);
                        b.updateSuperProperty(key, value, d.sampled(BINARY_OPERATORS));
                    });
                    return null;
                });

        generator("computed_super_property").family("objects").weight(20).context(Context.METHOD).scaffolds().block()
                .source("CodeGenerators.swift:2686")
                .doc("`super[key]` and `super[key] = value`.")
                .register((d, b, in) -> {
                    scaffold(d, b, () -> {
                        int key = variable(d, b);
                        if (d.bool()) b.getComputedSuperProperty(key);
                        else b.setComputedSuperProperty(key, variable(d, b));
                    });
                    return null;
                });

        List<Key> everyPrototypeHas = List.of(Env.k("TO_STRING"), Env.k("VALUE_OF"), Env.k("HAS_OWN_PROPERTY"));
        generator("super_method_call").family("objects").weight(20).context(Context.METHOD).scaffolds().block()
                .source("CodeGenerators.swift:2585")
                .doc("`super.method(args)` for a method every prototype has (a drawn prototype rarely has `m`: "
                        + "the completion test showed \"Cannot find function m\").")
                .register((d, b, in) -> {
                    scaffold(d, b, () -> {
                        Key key = d.sampled(everyPrototypeHas);
                        b.callSuperMethod(key, d.lists(0, 2, dd -> variable(dd, b)));
                    });
                    return null;
                });
    }

    /**
     * Run {@code body} inside a method: directly if one is open, else inside an object literal with
     * a prototype and one method, which is then called (design §7.2 scaffolding). The body draws
     * from the generator's own draw, as the Python lambdas close over {@code draw}.
     */
    static void scaffold(Draw d, Builder b, Runnable body) {
        if ((b.context() & Context.METHOD) != 0) {
            body.run();
            return;
        }
        List<Integer> protoPool = b.pool(OBJECT).exact();
        Key m = Env.k("M");
        var o = b.objectLiteral();
        try (o) {
            if (!protoPool.isEmpty()) o.setPrototype(pick(d, protoPool));
            try (var mb = o.method(m, new Parameters(0))) {
                body.run();
            }
        }
        b.callComputedMethod(o.result(), b.loadString(m.name()), List.of());
    }
}
