// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/FuzzIL/TypeSystem.swift at commit be2811d00282320381fc8007d64873745772011d,
// simplified as described in docs/design-generation-layer.md §5.1 and §15.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Types: a set of possible kinds with an optional object group, signature, element type and
 * known keys. {@code is} is subsumption, {@code mayBe} is intersection, {@code join} is union
 * (over-approximate). Known keys are kept sorted, so iterating them is deterministic.
 */
public record Type(int possible, String group, Signature signature, Type element, Set<Key> props) {
    public Type {
        if (possible == 0) {                 // NOTHING is unique: no group, no signature, no keys
            group = null; signature = null; element = null; props = Set.of();
        } else {
            props = props == null || props.isEmpty() ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(props));
        }
    }

    public Type(int possible) { this(possible, null, null, null, Set.of()); }

    // -- construction ---------------------------------------------------------------------
    public static Type object(String group, Set<Key> props) { return new Type(Kind.OBJECT.bit, group, null, null, Set.copyOf(props)); }
    public static Type object(String group) { return object(group, Set.of()); }
    public static Type object() { return object(null); }
    public static Type function(Signature signature, String group) { return new Type(Kind.FUNCTION.bit, group, signature, null, Set.of()); }
    public static Type function(String group) { return function(null, group); }
    public static Type function(Signature signature) { return function(signature, null); }
    public static Type array(Type element) { return new Type(Kind.OBJECT.bit, "Array", null, element, Set.of(Keys.LENGTH_KEY)); }
    public static Type array() { return array(null); }

    // -- queries ----------------------------------------------------------------------------
    public boolean isNothing() { return possible == 0; }

    public boolean isUnion() { return Integer.bitCount(possible) > 1; }

    public boolean has(Kind k) { return (possible & k.bit) != 0; }

    /** Every value of this type is a value of {@code other} (subsumption; TypeSystem.swift:521-700). */
    public boolean is(Type other) {
        if (isNothing()) return true;
        if (other.isNothing()) return false;
        if ((possible & ~other.possible) != 0) return false;
        if (other.group != null && !other.group.equals(group)) return false;
        if (other.signature != null && !other.signature.equals(signature)) return false;
        if (other.element != null && (element == null || !element.is(other.element))) return false;
        return props.containsAll(other.props);
    }

    /** Some value could be of both types (intersection; TypeSystem.swift:512). */
    public boolean mayBe(Type other) {
        if ((possible & other.possible) == 0) return false;
        return group == null || other.group == null || group.equals(other.group);
    }

    public boolean mayNotBe(Type other) { return !is(other); }

    /** Union: possible kinds united, everything else kept only when both agree. */
    public Type join(Type other) {
        if (isNothing()) return other;
        if (other.isNothing()) return this;
        if (this.equals(other)) return this;
        // Not computeIfAbsent: computing a join recurses into join for the element types,
        // which would modify the map during its own update.
        Map<Type, Type> row = JOINS.computeIfAbsent(this, t -> new ConcurrentHashMap<>());
        Type cached = row.get(other);
        if (cached != null) return cached;
        Type result = computeJoin(other);
        row.put(other, result);
        return result;
    }

    private Type computeJoin(Type other) {
        Type el = null;
        if (element != null && other.element != null) el = element.join(other.element);
        Set<Key> both;
        if (props.isEmpty() || other.props.isEmpty()) {
            both = Set.of();
        } else {
            both = new TreeSet<>(props);
            both.retainAll(other.props);
        }
        return new Type(possible | other.possible,
                Objects.equals(group, other.group) ? group : null,
                Objects.equals(signature, other.signature) ? signature : null,
                el, both);
    }

    /** Memoised results of {@link #join}, keyed by receiver then argument. */
    private static final Map<Type, Map<Type, Type>> JOINS = new ConcurrentHashMap<>();

    public Type withProp(Key key) {
        Set<Key> s = new TreeSet<>(props);
        s.add(key);
        return new Type(possible, group, signature, element, s);
    }

    public Type withoutProp(Key key) {
        Set<Key> s = new TreeSet<>(props);
        s.remove(key);
        return new Type(possible, group, signature, element, s);
    }

    public Type withSignature(Signature sig) { return new Type(possible, group, sig, element, props); }

    public Type withElement(Type el) { return new Type(possible, group, signature, el, props); }

    @Override public String toString() {
        if (possible == 0) return "nothing";
        if (possible == Kind.ALL && group == null) return "any";
        List<String> kinds = new ArrayList<>();
        for (Kind k : Kind.values()) if (has(k)) kinds.add(k.name().toLowerCase());
        List<String> extra = new ArrayList<>();
        if (group != null && !group.isEmpty()) extra.add(group);
        if (signature != null) extra.add("sig");
        if (element != null) extra.add("of " + element);
        if (!props.isEmpty())
            extra.add("{" + props.stream().map(Key::name).sorted().collect(Collectors.joining(",")) + "}");
        return String.join("|", kinds) + (extra.isEmpty() ? "" : "(" + String.join(" ", extra) + ")");
    }

    public static final Type NOTHING = new Type(0);
    public static final Type ANY = new Type(Kind.ALL);
    public static final Type UNDEFINED = new Type(Kind.UNDEFINED.bit);
    public static final Type NULL = new Type(Kind.NULL.bit);
    public static final Type NULLISH = new Type(Kind.of(Kind.UNDEFINED, Kind.NULL));
    public static final Type BOOLEAN = new Type(Kind.BOOLEAN.bit);
    public static final Type INTEGER = new Type(Kind.INTEGER.bit);
    public static final Type FLOAT = new Type(Kind.FLOAT.bit);
    public static final Type NUMBER = new Type(Kind.of(Kind.INTEGER, Kind.FLOAT));
    public static final Type BIGINT = new Type(Kind.BIGINT.bit);
    public static final Type STRING = new Type(Kind.STRING.bit);
    public static final Type SYMBOL = new Type(Kind.SYMBOL.bit);
    public static final Type PRIMITIVE = new Type(Kind.of(Kind.BOOLEAN, Kind.INTEGER, Kind.FLOAT, Kind.STRING));
    public static final Type OBJECT = new Type(Kind.OBJECT.bit);
    public static final Type FUNCTION = new Type(Kind.FUNCTION.bit);
    public static final Type ARRAY = array();
    public static final Type NUMERIC = new Type(Kind.of(Kind.INTEGER, Kind.FLOAT, Kind.BIGINT));
}
