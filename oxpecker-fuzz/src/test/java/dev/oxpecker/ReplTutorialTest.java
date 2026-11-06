package dev.oxpecker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * docs/repl-tutorial.md runs: every {@code java} block is evaluated in order in one jshell session
 * on the test classpath, and must compile and run without an exception. The blocks of the section
 * "Run it in Rhino" need a built harness and run only in the slow suite.
 */
class ReplTutorialTest {
    static final Path DOC = Oxpecker.root().resolve("docs/repl-tutorial.md");
    static final Pattern BLOCK = Pattern.compile("^```java\\n(.*?)^```", Pattern.MULTILINE | Pattern.DOTALL);
    static final String HARNESS_SECTION = "## 8. Run it in Rhino";

    record Block(String code, boolean needsHarness) {}

    static List<Block> blocks() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        int harnessAt = doc.indexOf(HARNESS_SECTION);
        int harnessEnd = doc.indexOf("\n## ", harnessAt + 1);
        List<Block> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(doc);
        while (m.find()) out.add(new Block(m.group(1), m.start() > harnessAt && m.start() < harnessEnd));
        return out;
    }

    @Test void tutorialHasBlocks() throws IOException {
        List<Block> bs = blocks();
        assertTrue(bs.size() >= 10, "blocks " + bs.size());
        assertTrue(bs.stream().anyMatch(Block::needsHarness));
    }

    void runBlocks(boolean withHarness) throws IOException {
        try (JShell js = JShell.builder()
                .remoteVMOptions("-Doxpecker.root=" + Oxpecker.root(), "-Dfile.encoding=UTF-8")
                .out(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
                .build()) {
            js.addToClasspath(System.getProperty("java.class.path"));
            SourceCodeAnalysis sca = js.sourceCodeAnalysis();
            int k = 0;
            for (Block b : blocks()) {
                k++;
                if (b.needsHarness && !withHarness) continue;
                String rest = b.code;
                while (!rest.isBlank()) {
                    SourceCodeAnalysis.CompletionInfo ci = sca.analyzeCompletion(rest);
                    String source = ci.source() != null ? ci.source() : rest;
                    for (SnippetEvent e : js.eval(source)) {
                        if (e.exception() != null)
                            fail("block " + k + " threw " + e.exception() + "\n" + source);
                        if (e.status() == Snippet.Status.REJECTED) {
                            StringBuilder why = new StringBuilder();
                            js.diagnostics(e.snippet()).forEach((Diag d) -> why.append(d.getMessage(null)).append('\n'));
                            fail("block " + k + " does not compile:\n" + why + source);
                        }
                    }
                    if (ci.source() == null) break;
                    rest = ci.remaining();
                }
            }
        }
    }

    @Test void tutorialBlocksRunInOrder() throws IOException { runBlocks(false); }

    @Test @Tag("slow")
    void tutorialBlocksRunInOrderWithTheHarness() throws IOException {
        Assumptions.assumeTrue(Files.exists(Oxpecker.harnessBin()), "no harness launcher");
        runBlocks(true);
    }
}
