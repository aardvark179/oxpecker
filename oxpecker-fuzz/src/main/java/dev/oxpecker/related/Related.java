package dev.oxpecker.related;

import dev.oxpecker.util.Py;

import java.util.List;

/** A regression a finding is probably connected to: its score and the evidence they share. */
public record Related(String name, double score, List<String> shared) {

    public Related {
        shared = List.copyOf(shared);
    }

    @Override
    public String toString() {
        return name + " (" + Py.fixed(score, 2) + ": " + String.join("; ", shared) + ")";
    }
}
