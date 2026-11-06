package dev.oxpecker.il.generators;

/**
 * Generator families (oxpecker/il/generators/__init__.py). Registering them in this order keeps
 * the registry's order, which the value makers' lookup depends on. Files translated from Fuzzilli
 * carry the attribution header; each generator's {@code source} names the Swift definition it follows.
 */
public final class Families {
    private Families() {}

    public static void registerAll() {
        Values.register();
        Operations.register();
        Control.register();
        Calls.register();
        Objects.register();
        Compiler.register();
        Java.register();
        Continuations.register();
    }
}
