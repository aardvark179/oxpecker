package dev.oxpecker.il;

import java.util.List;

/**
 * Destructuring (JsOperations.swift:3571-3702). Targets: a fresh binding, a member of an existing
 * object, or a nested pattern.
 */
public final class Patterns {
    private Patterns() {}

    public sealed interface Target permits Binding, PropertyTarget, ElementTarget, ComputedTarget, Pattern {}

    public sealed interface Pattern extends Target permits ObjectPattern, ArrayPattern {}

    /** Declares (Destruct) or assigns (DestructAndReassign) a plain variable. */
    public record Binding() implements Target {
        @Override public String toString() { return "Binding()"; }
    }

    public record PropertyTarget(Key key) implements Target {
        @Override public String toString() { return Repr.record(this); }
    }

    public record ElementTarget(int index) implements Target {
        @Override public String toString() { return Repr.record(this); }
    }

    /** {@code <obj>[<key>] = ...}; takes two inputs. */
    public record ComputedTarget() implements Target {
        @Override public String toString() { return "ComputedTarget()"; }
    }

    /** {@code key == null}: a computed key (one extra input). */
    public record ObjectProperty(Key key, Target target, boolean hasDefault) {
        public ObjectProperty(Key key, Target target) { this(key, target, false); }
        @Override public String toString() { return Repr.record(this); }
    }

    /** {@code hasRest}: {@code ...rest}, always a fresh binding. */
    public record ObjectPattern(List<ObjectProperty> properties, boolean hasRest) implements Pattern {
        public ObjectPattern { properties = List.copyOf(properties); }
        public ObjectPattern(List<ObjectProperty> properties) { this(properties, false); }
        @Override public String toString() { return Repr.record(this); }
    }

    /** {@code target == null}: a hole. */
    public record ArrayElement(Target target, boolean hasDefault) {
        public ArrayElement(Target target) { this(target, false); }
        @Override public String toString() { return Repr.record(this); }
    }

    public record ArrayPattern(List<ArrayElement> elements, Target rest) implements Pattern {
        public ArrayPattern { elements = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(elements)); }
        public ArrayPattern(List<ArrayElement> elements) { this(elements, null); }
        @Override public String toString() { return Repr.record(this); }
    }

    public static int numBindings(Target t) {
        if (t == null || t instanceof PropertyTarget || t instanceof ElementTarget || t instanceof ComputedTarget) return 0;
        if (t instanceof Binding) return 1;
        if (t instanceof ObjectPattern o) {
            int n = o.hasRest() ? 1 : 0;
            for (ObjectProperty p : o.properties()) n += numBindings(p.target());
            return n;
        }
        ArrayPattern a = (ArrayPattern) t;
        int n = numBindings(a.rest());
        for (ArrayElement e : a.elements()) n += numBindings(e.target());
        return n;
    }

    static int targetInputs(Target t) {
        if (t == null || t instanceof Binding) return 0;
        if (t instanceof PropertyTarget || t instanceof ElementTarget) return 1;
        if (t instanceof ComputedTarget) return 2;
        return numExtraInputs((Pattern) t);
    }

    /**
     * Inputs beyond the source: computed keys, default values and member-target objects, in
     * depth-first left-to-right pattern order (JsOperations.swift:3668-3693).
     */
    public static int numExtraInputs(Pattern p) {
        int n = 0;
        if (p instanceof ObjectPattern o) {
            for (ObjectProperty q : o.properties())
                n += (q.key() == null ? 1 : 0) + (q.hasDefault() ? 1 : 0) + targetInputs(q.target());
            return n;
        }
        ArrayPattern a = (ArrayPattern) p;
        for (ArrayElement e : a.elements()) n += (e.hasDefault() ? 1 : 0) + targetInputs(e.target());
        return n + targetInputs(a.rest());
    }
}
