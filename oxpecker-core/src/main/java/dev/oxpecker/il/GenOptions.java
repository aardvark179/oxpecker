package dev.oxpecker.il;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generation options: what a program draw is parameterised by. {@code families}/{@code generators}
 * null means unrestricted; {@code weights} maps a generator or family name to a multiplier.
 */
public record GenOptions(int minStatements, int maxStatements, int maxDepth, int probeEvery, int probeWidth,
                         boolean strict, int languageVersion, Set<String> toggles, Set<String> families,
                         Set<String> generators, Map<String, Double> weights) {
    public static final Set<String> ALL_TOGGLES = Set.of("let", "generators", "for-of", "destructuring", "spread",
            "continuations", "with", "eval");
    public static final String TOGGLE_LET = "let";
    public static final int DEFAULT_MIN_STATEMENTS = 3;
    public static final GenOptions DEFAULT = builder().build();

    public GenOptions {
        toggles = sorted(toggles);
        families = families == null ? null : sorted(families);
        generators = generators == null ? null : sorted(generators);
        weights = java.util.Collections.unmodifiableMap(new TreeMap<>(weights == null ? Map.of() : weights));
        if (minStatements < 0 || maxStatements < 0)
            throw new IllegalArgumentException("statement bounds must not be negative: min_statements=" + minStatements
                    + ", max_statements=" + maxStatements);
        if (maxStatements < minStatements)
            throw new IllegalArgumentException("max_statements=" + maxStatements + " is below min_statements=" + minStatements);
        if (maxDepth < 0) throw new IllegalArgumentException("max_depth must not be negative: " + maxDepth);
        Set<String> unknown = new TreeSet<>(toggles);
        unknown.removeAll(ALL_TOGGLES);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown toggles: " + unknown);
    }

    private static Set<String> sorted(Set<String> s) { return java.util.Collections.unmodifiableSortedSet(new TreeSet<>(s)); }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.minStatements = minStatements; b.maxStatements = maxStatements; b.maxDepth = maxDepth;
        b.probeEvery = probeEvery; b.probeWidth = probeWidth; b.strict = strict; b.languageVersion = languageVersion;
        b.toggles = toggles; b.families = families; b.generators = generators; b.weights = weights;
        return b;
    }

    /** Python's keyword arguments: unset {@code minStatements} is DEFAULT_MIN_STATEMENTS, lowered to maxStatements. */
    public static final class Builder {
        Integer minStatements = null;
        int maxStatements = 12, maxDepth = 3, probeEvery = 1, probeWidth = 6, languageVersion = 200;
        boolean strict = false;
        Set<String> toggles = ALL_TOGGLES, families = null, generators = null;
        Map<String, Double> weights = Map.of();

        public Builder minStatements(Integer v) { minStatements = v; return this; }
        public Builder maxStatements(int v) { maxStatements = v; return this; }
        public Builder maxDepth(int v) { maxDepth = v; return this; }
        /** A Probe after every n top-level statements; 0 = none. */
        public Builder probeEvery(int v) { probeEvery = v; return this; }
        /** Variables observed per probe. */
        public Builder probeWidth(int v) { probeWidth = v; return this; }
        public Builder strict(boolean v) { strict = v; return this; }
        public Builder languageVersion(int v) { languageVersion = v; return this; }
        public Builder toggles(Set<String> v) { toggles = v; return this; }
        public Builder families(Set<String> v) { families = v; return this; }
        public Builder generators(Set<String> v) { generators = v; return this; }
        public Builder weights(Map<String, Double> v) { weights = v; return this; }

        public GenOptions build() {
            int min = minStatements != null ? minStatements : Math.min(DEFAULT_MIN_STATEMENTS, maxStatements);
            return new GenOptions(min, maxStatements, maxDepth, probeEvery, probeWidth, strict, languageVersion,
                    toggles, families, generators, weights);
        }
    }
}
