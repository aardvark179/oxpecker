package dev.oxpecker.il;

import java.util.List;
import java.util.Map;

/** JsOperations.swift:1382-1431. {@code destructuring}: (parameter index, pattern) pairs. */
public record Parameters(int count, boolean hasRest, List<Integer> defaultIndices,
                         List<Map.Entry<Integer, Patterns.Pattern>> destructuring) {
    public static final Parameters NONE = new Parameters(0);

    public Parameters {
        defaultIndices = List.copyOf(defaultIndices);
        destructuring = List.copyOf(destructuring);
        if (count < 0) throw new IllegalArgumentException("parameter count");
        for (int i = 1; i < defaultIndices.size(); i++)
            if (defaultIndices.get(i - 1) > defaultIndices.get(i))
                throw new IllegalArgumentException("default indices must be sorted");
        for (int i : defaultIndices)
            if (!(0 <= i && i < count) || (hasRest && i == count - 1))
                throw new IllegalArgumentException("default index out of range or on the rest parameter");
        for (var e : destructuring)
            if (!(0 <= e.getKey() && e.getKey() < count))
                throw new IllegalArgumentException("destructuring index out of range");
    }

    public Parameters(int count) { this(count, false, List.of(), List.of()); }

    public Parameters(int count, boolean hasRest) { this(count, hasRest, List.of(), List.of()); }

    public int numDefaults() { return defaultIndices.size(); }

    /** Inner outputs of the subroutine: one per plain parameter, the bindings of a destructured one. */
    public int numBindings() {
        int n = count - destructuring.size();
        for (var e : destructuring) n += Patterns.numBindings(e.getValue());
        return n;
    }

    @Override public String toString() {
        StringBuilder d = new StringBuilder("(");
        for (int i = 0; i < destructuring.size(); i++) {
            var e = destructuring.get(i);
            if (i > 0) d.append(", ");
            d.append('(').append(e.getKey()).append(", ").append(e.getValue()).append(')');
        }
        if (destructuring.size() == 1) d.append(',');
        d.append(')');
        return "Parameters(count=" + count + ", has_rest=" + Repr.of(hasRest) + ", default_indices="
                + Repr.of(defaultIndices) + ", destructuring=" + d + ")";
    }
}
