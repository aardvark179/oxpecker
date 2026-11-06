package dev.oxpecker.related;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.harness.Compare;
import dev.oxpecker.harness.Harness;
import dev.oxpecker.harness.RunOptions;
import dev.oxpecker.harness.RunResult;
import dev.oxpecker.harness.Verdict;
import dev.oxpecker.util.Py;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The exact signatures to skip plus a fingerprint per regression to relate new findings to. */
public final class KnownIndex {
    public final Set<String> signatures;
    public final List<Fingerprint> entries;
    private Map<String, Double> idf = Map.of();

    public KnownIndex(Set<String> signatures, List<Fingerprint> entries) {
        this.signatures = new HashSet<>(signatures);
        this.entries = new ArrayList<>(entries);
        reindex();
    }

    public KnownIndex(List<Fingerprint> entries) {
        this(Set.of(), entries);
    }

    public KnownIndex() {
        this(Set.of(), List.of());
    }

    /** Recomputes the token rarities after entries changed (Python's __post_init__). */
    public void reindex() {
        Map<String, Integer> df = new HashMap<>();
        for (Fingerprint e : entries) for (String t : e.tokens()) df.merge(t, 1, Integer::sum);
        int n = Math.max(entries.size(), 1);
        Map<String, Double> out = new HashMap<>();
        df.forEach((t, c) -> out.put(t, Math.log((double) (n + 1) / c)));
        idf = out;
    }

    double idf(String token) {
        return idf.getOrDefault(token, 0.0);
    }

    public List<Related> related(Fingerprint fp) {
        return related(fp, 3, 0.5);
    }

    /** Regressions ranked by evidence: frame and message 1.0 each, source up to 1.0, coarse 0.2.
     * Source-only scores below ~0.7 are usually shared incidental syntax, not a link. */
    public List<Related> related(Fingerprint fp, int limit, double threshold) {
        List<Related> scored = new ArrayList<>();
        for (Fingerprint e : entries) {
            double score = 0.0;
            List<String> shared = new ArrayList<>();
            Set<String> frames = intersect(fp.frames(), e.frames());
            if (!frames.isEmpty()) {
                score += 1.0;
                String f = Py.min(frames);
                shared.add("frame " + (f.startsWith("org.mozilla.javascript.") ? f.substring("org.mozilla.javascript.".length()) : f));
            }
            Set<String> messages = intersect(fp.messages(), e.messages());
            if (!messages.isEmpty()) {
                score += 1.0;
                shared.add("message " + Py.repr(Py.head(Py.min(messages), 70)));
            }
            // The rarity-weighted mass of the constructs both use, saturating: a ratio fails both
            // ways (over the union, a long finding drowns a minimal regression; over the
            // regression alone, a regression that is little more than `for` matches every loop).
            Set<String> both = intersect(fp.tokens(), e.tokens());
            if (!both.isEmpty()) {
                double mass = 0;
                for (String t : both) mass += idf(t);
                double overlap = 1 - Math.exp(-mass / Fingerprints.SOURCE_SCALE);
                score += overlap;
                // Python breaks ties in set order (hash-seeded); here by name, so it is stable.
                List<String> rare = both.stream()
                        .sorted(Comparator.<String>comparingDouble(t -> -idf(t)).thenComparing(Py.ORDER))
                        .limit(4).toList();
                shared.add("source " + Py.fixed(overlap, 2) + " (" + String.join(" ", rare) + ")");
            }
            if (!fp.coarse().isEmpty() && fp.coarse().equals(e.coarse())) {
                score += 0.2;
                shared.add("coarse " + e.coarse());
            }
            if (score >= threshold) scored.add(new Related(e.name(), Py.round(score, 2), shared));
        }
        scored.sort(Comparator.comparingDouble(r -> -r.score()));
        return scored.subList(0, Math.min(limit, scored.size()));
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    /** tests/regressions. */
    public static Path regressions() {
        return Oxpecker.root().resolve("tests").resolve("regressions");
    }

    public static KnownIndex knownIndex(Harness client) {
        return knownIndex(client, regressions());
    }

    /**
     * Runs every regression (tests/regressions/*.js) through the harness: their signatures are
     * known, and with tests/known_signatures.txt they are what the loop does not record as new.
     * master-only/ regressions do not reproduce on the pinned Rhino; they are related by source only.
     */
    public static KnownIndex knownIndex(Harness client, Path regressions) {
        KnownIndex index = new KnownIndex();
        for (Path path : glob(regressions)) {
            String source = read(path);
            RunResult run = client.run(source, RunOptions.DEFAULT.withSourceName(path.getFileName().toString()));
            Verdict verdict = Compare.compareRun(run);
            index.signatures.add(verdict.signature());
            index.entries.add(Fingerprint.of(path.getFileName().toString(), source, run, verdict));
        }
        List<Path> subdirs;
        try (Stream<Path> s = Files.list(regressions)) {
            subdirs = s.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Path> nested = new ArrayList<>();
        for (Path d : subdirs) nested.addAll(glob(d));
        nested.sort(Py.PATH_ORDER);
        for (Path path : nested) {
            index.entries.add(Fingerprint.of(path.getParent().getFileName() + "/" + path.getFileName(), read(path), null, null));
        }
        Path extra = regressions.getParent().resolve("known_signatures.txt");
        if (Files.exists(extra)) index.signatures.addAll(readSignatures(extra));
        index.reindex();
        return index;
    }

    /** The signatures of a known_signatures.txt: one per line, {@code #} comments. */
    public static Set<String> readSignatures(Path file) {
        Set<String> out = new HashSet<>();
        for (String ln : Py.splitlines(read(file))) {
            String s = Py.strip(Py.partition(ln, "#")[0]);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** {@code sorted(dir.glob("*.js"))}. */
    static List<Path> glob(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".js")).sorted(Py.PATH_ORDER).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
