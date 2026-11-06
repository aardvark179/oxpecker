package dev.oxpecker.harness;

import dev.oxpecker.Oxpecker;
import dev.oxpecker.util.Py;

import java.util.List;

/**
 * One harness process: its name (the key in RunResult.results), role and pinned mode, and which
 * harness runs it ({@code harness}, launched by bin/harness-&lt;harness&gt;, unless
 * {@code harnessBin} names a launcher).
 */
public record HarnessSpec(String name, String role, String mode, String harness, String harnessBin) {

    public HarnessSpec {
        if (!Ensemble.ROLES.contains(role)) throw new IllegalArgumentException("unknown harness role: " + role);
        if (role.equals(Ensemble.ORACLE)) throw new UnsupportedOperationException("oracle harnesses are not implemented yet");
        if (harness == null) harness = Oxpecker.DEFAULT_HARNESS;
        if (harnessBin == null) {
            List<String> available = Oxpecker.availableHarnesses();
            if (!available.contains(harness)) {
                throw new IllegalArgumentException("unknown harness " + Py.repr(harness) + "; available: "
                        + String.join(", ", available));
            }
            harnessBin = Oxpecker.harnessBin(harness).toString();
        }
    }

    /** On the default harness. */
    public HarnessSpec(String name, String role, String mode) {
        this(name, role, mode, null, null);
    }

    public HarnessSpec(String name, String role, String mode, String harness) {
        this(name, role, mode, harness, null);
    }
}
