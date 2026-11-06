package dev.oxpecker.gen;

/**
 * Hypothesis's {@code reject()}: this draw cannot produce a value. Thrown out of a {@link Gen};
 * the engine turns it into "no value" (a generation that returns null, a shrink candidate that is
 * not interesting). Catching it inside a generator is fine (a rejected mutation step that is
 * skipped and counted, say): the draws made so far stay recorded.
 */
public final class Reject extends RuntimeException {
    public Reject(String why) { super(why, null, false, false); }

    public Reject() { this("rejected"); }

    public static <T> T reject() { throw new Reject(); }

    public static <T> T reject(String why) { throw new Reject(why); }
}
