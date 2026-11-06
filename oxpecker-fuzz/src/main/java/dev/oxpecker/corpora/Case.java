package dev.oxpecker.corpora;

import java.util.Map;

/**
 * One program of a seed or replay corpus: test262 (as vendored by Rhino), Rhino's own tests,
 * other engines' regression suites. Each importer yields Cases the harness can run.
 *
 * @param tags e.g. {@code {"knownDivergence": "compiled"}}
 */
public record Case(String id, String source, String path, boolean strict, int languageVersion, Map<String, String> tags) {

    public Case {
        tags = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(tags));
    }

    public Case(String id, String source, String path) {
        this(id, source, path, false, 250, Map.of());
    }
}
