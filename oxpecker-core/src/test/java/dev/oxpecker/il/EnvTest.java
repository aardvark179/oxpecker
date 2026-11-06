package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Registries built from the capability table (tests/il/test_environment.py): guard test 7 (the
 * overlay agrees with the probe), pools ordered simplest-first, host entries present.
 */
class EnvTest {
    @Test void builtinsComeFromTheTableWithTypes() {
        Builtin map = Env.builtin("MAP");
        assertEquals("Map", map.name());
        assertEquals("Map", map.constructs().group());
        assertEquals("TypedArray", Env.builtin("INT32_ARRAY").constructs().group());
        assertEquals("Math", Env.builtin("MATH").type().group());
        assertNull(Env.builtin("MATH").constructs());
        assertTrue(Env.builtin("NAN").type().is(Type.NUMBER));
        assertEquals(Type.UNDEFINED.possible(), Env.builtin("UNDEFINED").type().possible());
        assertTrue(Env.builtin("PARSE_INT").calls().is(Type.NUMBER));
        assertEquals(Type.STRING, Env.builtin("STRING").calls());
        assertTrue(Env.BUILTINS.size() > 50);
    }

    @Test void hostHooksAreRegisteredByName() {
        assertEquals("J", Env.builtin("J").type().group());
        assertEquals("__pause", Env.builtin("PAUSE").name());
        assertEquals(Type.ANY, Env.builtin("PAUSE").calls());
        assertNotNull(Env.builtin("CONTINUATION").constructs());
        assertEquals("eval", Env.builtin("EVAL").name());
        assertSame(Env.builtin("MAP"), Env.builtinNamed("Map"));
        assertTrue(Env.BUILTIN_POOL.stream().noneMatch(b -> Set.of("J", "__pause", "Continuation").contains(b.name())));
    }

    @Test void methodsFromPrototypesAndStatics() {
        Method push = Env.method("ARRAY_PUSH");
        assertEquals("Array", push.receiver());
        assertEquals(Type.INTEGER, push.returns());
        Method imul = Env.method("MATH_IMUL");
        assertEquals("Math", imul.receiver());
        assertEquals(Type.INTEGER, imul.returns());
        assertEquals(2, imul.arity());
        assertEquals("ArrayCtor", Env.method("ARRAY_CTOR_IS_ARRAY").receiver());
        assertEquals(List.of(Type.NUMBER), Env.method("STRING_CHAR_CODE_AT").params());
        assertEquals("J", Env.method("J_TYPE_OF").receiver());
        assertEquals(Type.STRING, Env.method("J_TYPE_OF").returns());
        assertEquals(List.of(Type.BIGINT), Env.method("J_TAKES_BIG_INTEGER").params());
        assertEquals("Reflect", Env.method("REFLECT_GET").receiver());
        assertTrue(Env.methodsOf("Array").stream().anyMatch(m -> m.name().equals("push")));
        Key length = new Key("length", KeyKind.LENGTH);
        assertTrue(Env.propsOf("Array").contains(length) || Env.KEYS.containsValue(length));
    }

    @Test void keysHaveKinds() {
        assertEquals(KeyKind.LENGTH, Env.k("LENGTH").kind());
        assertEquals(KeyKind.PROTO, Env.k("PROTO").kind());
        assertEquals("__proto__", Env.k("PROTO").name());
        assertEquals(KeyKind.INDEX, Env.k("INDEX_0").kind());
        assertEquals(KeyKind.WELL_KNOWN, Env.k("TO_STRING").kind());
        assertEquals(KeyKind.WELL_KNOWN, Env.k("CONSTRUCTOR").kind());
        assertEquals(KeyKind.ORDINARY, Env.k("A").kind());
        assertEquals("!", Env.k("BANG").name());
        assertTrue(Env.ORDINARY_KEYS.stream().allMatch(k -> k.kind() == KeyKind.ORDINARY));
        assertTrue(Env.INDEX_KEYS.stream().allMatch(k -> k.kind() == KeyKind.INDEX));
    }

    @Test void overlayAgreesWithTheProbeTable() {
        Set<String> missing = new TreeSet<>(Env.overlayKeys());
        missing.removeAll(Env.tableMethodKeys());
        assertTrue(missing.isEmpty(), "overlay names methods the probe does not see: " + missing);
    }

    @Test void poolsAreOrderedSimplestFirst() {
        assertEquals(0L, Env.INTERESTING_INTEGERS.get(0));
        assertEquals(1L, Env.INTERESTING_INTEGERS.get(1));
        assertEquals(0.0, Env.INTERESTING_FLOATS.get(0));
        assertEquals("", Env.INTERESTING_STRINGS.get(0));
        assertTrue(Env.INTERESTING_INTEGERS.stream().allMatch(v -> v >= 0) && Env.INTERESTING_FLOATS.stream().allMatch(v -> v >= 0));
        assertEquals("a", Env.REGEXP_PATTERNS.get(0).pattern());
        assertTrue(Env.TYPED_ARRAY_CONSTRUCTORS.size() >= 10 && Env.ERROR_CONSTRUCTORS.size() >= 7);
    }

    @Test void attrNames() {
        assertEquals("INT32_ARRAY", Env.attrName("Int32Array"));
        assertEquals("DECODE_URI_COMPONENT", Env.attrName("decodeURIComponent"));
        assertEquals("IS_NAN", Env.attrName("isNaN"));
        assertEquals("UINT8_CLAMPED_ARRAY", Env.attrName("Uint8ClampedArray"));
        assertEquals("CHAR_CODE_AT", Env.attrName("charCodeAt"));
        assertEquals("URI_ERROR", Env.attrName("URIError"));
    }

    @Test void syntaxSupportIsReadFromTheTable() {
        assertTrue(Env.SYNTAX_SUPPORTED.contains("arrow"));
        assertFalse(Env.SYNTAX_SUPPORTED.contains("class"));
        assertFalse(Env.PROBED_RHINO.isEmpty());
    }
}
