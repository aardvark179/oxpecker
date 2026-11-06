package dev.oxpecker.il;

import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.FLOAT;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NOTHING;
import static dev.oxpecker.il.Type.NULL;
import static dev.oxpecker.il.Type.NULLISH;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.STRING;
import static dev.oxpecker.il.Type.UNDEFINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** tests/il/test_types.py: subsumption, intersection, union, and their laws. */
class TypesTest {
    @Test void basicSubsumption() {
        assertTrue(INTEGER.is(NUMBER) && !NUMBER.is(INTEGER));
        assertTrue(NUMBER.is(ANY) && !ANY.is(NUMBER));
        assertTrue(NOTHING.is(INTEGER) && !INTEGER.is(NOTHING));
        assertTrue(NULL.is(NULLISH) && UNDEFINED.is(NULLISH) && !NULL.is(UNDEFINED));
        assertTrue(ARRAY.is(OBJECT) && !OBJECT.is(ARRAY));
        assertTrue(Type.object("Map").is(OBJECT) && !OBJECT.is(Type.object("Map")));
        assertFalse(Type.object("Map").is(Type.object("Set")));
    }

    @Test void propsAreLowerBounds() {
        Key k = new Key("x");
        assertTrue(Type.object(null, Set.of(k)).is(OBJECT));
        assertFalse(OBJECT.is(Type.object(null, Set.of(k))));
        assertTrue(OBJECT.withProp(k).is(Type.object(null, Set.of(k))));
        assertFalse(OBJECT.withProp(k).withoutProp(k).is(Type.object(null, Set.of(k))));
    }

    @Test void maybe() {
        assertTrue(NUMBER.mayBe(INTEGER) && INTEGER.mayBe(NUMBER));
        assertFalse(STRING.mayBe(NUMBER));
        assertTrue(ANY.mayBe(NULLISH));
        assertFalse(Type.object("Map").mayBe(Type.object("Set")));
        assertTrue(Type.object("Map").mayBe(OBJECT));
        assertTrue(NUMBER.mayNotBe(INTEGER) && !INTEGER.mayNotBe(NUMBER));
    }

    @Test void join() {
        assertEquals(NUMBER, INTEGER.join(FLOAT));
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), INTEGER.join(STRING).possible());
        assertEquals(STRING, NOTHING.join(STRING));
        assertEquals(STRING, STRING.join(NOTHING));
        assertNull(Type.object("Map").join(Type.object("Set")).group());
        assertEquals("Map", Type.object("Map").join(Type.object("Map")).group());
        Key a = new Key("a"), b = new Key("b");
        assertEquals(Set.of(a), Type.object(null, Set.of(a, b)).join(Type.object(null, Set.of(a))).props());
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), Type.array(INTEGER).join(Type.array(STRING)).element().possible());
        assertNull(Type.array(INTEGER).join(OBJECT).element());
    }

    @Test void signatures() {
        Signature s = new Signature(List.of(new Param(INTEGER), new Param(STRING, ParamMode.OPT), new Param(ANY, ParamMode.REST)), NUMBER);
        assertEquals(1, s.minArguments());
        assertTrue(s.hasRest());
        assertTrue(s.accepts(1) && s.accepts(10) && !s.accepts(0));
        assertFalse(new Signature(List.of(new Param(INTEGER)), ANY).accepts(2));
        assertThrows(IllegalArgumentException.class, () -> new Signature(List.of(new Param(ANY, ParamMode.REST), new Param(ANY)), ANY));
        assertThrows(IllegalArgumentException.class, () -> new Signature(List.of(new Param(ANY, ParamMode.OPT), new Param(ANY)), ANY));
        Type f = Type.function(s);
        assertTrue(f.is(FUNCTION) && !FUNCTION.is(f));
    }

    @Test void keys() {
        assertFalse(new Key("0", KeyKind.INDEX).isIdentifier());
        assertTrue(new Key("abc").isIdentifier() && !new Key("a-b").isIdentifier());
        assertThrows(IllegalArgumentException.class, () -> new Key("01", KeyKind.INDEX));
        assertThrows(IllegalArgumentException.class, () -> new Key("", KeyKind.ORDINARY));
        assertThrows(IllegalArgumentException.class, () -> new Key("x", KeyKind.LENGTH));
    }

    @Test void str() {
        assertEquals("any", ANY.toString());
        assertEquals("nothing", NOTHING.toString());
        assertEquals("integer|float", NUMBER.toString());
        assertTrue(ARRAY.toString().contains("Array"));
    }

    /** The Hypothesis property of the Python test, over seeded random draws of the same shape. */
    @Test void latticeLaws() {
        Random rnd = new Random(20261106);
        String[] groups = {null, "A", "B"};
        for (int n = 0; n < 5000; n++) {
            Type[] t = new Type[3];
            for (int j = 0; j < 3; j++) {
                int kinds = 0;
                int count = rnd.nextInt(5);
                for (int c = 0; c < count; c++) kinds |= Kind.values()[rnd.nextInt(Kind.values().length)].bit;
                t[j] = new Type(kinds, groups[rnd.nextInt(3)], null, null, Set.of());
            }
            Type a = t[0], b = t[1], c = t[2];
            assertTrue(a.is(a));
            assertTrue(a.is(a.join(b)) && b.is(a.join(b)));
            assertEquals(a.join(b), b.join(a));
            assertEquals(a.join(b).join(c), a.join(b.join(c)));
            assertTrue(a.is(ANY) && NOTHING.is(a));
            if (a.is(b) && b.is(a)) assertEquals(a.possible(), b.possible());
            if (a.is(b) && !a.isNothing()) assertTrue(a.mayBe(b));
        }
    }
}
