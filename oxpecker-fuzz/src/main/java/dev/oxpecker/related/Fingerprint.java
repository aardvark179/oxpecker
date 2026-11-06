package dev.oxpecker.related;

import dev.oxpecker.harness.RunResult;
import dev.oxpecker.harness.Verdict;

import java.util.Set;

/** The evidence a finding or regression offers for relating it to others. */
public record Fingerprint(String name, String signature, String coarse, Set<String> messages, Set<String> frames,
                          Set<String> tokens) {

    public Fingerprint {
        messages = Set.copyOf(messages);
        frames = Set.copyOf(frames);
        tokens = Set.copyOf(tokens);
    }

    public static Fingerprint of(String name, String source, RunResult run, Verdict verdict) {
        boolean finding = verdict != null && verdict.isFinding();
        return new Fingerprint(name,
                finding ? verdict.signature() : "",
                finding ? verdict.coarseSignature() : "",
                run != null && finding ? Fingerprints.runMessages(run) : Set.of(),
                run != null && finding ? Fingerprints.runFrames(run) : Set.of(),
                Fingerprints.sourceTokens(source));
    }
}
