package dev.oxpecker.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.mozilla.javascript.Context;

/**
 * The Rhino execution configurations under test: one per {@link Context.EvaluationMethod} this
 * Rhino offers (and considers valid here), plus one per valid interpreted method with a no-op
 * Debugger attached (a Debugger only affects interpreted code).
 */
public final class Mode {
    /** Every mode: Rhino's evaluation methods in their order, then their debugger variants. */
    static final List<Mode> ALL = all();
    static final Mode INTERPRETED = fromWire("interpreted");
    static final Mode COMPILED = fromWire("compiled");

    public final String wire;
    public final Context.EvaluationMethod method;
    public final boolean interpreted;
    /** A no-op Debugger attached, as in Rhino's own test262 runner (interpreted methods only). */
    public final boolean debugger;

    private Mode(String wire, Context.EvaluationMethod method, boolean debugger) {
        this.wire = wire;
        this.method = method;
        this.interpreted = method.isInterpreted();
        this.debugger = debugger;
    }

    private static List<Mode> all() {
        List<Mode> modes = new ArrayList<>();
        for (Context.EvaluationMethod m : Context.EvaluationMethod.values()) {
            if (m.isValid()) modes.add(new Mode(wireName(m), m, false));
        }
        for (Context.EvaluationMethod m : Context.EvaluationMethod.values()) {
            if (m.isValid() && m.isInterpreted()) modes.add(new Mode(debuggerWireName(m), m, true));
        }
        return List.copyOf(modes);
    }

    /**
     * The wire name of an evaluation method: the two the client, regressions and corpora already
     * know keep their names, any other is its constant's name in lower case.
     */
    private static String wireName(Context.EvaluationMethod m) {
        return switch (m) {
            case Interpreter -> "interpreted";
            case Compiler -> "compiled";
            default -> m.name().toLowerCase(Locale.ROOT);
        };
    }

    /** The debugger variant's wire name: "debugger" for the interpreter (as before), else "<mode>-debugger". */
    private static String debuggerWireName(Context.EvaluationMethod m) {
        return m == Context.EvaluationMethod.Interpreter ? "debugger" : wireName(m) + "-debugger";
    }

    public static Mode fromWire(String s) {
        for (Mode m : ALL) {
            if (m.wire.equals(s)) return m;
        }
        throw new IllegalArgumentException("unknown mode: " + s);
    }

    void configure(Context cx) {
        cx.setEvaluationMethod(method);
        if (debugger) cx.setDebugger(new NoOpDebugger(), null);
    }
}
