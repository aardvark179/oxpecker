package dev.oxpecker.fuzz;

import dev.oxpecker.gen.Choices;
import dev.oxpecker.il.GenOptions;
import dev.oxpecker.il.Mutate.Lineage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The lineages behind corpus entries, by entry id, so extending an entry draws only its new steps
 * instead of replaying the whole lineage. Entries the loop adds are put here as they are found;
 * entries loaded from disk are replayed on first use. Least recently used entries are dropped
 * past {@code capacity}.
 */
public final class LineageCache {
    private final int capacity;
    private final LinkedHashMap<Integer, Lineage> lineages = new LinkedHashMap<>(16, 0.75f, true);
    public int hits = 0, misses = 0;
    /** Misses (or their ancestors) rebuilt from a parent's lineage. */
    public int incremental = 0;

    public LineageCache(int capacity) { this.capacity = capacity; }

    public LineageCache() { this(512); }

    public void put(int entryId, Lineage lineage) {
        lineages.put(entryId, lineage);
        while (lineages.size() > capacity) lineages.remove(lineages.keySet().iterator().next());
    }

    /**
     * The entry's lineage (null if it no longer decodes). On a miss it is rebuilt from its parent's
     * lineage when {@code byId} has the parent (itself cached, or rebuilt the same way), so only the
     * entry's own round is replayed. Otherwise, or when the entry no longer continues its parent's
     * choices, it is replayed from its choices.
     */
    public Lineage get(Entry entry, GenOptions options, int steps, Map<Integer, Entry> byId) {
        Lineage cached = lineages.get(entry.id);
        if (cached != null) {
            hits++;
            return cached;
        }
        misses++;
        return rebuild(entry, options, steps, byId != null ? byId : Map.of());
    }

    private Lineage rebuild(Entry entry, GenOptions options, int steps, Map<Integer, Entry> byId) {
        Choices choices = Choices.decode(entry.choices);
        if (choices == null) return null;
        int cap = entry.cap != null ? entry.cap : steps;
        Lineage lineage = null;
        Entry parentEntry = entry.parent != null ? byId.get(entry.parent) : null;
        if (parentEntry != null && parentEntry.id != entry.id) {
            Lineage parent = lineages.get(parentEntry.id);
            if (parent == null) parent = rebuild(parentEntry, options, steps, byId);
            Choices parentChoices = parent != null ? Choices.decode(parentEntry.choices) : null;
            if (parent != null && parentChoices != null) {
                lineage = Lineages.continueLineage(parent, parentChoices, choices, options, cap);
                if (lineage != null) incremental++;
            }
        }
        if (lineage == null) lineage = Lineages.replay(choices, options, cap);
        if (lineage != null) put(entry.id, lineage);
        return lineage;
    }
}
