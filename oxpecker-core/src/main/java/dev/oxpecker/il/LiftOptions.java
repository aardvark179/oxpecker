package dev.oxpecker.il;

/** lift.py LiftOptions: probes and provenance comments on by default, sloppy mode, six probed variables. */
public record LiftOptions(boolean probes, boolean comments, boolean strict, int probeWidth) {
    public static final LiftOptions DEFAULT = new LiftOptions(true, true, false, 6);
    /** No probes, no comments: what the shell's {@code js()} prints. */
    public static final LiftOptions PLAIN = new LiftOptions(false, false, false, 6);

    public LiftOptions withProbes(boolean v) { return new LiftOptions(v, comments, strict, probeWidth); }
    public LiftOptions withComments(boolean v) { return new LiftOptions(probes, v, strict, probeWidth); }
    public LiftOptions withStrict(boolean v) { return new LiftOptions(probes, comments, v, probeWidth); }
    public LiftOptions withProbeWidth(int v) { return new LiftOptions(probes, comments, strict, v); }
}
