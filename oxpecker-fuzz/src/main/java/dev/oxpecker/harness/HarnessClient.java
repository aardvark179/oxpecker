package dev.oxpecker.harness;

import com.google.gson.JsonParseException;
import dev.oxpecker.Oxpecker;
import dev.oxpecker.util.Json;
import dev.oxpecker.util.Py;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One long-lived JVM running the harness, spoken to in JSON lines over stdio; restarted when it
 * dies. Not thread-safe; use one client per worker. With {@code mode}, the JVM is pinned to that
 * mode ({@code --serve --mode m}) and runs nothing else.
 */
public final class HarnessClient implements Harness {
    public static final List<String> DEFAULT_MODES = List.of("interpreted", "compiled");

    private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "oxpecker-harness-deadline");
        t.setDaemon(true);
        return t;
    });

    private final String harnessBin;
    private final boolean coverage;
    private final String mode;
    private int recycleEvery;
    private final double hardTimeoutS;
    private final boolean quiet;
    /** Coverage lives in the JVM: a restart would start from nothing, and every probe would be
     * new again. With a checkpoint (.exec) set, a restarted JVM loads it first. */
    private volatile String coverageCheckpoint;
    private volatile Process proc;
    private BufferedWriter stdin;
    private InputStream stdout;
    private int casesSinceStart;
    private int restarts;

    public HarnessClient(Path harnessBin, String mode, boolean quiet, boolean coverage, int recycleEvery,
                         double hardTimeoutS) {
        this.harnessBin = harnessBin.toString();
        this.coverage = coverage;
        this.mode = mode;
        this.recycleEvery = recycleEvery;
        this.hardTimeoutS = hardTimeoutS;
        this.quiet = quiet;
    }

    public HarnessClient(Path harnessBin, String mode, boolean quiet, boolean coverage) {
        this(harnessBin, mode, quiet, coverage, 5000, 60.0);
    }

    /** The default harness, unpinned, quiet or not. */
    public HarnessClient(boolean quiet) {
        this(Oxpecker.harnessBin(), null, quiet, false);
    }

    public HarnessClient() {
        this(false);
    }

    public String harnessBin() { return harnessBin; }
    public boolean coverage() { return coverage; }
    public String mode() { return mode; }
    public int restarts() { return restarts; }
    public int casesSinceStart() { return casesSinceStart; }
    public boolean isRunning() { return proc != null; }
    public String coverageCheckpoint() { return coverageCheckpoint; }
    public void setCoverageCheckpoint(String path) { coverageCheckpoint = path; }

    // -- process management --------------------------------------------------------------

    public HarnessClient start() {
        if (!Files.exists(Path.of(harnessBin))) throw new HarnessError("harness launcher not found: " + harnessBin);
        List<String> cmd = new ArrayList<>(List.of(harnessBin, "--serve"));
        if (mode != null) cmd.addAll(List.of("--mode", mode));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (coverage) {
            pb.environment().put("OXPECKER_COVERAGE", "1");
            recycleEvery = Math.max(recycleEvery, 1_000_000_000);   // coverage lives in the JVM: never recycle
        }
        pb.redirectError(quiet ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.INHERIT);
        try {
            Process p = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedInputStream(p.getInputStream(), 1 << 16);
            proc = p;
        } catch (IOException e) {
            throw new HarnessError("cannot start " + harnessBin + ": " + e.getMessage(), e);
        }
        casesSinceStart = 0;
        return this;
    }

    @Override
    public void stop() {
        Process p = proc;
        proc = null;
        if (p == null) return;
        try {
            stdin.write(Json.line(Map.of("cmd", "exit")) + "\n");
            stdin.flush();
            if (!p.waitFor(2, TimeUnit.SECONDS)) throw new IOException("timeout");
        } catch (IOException | InterruptedException e) {
            p.destroyForcibly();
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void restart() {
        stop();
        restarts++;
        start();
        String cp = coverageCheckpoint;
        if (coverage && cp != null && Files.exists(Path.of(cp))) coverageLoad(cp);
    }

    @Override
    public Map<String, Object> info() {
        return Outcome.castMap(request(Map.of("cmd", "info")).get("info"));
    }

    /** The running process's declared name, version, modes and capabilities. */
    public HarnessInfo harnessInfo() {
        return HarnessInfo.fromJson(info());
    }

    @Override
    public int coverageSave(String path) {
        Map<String, Object> resp = request(ordered("cmd", "coverage-save", "path", path));
        if (resp.containsKey("error")) throw new HarnessError(Py.str(resp.get("error")));
        return ((Number) resp.get("coveredProbes")).intValue();
    }

    @Override
    public int coverageLoad(String path) {
        Map<String, Object> resp = request(ordered("cmd", "coverage-load", "path", path));
        if (resp.containsKey("error")) throw new HarnessError(Py.str(resp.get("error")));
        return ((Number) resp.get("coveredProbes")).intValue();
    }

    /** Per-bucket Rhino coverage (needs OXPECKER_COVERAGE=1 in the environment of bin/harness). */
    @Override
    public Map<String, Object> coverageReport() {
        Map<String, Object> resp = request(Map.of("cmd", "coverage"));
        if (resp.containsKey("error")) throw new HarnessError(Py.str(resp.get("error")));
        return Outcome.castMap(resp.get("coverage"));
    }

    /** Asks a harness launcher what it is ({@code --info}, one short-lived process), before
     * choosing how to start it. */
    public static HarnessInfo describeHarness(Path harnessBin, double timeoutS) {
        if (!Files.exists(harnessBin)) throw new HarnessError("harness launcher not found: " + harnessBin);
        String hint = " (is it built? cd harness-<name> && ./gradlew fatJar)";
        try {
            Process p = new ProcessBuilder(harnessBin.toString(), "--info")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try { p.getInputStream().transferTo(out); } catch (IOException ignored) { }
            });
            if (!p.waitFor((long) (timeoutS * 1000), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new HarnessError(harnessBin + " --info failed: timed out after " + timeoutS + " seconds" + hint);
            }
            reader.join();
            if (p.exitValue() != 0) {
                throw new HarnessError(harnessBin + " --info failed: exit status " + p.exitValue() + hint);
            }
            return HarnessInfo.fromJson(Json.parseObject(out.toString(StandardCharsets.UTF_8)));
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            throw new HarnessError(harnessBin + " --info failed: " + e.getMessage() + hint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessError(harnessBin + " --info interrupted", e);
        }
    }

    public static HarnessInfo describeHarness(Path harnessBin) {
        return describeHarness(harnessBin, 60.0);
    }

    public static HarnessInfo describeHarness() {
        return describeHarness(Oxpecker.harnessBin());
    }

    // -- requests -------------------------------------------------------------------------

    /** Sends one request line and reads one response line. */
    public Map<String, Object> request(Map<String, Object> req) {
        if (proc == null) start();
        Process p = proc;
        String line = Json.line(req);
        try {
            stdin.write(line + "\n");
            stdin.flush();
        } catch (IOException e) {
            throw new HarnessError("harness died while sending: " + e.getMessage(), e);
        }
        // A hard deadline beyond anything the harness's own watchdog should allow.
        ScheduledFuture<?> timer = TIMERS.schedule(this::kill, (long) (hardTimeoutS * 1000), TimeUnit.MILLISECONDS);
        String resp;
        try {
            resp = readLine(stdout);
        } catch (IOException e) {
            resp = "";
        } finally {
            timer.cancel(false);
        }
        if (resp.isEmpty()) throw new HarnessError("harness died (no response)");
        try {
            return Json.parseObject(resp);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new HarnessError("bad response from harness: " + Py.repr(Py.head(resp, 200)), e);
        }
    }

    /** A line including its {@code \n}, or what is left before EOF ("" at EOF). */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (b == '\n') break;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private void kill() {
        Process p = proc;
        if (p != null) p.destroyForcibly();
    }

    /**
     * Runs one case in every mode (by default both, or the pinned one). If the JVM dies or asks
     * to be restarted, the missing modes are reported as crash/timeout and the JVM is restarted.
     * {@code coverageHits} asks for every probe the run hit (Outcome.hitProbes), for corpus
     * minimisation.
     */
    @Override
    public RunResult run(String source, RunOptions opt) {
        if (opt.comparators() != null) throw new IllegalArgumentException("a HarnessClient has no comparators");
        List<String> modes = opt.modes() != null ? opt.modes() : mode != null ? List.of(mode) : DEFAULT_MODES;
        String caseId = opt.id() != null && !opt.id().isEmpty() ? opt.id() : newId();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("id", caseId);
        req.put("source", source);
        req.put("modes", modes);
        req.put("languageVersion", opt.languageVersion());
        req.put("strict", opt.strict());
        req.put("instructionBudget", opt.instructionBudget());
        req.put("wallMs", opt.wallMs());
        req.put("seed", opt.seed());
        req.put("sourceName", opt.sourceName());
        // Auto-detected unless stated: only scripts that pause need the continuation runner.
        req.put("continuations", opt.continuations() == null
                ? source.contains("__pause") || source.contains("Continuation") : opt.continuations());
        req.put("coverageBucket", opt.coverageBucket());
        if (opt.coverageNewIds()) req.put("coverageNewIds", true);
        if (opt.coverageRequire() != null) req.put("coverageRequire", opt.coverageRequire());
        if (opt.coverageHits()) req.put("coverageHits", true);
        if (casesSinceStart >= recycleEvery) restart();
        casesSinceStart++;
        boolean restarted = false;
        Map<String, Object> resp;
        try {
            resp = request(req);
        } catch (HarnessError e) {
            if (!quiet) System.err.println("[harness] " + e.getMessage() + "; restarting JVM");
            restart();
            Map<String, Outcome> results = new LinkedHashMap<>();
            for (String m : modes) {
                Map<String, Object> ee = new LinkedHashMap<>();
                ee.put("clazz", "JVM");
                ee.put("message", e.getMessage());
                ee.put("stack", "");
                results.put(m, Outcome.of(m, "crash").reason("jvm-died").engineException(ee));
            }
            return new RunResult(caseId, results, true);
        }
        if (resp.containsKey("error")) throw new HarnessError(Py.str(resp.get("error")));
        Map<String, Outcome> results = new LinkedHashMap<>();
        Object rs = resp.get("results");
        if (rs instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) results.put((String) e.getKey(), Outcome.fromJava(Outcome.castMap(e.getValue())));
        }
        boolean watchdog = results.values().stream().anyMatch(o -> "timeout".equals(o.status) && "watchdog".equals(o.reason));
        for (String m : modes) {
            if (!results.containsKey(m)) {
                // The harness stopped before running this mode: after a watchdog timeout (then this
                // mode is simply unknown: count it as a timeout too, so the case is discarded rather
                // than reported) or after an OutOfMemoryError (a crash).
                if (watchdog) {
                    results.put(m, Outcome.of(m, "timeout").reason("not-run-after-watchdog"));
                } else {
                    Map<String, Object> ee = new LinkedHashMap<>();
                    ee.put("clazz", "JVM");
                    ee.put("message", "mode not run: harness asked for a restart (OutOfMemoryError in an earlier mode)");
                    ee.put("stack", "");
                    results.put(m, Outcome.of(m, "crash").reason("not-run").engineException(ee));
                }
            }
        }
        if (Py.truthy(resp.get("restart"))) {
            restarted = true;
            restart();
        }
        return new RunResult(caseId, results, restarted);
    }

    static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
