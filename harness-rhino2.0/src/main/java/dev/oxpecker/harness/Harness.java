package dev.oxpecker.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.mozilla.javascript.Context;

/**
 * Entry point.
 *
 * <pre>
 *   java -jar oxpecker-harness-all.jar                 # JSON-lines server on stdin/stdout
 *   java -jar oxpecker-harness-all.jar --serve --mode compiled   # ... running only that mode
 *   java -jar oxpecker-harness-all.jar --replay case.js [--mode compiled] [--language-version 250] [--strict]
 *   java -jar oxpecker-harness-all.jar --info
 * </pre>
 */
public final class Harness {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** Version of the JSON-lines protocol spoken by {@link #serve}. */
    static final int PROTOCOL = 1;
    /** The name this harness declares in {@link #info}: which engine it runs. */
    static final String NAME = "Rhino2.0";

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("--serve")) {
            Mode pinned = null;
            if (args.length == 3 && args[1].equals("--mode")) {
                pinned = Mode.fromWire(args[2]);
            } else if (args.length > 1) {
                usage();
            }
            serve(pinned);
        } else if (args[0].equals("--replay")) {
            replay(args);
        } else if (args[0].equals("--info")) {
            System.out.println(GSON.toJson(info()));
        } else {
            usage();
        }
    }

    private static void usage() {
        System.err.println("usage: harness [--serve [--mode m] | --replay file.js [--mode m]... [--language-version n] [--strict] | --info]");
        System.exit(2);
    }

    /** The {@link #info(Mode, Coverage)} of an unpinned harness without a coverage agent check. */
    static Map<String, Object> info() {
        return info(null, new Coverage());
    }

    /**
     * What this harness is. {@code protocol} versions the JSON-lines wire format, so that a
     * harness for another engine (an oracle) can implement it; {@code name} and {@code modes}
     * declare the engine and the execution modes it can be pinned to; {@code capabilities} lists
     * the optional Outcome fields this harness fills in, which the client compares only when both
     * sides report them.
     */
    static Map<String, Object> info(Mode pinned, Coverage coverage) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Rhino's own version string says "Rhino Snapshot" for release jars too (1.9.2), so the
        // artifact version the jar was built with, stamped into the manifest by fatJar, comes first.
        String bundled = null;
        try (java.io.InputStream in = Harness.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in != null) bundled = new java.util.jar.Manifest(in).getMainAttributes().getValue("Rhino-Version");
        } catch (java.io.IOException ignored) {
        }
        String version;
        try (Context cx = Context.enter()) {
            String impl = cx.getImplementationVersion();
            version = bundled != null ? bundled + " (" + impl + ")" : impl;
        }
        m.put("protocol", PROTOCOL);
        m.put("name", NAME);
        m.put("version", version);
        m.put("rhino", version);
        m.put("java", System.getProperty("java.version"));
        m.put("harness", "0.1.0");
        // Every mode this harness can run; `mode` is the one it is pinned to (absent: any).
        m.put("modes", Mode.ALL.stream().map(mode -> mode.wire).toList());
        m.put("mode", pinned == null ? null : pinned.wire);
        List<String> capabilities = new ArrayList<>(List.of("javaTypes", "javaTrace", "continuations"));
        if (coverage.enabled()) capabilities.add("coverage");
        m.put("capabilities", capabilities);
        m.put("coverage", coverage.enabled());
        return m;
    }

    /**
     * JSON lines over stdio. Logging goes to stderr only; stdout is reserved for responses.
     * With {@code pinned}, every request runs in that mode only and asking for another is an error.
     */
    static void serve(Mode pinned) throws IOException {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ModeRunner runner = new ModeRunner();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Map<String, Object> resp = new LinkedHashMap<>();
            Request req;
            try {
                req = GSON.fromJson(line, Request.class);
            } catch (JsonSyntaxException e) {
                resp.put("error", "bad request: " + e.getMessage());
                out.println(GSON.toJson(resp));
                out.flush();
                continue;
            }
            resp.put("id", req.id);
            String cmd = req.cmd == null ? "run" : req.cmd;
            switch (cmd) {
                case "exit" -> {
                    resp.put("ok", true);
                    out.println(GSON.toJson(resp));
                    out.flush();
                    return;
                }
                case "info" -> resp.put("info", info(pinned, runner.coverage));
                case "coverage-save", "coverage-load" -> {
                    try {
                        if (!runner.coverage.enabled()) resp.put("error", "coverage agent not attached");
                        else if (cmd.equals("coverage-save")) runner.coverage.save(req.path);
                        else runner.coverage.load(req.path);
                        resp.put("coveredProbes", runner.coverage.coveredProbes());
                    } catch (IOException e) {
                        resp.put("error", cmd + " failed: " + e);
                    }
                }
                case "coverage" -> {
                    try {
                        resp.put("coverage", runner.coverage.report());
                    } catch (IOException e) {
                        resp.put("error", "coverage report failed: " + e);
                    }
                }
                case "run" -> {
                    if (req.source == null) {
                        resp.put("error", "missing source");
                    } else {
                        try {
                            resp.put("results", runAll(runner, req, req.modeList(pinned)));
                        } catch (IllegalArgumentException e) {
                            resp.put("error", e.getMessage());
                        }
                    }
                }
                default -> resp.put("error", "unknown cmd: " + cmd);
            }
            if (runner.poisoned()) resp.put("restart", true);
            out.println(GSON.toJson(resp));
            out.flush();
            if (runner.poisoned()) {
                System.err.println("harness: worker thread stuck or JVM unhealthy, exiting for restart");
                System.exit(3);
            }
        }
    }

    static Map<String, Outcome> runAll(ModeRunner runner, Request req, List<Mode> modes) {
        Map<String, Outcome> results = new LinkedHashMap<>();
        for (Mode mode : modes) {
            results.put(mode.wire, runner.run(req, mode));
            if (runner.poisoned()) break;
        }
        return results;
    }

    static void replay(String[] args) throws IOException {
        Request req = new Request();
        List<String> modes = new ArrayList<>();
        Path file = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--mode" -> modes.add(args[++i]);
                case "--language-version" -> req.languageVersion = Integer.parseInt(args[++i]);
                case "--strict" -> req.strict = true;
                case "--budget" -> req.instructionBudget = Long.parseLong(args[++i]);
                case "--wall-ms" -> req.wallMs = Long.parseLong(args[++i]);
                case "--seed" -> req.seed = Long.parseLong(args[++i]);
                case "--json" -> req.cmd = "json";
                case "--continuations" -> req.continuations = true;
                default -> file = Path.of(args[i]);
            }
        }
        Objects.requireNonNull(file, "replay needs a file");
        req.id = file.getFileName().toString();
        req.sourceName = req.id;
        req.source = Files.readString(file, StandardCharsets.UTF_8);
        if (!modes.isEmpty()) req.modes = modes;
        Map<String, Outcome> results = runAll(new ModeRunner(), req, req.modeList(null));
        if ("json".equals(req.cmd)) {
            System.out.println(GSON.toJson(results));
            return;
        }
        for (Outcome o : results.values()) {
            System.out.println("== " + o.mode + " (" + o.ms + " ms, compiledActually=" + o.compiledActually + ")");
            System.out.println("status: " + o.status + (o.reason != null ? " [" + o.reason + "]" : ""));
            if (o.value != null) System.out.println("value: " + o.value + "   javaTypes=" + o.valueJavaTypes);
            if (o.thrown != null) {
                System.out.println("thrown: " + o.thrown.name + " (" + o.thrown.kind + ") " + o.thrown.message);
                if (o.thrown.value != null) System.out.println("  value: " + o.thrown.value);
            }
            for (int i = 0; i < o.trace.size(); i++) {
                System.out.println("trace[" + i + "]: " + o.trace.get(i) + "   javaTypes=" + o.traceJavaTypes.get(i));
            }
            for (int i = 0; i < o.javaTrace.size(); i++) {
                System.out.println("java[" + i + "]: " + o.javaTrace.get(i) + "   classes=" + o.javaTraceTypes.get(i));
            }
            for (String s : o.continuations) System.out.println("continuation: " + s);
            for (String s : o.output) System.out.println("out: " + s);
            for (String s : o.soft) System.out.println("soft: " + s);
            if (o.engineException != null) {
                System.out.println("engine: " + o.engineException.clazz + ": " + o.engineException.message);
                System.out.print(o.engineException.stack);
            }
        }
        if (results.size() >= 2) {
            List<Outcome> list = new ArrayList<>(results.values());
            Outcome a = list.get(0), b = list.get(1);
            boolean same = Objects.equals(a.status, b.status) && Objects.equals(a.value, b.value)
                    && Objects.equals(a.trace, b.trace) && Objects.equals(a.output, b.output)
                    && Objects.equals(a.javaTrace, b.javaTrace)
                    && Objects.equals(a.thrown == null ? null : a.thrown.name, b.thrown == null ? null : b.thrown.name)
                    && Objects.equals(a.thrown == null ? null : a.thrown.value, b.thrown == null ? null : b.thrown.value);
            boolean sameJava = Objects.equals(a.valueJavaTypes, b.valueJavaTypes)
                    && Objects.equals(a.traceJavaTypes, b.traceJavaTypes)
                    && Objects.equals(a.javaTraceTypes, b.javaTraceTypes);
            System.out.println("== verdict: " + (same ? "SAME" : "DIFF") + (sameJava ? "" : " (java types differ)"));
        }
    }
}
