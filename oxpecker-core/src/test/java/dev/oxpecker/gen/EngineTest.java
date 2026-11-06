package dev.oxpecker.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EngineTest {
    /** A list of ints and a context-dependent pick from it (strategies.pick over a drawn pool). */
    static final Gen<List<Integer>> PROG = d -> {
        List<Integer> xs = d.lists(0, 10, e -> e.ints(0, 100));
        List<Integer> out = new ArrayList<>(xs);
        if (!xs.isEmpty()) out.add(d.pick(xs));
        if (d.maybe(0.3)) out.add(-1);
        return out;
    };

    @Test void freshIsDeterministicPerSeed() {
        assertEquals(Engine.fresh(PROG, 7).value(), Engine.fresh(PROG, 7).value());
        assertEquals(Engine.fresh(PROG, 7).choices(), Engine.fresh(PROG, 7).choices());
    }

    @Test void replayReproducesEveryDraw() {
        for (int seed = 0; seed < 200; seed++) {
            var drawn = Engine.fresh(PROG, seed);
            assertEquals(drawn.value(), Engine.replay(PROG, drawn.choices()), "seed " + seed);
            assertEquals(drawn.choices(), Engine.replayDrawn(PROG, drawn.choices()).choices());
        }
    }

    @Test void replayFailsWhenChoicesRunOutOrDoNotFit() {
        var drawn = Engine.fresh(PROG, 3);
        assertNull(Engine.replay(PROG, drawn.choices().prefix(0)));
        assertNull(Engine.replay(d -> d.ints(0, 3), Choices.of(9)));
        assertEquals(Integer.valueOf(2), Engine.<Integer>replay(d -> d.ints(0, 3), Choices.of(2, 99, 99)));
    }

    @Test void prefixThenFresh() {
        Gen<List<Integer>> steps = d -> {
            List<Integer> out = new ArrayList<>();
            while (out.size() < 5 && d.bool()) out.add(d.ints(0, 1000));
            return out;
        };
        var parent = Engine.fresh(steps, new Random(1));
        // extend: the parent's draws without its final `false`, a forced `true`, then fresh draws
        Choices kept = parent.choices().prefix(parent.choices().size() - 1);
        var child = Engine.withPrefix(steps, kept.concat(Choices.of(1)), new Random(2));
        assertNotNull(child);
        assertTrue(child.choices().startsWith(kept));
        assertEquals(parent.value(), child.value().subList(0, parent.value().size()));
        assertTrue(child.value().size() > parent.value().size());
        assertEquals(child.value(), Engine.replay(steps, child.choices()));
    }

    @Test void shrinkStartsFromTheRecordedExample() {
        int found = 0;
        for (int seed = 0; seed < 50; seed++) {
            var drawn = Engine.fresh(PROG, seed);
            if (drawn.value().stream().noneMatch(x -> x > 50)) continue;
            found++;
            var s = Engine.shrink(PROG, drawn.choices(), v -> v.stream().anyMatch(x -> x > 50), 500, List::size);
            assertNotNull(s, "seed " + seed);
            assertTrue(s.value().stream().anyMatch(x -> x > 50));
            assertTrue(s.value().size() <= 2, "seed " + seed + ": " + s.value());   // one element (+ its pick)
            assertEquals(s.value(), Engine.replay(PROG, s.choices()));
        }
        assertTrue(found > 10);
    }

    @Test void shrinkRespectsTheBudget() {
        var drawn = Engine.fresh(PROG, 11);
        int[] calls = {0};
        var s = Engine.shrink(PROG, drawn.choices(), v -> { calls[0]++; return !v.isEmpty(); }, 5, List::size);
        assertNotNull(s);
        assertTrue(calls[0] <= 5, "calls " + calls[0]);
        assertEquals(calls[0], s.calls());
    }

    @Test void shrinkOfAnExampleThatDoesNotReproduce() {
        var drawn = Engine.fresh(PROG, 4);
        assertNull(Engine.shrink(PROG, drawn.choices(), v -> false, 50, List::size));
    }

    @Test void rejectionIsNoValue() {
        Gen<Integer> g = d -> {
            int x = d.ints(0, 10);
            if (x % 2 == 1) Reject.reject();
            return x;
        };
        int rejected = 0;
        for (int seed = 0; seed < 50; seed++) if (Engine.fresh(g, seed) == null) rejected++;
        assertTrue(rejected > 5 && rejected < 45);
        assertNull(Engine.replay(g, Choices.of(3)));
        // a shrink candidate that rejects is not interesting: 8 shrinks to 4, 2 and 0 but never to an odd value
        var s = Engine.shrink(g, Choices.of(8), v -> true, 100, v -> v);
        assertEquals(0, s.value());
    }

    @Test void labelledRecursionShrinksToANestedStructure() {
        // a tree: depth-first lists of subtrees; interesting when it holds a 99 leaf
        @SuppressWarnings("unchecked") Gen<Object>[] tree = new Gen[1];
        tree[0] = d -> d.maybe(0.6) && d.ints(0, 3) < 3
                ? d.lists(0, 3, e -> e.draw("tree", tree[0]))
                : (Object) d.ints(0, 99);
        int shrunk = 0;
        for (int seed = 0; seed < 300 && shrunk < 5; seed++) {
            var drawn = Engine.fresh(d -> d.draw("tree", tree[0]), seed);
            if (!drawn.value().toString().contains("99")) continue;
            var s = Engine.shrink(d -> d.draw("tree", tree[0]), drawn.choices(),
                    v -> v.toString().contains("99"), 2000, v -> v.toString().length());
            assertNotNull(s);
            assertEquals("99", s.value().toString(), "seed " + seed + " from " + drawn.value());
            shrunk++;
        }
        assertTrue(shrunk > 0);
    }

    @Test void textAndFloats() {
        for (int seed = 0; seed < 100; seed++) {
            var t = Engine.fresh(d -> d.text(6), seed).value();
            assertTrue(t.codePointCount(0, t.length()) <= 6);
            assertTrue(t.chars().noneMatch(c -> Character.isSurrogate((char) c) && !isPaired(t)));
            double f = Engine.fresh(d -> d.floats(0.0, 1e6), seed).value();
            assertTrue(f >= 0.0 && f <= 1e6);
        }
    }

    private static boolean isPaired(String s) {
        return s.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF);
    }

    @Test void choicesEncodeRoundTrip() {
        Choices c = Choices.of(0, 1, -1, 127, 128, -10, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x10FFFF);
        assertEquals(c, Choices.decode(c.encode()));
        assertNull(Choices.decode("0a1b"));
        assertNull(Choices.decode(null));
        assertEquals(Choices.EMPTY, Choices.decode(Choices.EMPTY.encode()));
    }
}
