package dev.oxpecker.il;

import java.util.ArrayList;
import java.util.List;

/** Context.swift, minus wasm/async/class/module/worker; plus WITH. A bit mask. */
public final class Context {
    private Context() {}
    public static final int EMPTY = 0;
    public static final int JAVASCRIPT = 1;
    public static final int SUBROUTINE = 1 << 1;
    public static final int GENERATOR_FUNCTION = 1 << 2;
    public static final int METHOD = 1 << 3;
    public static final int LOOP = 1 << 4;
    public static final int OBJECT_LITERAL = 1 << 5;
    public static final int SWITCH_BLOCK = 1 << 6;
    public static final int SWITCH_CASE = 1 << 7;
    public static final int WITH = 1 << 8;

    private static final String[] NAMES = {"JAVASCRIPT", "SUBROUTINE", "GENERATOR_FUNCTION", "METHOD", "LOOP",
            "OBJECT_LITERAL", "SWITCH_BLOCK", "SWITCH_CASE", "WITH"};

    public static String str(int ctx) {
        if (ctx == 0) return "Context.EMPTY";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) if ((ctx & (1 << i)) != 0) names.add(NAMES[i]);
        return "Context." + String.join("|", names);
    }
}
