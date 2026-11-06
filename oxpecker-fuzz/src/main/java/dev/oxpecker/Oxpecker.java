package dev.oxpecker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * oxpecker: differential testing of Mozilla Rhino, interpreted vs compiled. Where the repository
 * is, and which harnesses it has.
 */
public final class Oxpecker {
    private Oxpecker() {}

    /** Each harness is a project harness-&lt;name&gt;/ with a launcher bin/harness-&lt;name&gt;;
     * OXPECKER_HARNESS picks the one used where none is named (bin/harness reads it too). */
    public static final String DEFAULT_HARNESS = envOr("OXPECKER_HARNESS", "rhino2.0");

    private static volatile Path root;

    /** The repository root: system property {@code oxpecker.root}, else env {@code OXPECKER_ROOT},
     * else the first directory up from the working directory that has {@code bin/harness}. */
    public static Path root() {
        Path r = root;
        if (r == null) {
            synchronized (Oxpecker.class) {
                if (root == null) root = findRoot();
                r = root;
            }
        }
        return r;
    }

    private static Path findRoot() {
        String p = System.getProperty("oxpecker.root");
        if (p == null || p.isEmpty()) p = System.getenv("OXPECKER_ROOT");
        if (p != null && !p.isEmpty()) return Path.of(p).toAbsolutePath().normalize();
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            if (Files.exists(d.resolve("bin").resolve("harness"))) return d;
        }
        throw new IllegalStateException("cannot find the oxpecker repository: set -Doxpecker.root or OXPECKER_ROOT, "
                + "or run from inside it (no bin/harness above " + Path.of("").toAbsolutePath() + ")");
    }

    /** The launcher of harness {@code name}. */
    public static Path harnessBin(String name) {
        return root().resolve("bin").resolve("harness-" + name);
    }

    /** Where harness {@code name}'s build puts its jar and the JaCoCo agent. */
    public static Path harnessLibs(String name) {
        return root().resolve("harness-" + name).resolve("build").resolve("libs");
    }

    /** The harnesses with a launcher in bin/. */
    public static List<String> availableHarnesses() {
        try (Stream<Path> s = Files.list(root().resolve("bin"))) {
            return s.map(x -> x.getFileName().toString()).filter(n -> n.startsWith("harness-"))
                    .map(n -> n.substring("harness-".length())).sorted(dev.oxpecker.util.Py.ORDER).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The default harness's launcher (HARNESS_BIN). */
    public static Path harnessBin() {
        return harnessBin(DEFAULT_HARNESS);
    }

    private static String envOr(String key, String dflt) {
        String v = System.getenv(key);
        return v == null ? dflt : v;
    }
}
