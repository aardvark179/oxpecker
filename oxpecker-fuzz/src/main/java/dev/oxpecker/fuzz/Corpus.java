package dev.oxpecker.fuzz;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.harness.Ensemble;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Mutate.Lineage;
import dev.oxpecker.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code corpus.jsonl} (one Entry per line, append-only) and {@code coverage.exec} in one directory. */
public final class Corpus {
    public final Path dir;
    public final Path file;
    public final Path exec;
    /** {@code Ensemble.describe()} of the run that wrote coverage.exec. */
    public final Path harnesses;
    public final List<Entry> entries = new ArrayList<>();
    public final Map<Integer, Entry> byId = new HashMap<>();

    public Corpus(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("corpus.jsonl");
        this.exec = dir.resolve("coverage.exec");
        this.harnesses = dir.resolve("harnesses.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file))
                for (String ln : Files.readAllLines(file, StandardCharsets.UTF_8))
                    if (!ln.isBlank()) entries.add(Entry.fromJson(Json.parseObject(ln)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Entry e : entries) byId.put(e.id, e);
    }

    public void add(Entry e) {
        entries.add(e);
        byId.put(e.id, e);
        try {
            Files.writeString(file, e.line() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public int nextId() {
        int max = -1;
        for (Entry e : entries) max = Math.max(max, e.id);
        return max + 1;
    }

    /** The entry's lineage; {@code steps} stands in for the cap of an entry recorded without one. */
    public Lineage replay(int index, GenOptions options, int steps) {
        Entry e = entries.get(index);
        Choices c = Choices.decode(e.choices);
        return c != null ? Lineages.replay(c, options, e.cap != null ? e.cap : steps) : null;
    }

    public Lineage replay(int index) { return replay(index, GenOptions.DEFAULT, 5); }

    /**
     * Refuses to resume {@code coverage.exec} recorded by a different guide (its probes would
     * describe another mode's or engine's code); {@code resetCoverage} discards it instead. Records
     * {@code current} (Ensemble.describe) as the corpus's harnesses. A corpus without a record
     * adopts {@code current}.
     */
    public void checkGuide(Map<String, Object> current, boolean resetCoverage) {
        try {
            if (Files.exists(harnesses) && Files.exists(exec)) {
                Map<String, Object> stored = Json.parseObject(Files.readString(harnesses, StandardCharsets.UTF_8));
                String why = Ensemble.describeMismatch(stored, current);
                if (why != null) {
                    if (!resetCoverage)
                        throw new IllegalStateException(exec + " was recorded by another guide (" + why
                                + "); pass reset_coverage (--reset-coverage) to discard it");
                    Files.delete(exec);
                }
            }
            Files.writeString(harnesses, Json.pyDumps(current, 1, false), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
