package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The golden twenty (design §10 item 5; tests/il/test_golden.py): the lifted source of twenty
 * seeded programs, checked in under src/test/resources/golden/. A change in any generator, the
 * lifter, the strategies or the Draw layer changes them; the diff is the review. Regenerate
 * deliberately with
 *
 * <pre>OXPECKER_UPDATE_GOLDEN=1 ./gradlew :oxpecker-core:test --tests 'dev.oxpecker.il.GoldenTest'</pre>
 *
 * (They are not the Python port's golden files, parity/golden-python, which the lifter parity test reads:
 * Hypothesis and jetCheck draw differently from the same seed.)
 */
class GoldenTest {
    static final Path GOLDEN = Path.of(System.getProperty("oxpecker.root", "..")).resolve("oxpecker-core/src/test/resources/golden");
    static final GenOptions OPTIONS = GenOptions.builder().maxStatements(8).maxDepth(3).build();

    static IntStream seeds() { return IntStream.range(0, 20); }

    @ParameterizedTest
    @MethodSource("seeds")
    void goldenLiftedProgram(int seed) throws IOException {
        Path path = GOLDEN.resolve(String.format("seed_%02d.js", seed));
        String source = Strategies.generate(seed, OPTIONS).source();
        if (System.getenv("OXPECKER_UPDATE_GOLDEN") != null) {
            Files.createDirectories(GOLDEN);
            Files.writeString(path, source, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(path), "no golden file for seed " + seed + "; run with OXPECKER_UPDATE_GOLDEN=1");
        assertEquals(Files.readString(path, StandardCharsets.UTF_8), source, "seed " + seed + " lifts differently from "
                + path.getFileName() + "; if the change is intended, regenerate with OXPECKER_UPDATE_GOLDEN=1 and review the diff");
    }
}
