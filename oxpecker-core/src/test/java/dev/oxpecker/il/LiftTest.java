package dev.oxpecker.il;

import static dev.oxpecker.il.Builder.fn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.il.Ops.*;
import dev.oxpecker.il.Patterns.ArrayElement;
import dev.oxpecker.il.Patterns.ArrayPattern;
import dev.oxpecker.il.Patterns.Binding;
import dev.oxpecker.il.Patterns.ObjectPattern;
import dev.oxpecker.il.Patterns.ObjectProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * tests/il/test_lift.py: inlining policy (FuzzIL's worked examples), per-instruction lift choices,
 * Rhino spellings, every construct lifts, probes and comments, code strings.
 */
class LiftTest {
    static final LiftOptions PLAIN = new LiftOptions(false, false, false, 6);

    static Key k(String attr) { return Env.k(attr); }

    static String js(Builder b) { return Lifter.lift(b.finish(), PLAIN); }

    static List<String> lines(String text) { return Arrays.stream(text.split("\n")).filter(l -> !l.isBlank()).toList(); }

    /** Lines with the numbers in generated names replaced by '#'. */
    static List<String> norm(String text) { return lines(text).stream().map(l -> l.replaceAll("(?<=[A-Za-z])\\d+", "#")).toList(); }

    // ------------------------------------------------------------------ inlining (lifter-notes §1.9)
    @Test void exampleAEverythingInlinesIntoOneCall() {
        Builder b = new Builder();
        int f = b.builtin(Env.builtin("PARSE_INT"));
        int x = b.binary(b.loadInt(3), b.loadInt(4), BinaryOperator.ADD);
        b.callFunction(f, List.of(x));
        assertEquals(List.of("parseInt(3 + 4);"), lines(js(b)));
    }

    @Test void exampleBTwoUsesAreDeclaredAndLiteralsAreDuplicated() {
        Builder b = new Builder();
        int f = b.builtin(Env.builtin("PARSE_INT"));
        int three = b.loadInt(3);
        int x = b.binary(three, b.loadInt(4), BinaryOperator.ADD);
        b.callFunction(f, List.of(x));
        b.callFunction(f, List.of(x));
        b.callFunction(f, List.of(three));
        assertEquals(List.of("var n3 = 3 + 4;", "parseInt(n3);", "parseInt(n3);", "parseInt(3);"), lines(js(b)));
    }

    @Test void exampleCAnInterveningStatementFlushesThePendingExpression() {
        Builder b = new Builder();
        int f = b.builtin(Env.builtin("PARSE_INT"));
        int g = b.builtin(Env.builtin("PARSE_FLOAT"));
        int o = b.declare(b.builtin(Env.builtin("MATH")));
        int v1 = b.callFunction(f, List.of(b.loadString("1")));
        b.setProperty(o, k("X"), b.loadInt(5));
        b.callFunction(g, List.of(v1));
        assertEquals(List.of("var o3 = Math;", "var n5 = parseInt(\"1\");", "o3.x = 5;", "parseFloat(n5);"), lines(js(b)));
    }

    @Test void effectfulExpressionsInlineOnlyInOrder() {
        Builder b = new Builder();
        int f = b.builtin(Env.builtin("PARSE_INT"));
        int g = b.builtin(Env.builtin("PARSE_FLOAT"));
        int a = b.callFunction(f, List.of(b.loadString("1")));
        int c = b.callFunction(g, List.of(b.loadString("2")));
        b.createArray(List.of(a, c));                    // in order: both inline
        int a2 = b.callFunction(f, List.of(b.loadString("3")));
        int c2 = b.callFunction(g, List.of(b.loadString("4")));
        b.createArray(List.of(c2, a2));                  // reversed: both are flushed first
        var text = lines(js(b));
        assertEquals("[parseInt(\"1\"), parseFloat(\"2\")];", text.get(0));
        assertEquals("var n8 = parseInt(\"3\");", text.get(1));
        assertEquals("var n10 = parseFloat(\"4\");", text.get(2));
        assertEquals("[n10, n8];", text.get(3));
    }

    @Test void liftChoiceKeepsAValueAsItsOwnStatement() {
        Builder b = new Builder();
        int x = b.emit(new LoadInteger(1), Lift.CONST).output();
        int y = b.emit(new BinaryOperation(BinaryOperator.MUL), List.of(x, x), Lift.LET).output();
        b.typeof(y);
        assertEquals(List.of("const n0 = 1;", "let n1 = n0 * n0;", "typeof n1;"), lines(js(b)));
    }

    @Test void declaredVariablesAreStatementsAndNeverInlined() {
        Builder b = new Builder();
        int n = b.declare(b.loadInt(1), DeclarationKind.LET);
        b.reassign(n, b.binary(n, b.loadInt(2), BinaryOperator.ADD));
        b.update(n, b.loadInt(3), BinaryOperator.MUL);
        b.unary(UnaryOperator.POST_INC, n);
        b.unary(UnaryOperator.PRE_DEC, n);
        assertEquals(List.of("let n1 = 1;", "n1 = n1 + 2;", "n1 *= 3;", "n1++;", "--n1;"), lines(js(b)));
    }

    @Test void constructsAreNeverInlinedAndUnusedEffectfulValuesBecomeStatements() {
        Builder b = new Builder();
        int m = b.construct(b.builtin(Env.builtin("MAP")));
        b.callMethod(m, Env.method("MAP_SET"), List.of(b.loadInt(1), b.loadInt(2)));
        assertEquals(List.of("var o1 = new Map();", "o1.set(1, 2);"), lines(js(b)));
    }

    // ------------------------------------------------------------------ spellings
    @Test void numberSpellings() {
        assertEquals("0xff", Lifter.spellInteger(255, Radix.HEX));
        assertEquals("0o10", Lifter.spellInteger(8, Radix.OCT));
        assertEquals("0b101", Lifter.spellInteger(5, Radix.BIN));
        assertEquals("1e+21", Lifter.spellFloat(1e21));
        assertEquals("0.1", Lifter.spellFloat(0.1));
        assertEquals("1.0", Lifter.spellFloat(1.0));
        assertEquals("1.5e3", Lifter.spellFloat(1500.0, FloatStyle.EXPONENT));
        assertEquals("5e-324", Lifter.spellFloat(5e-324));
        Builder b = new Builder();
        b.typeof(b.unary(UnaryOperator.MINUS, b.loadInt(0)));
        b.typeof(b.unary(UnaryOperator.MINUS, b.unary(UnaryOperator.MINUS, b.loadInt(1))));
        b.typeof(b.binary(b.unary(UnaryOperator.MINUS, b.loadInt(2)), b.loadInt(2), BinaryOperator.EXP));
        b.typeof(b.loadBigInt(5));
        // same-precedence right-associative unaries are bracketed on the left (Expression.swift:69-80)
        assertEquals(List.of("typeof (-0);", "typeof (-(-1));", "typeof ((-2) ** 2);", "typeof 5n;"), lines(js(b)));
    }

    @Test void stringEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\"", Lifter.spellString("a\"b\\c\n"));
        assertEquals("\"\\ud800\"", Lifter.spellString("\uD800"));
        assertEquals("\"\\u2028é\"", Lifter.spellString("\u2028é"));
    }

    @Test void precedenceAndBrackets() {
        Builder b = new Builder();
        int x = b.loadInt(1), y = b.loadInt(2), z = b.loadInt(3);
        int s = b.binary(b.binary(x, y, BinaryOperator.ADD), z, BinaryOperator.MUL);     // nested binaries are always bracketed
        b.typeof(s);
        b.callMethod(b.loadInt(3), Env.method("NUMBER_TO_STRING"), List.of());            // number literal receiver
        b.typeof(b.ternary(b.compare(x, y, CompareOperator.LESS), x, y));
        b.typeof(b.unary(UnaryOperator.LOGICAL_NOT, b.compare(x, y, CompareOperator.EQUAL)));
        assertEquals(List.of("typeof ((1 + 2) * 3);", "(3).toString();", "typeof (1 < 2 ? 1 : 2);", "typeof (!(1 == 2));"), lines(js(b)));
    }

    @Test void keysAndMembers() {
        Builder b = new Builder();
        int o = b.declare(b.builtin(Env.builtin("MATH")));
        b.typeof(b.getProperty(o, k("PI")));
        b.typeof(b.getProperty(o, new Key("a-b")));
        b.typeof(b.getProperty(o, new Key("3", KeyKind.INDEX)));
        b.typeof(b.getProperty(o, k("A"), true));
        b.typeof(b.getElement(o, -1));
        b.setComputed(o, b.loadString("k"), b.loadInt(1));
        b.typeof(b.deleteProperty(o, k("X")));
        assertEquals(List.of("var o1 = Math;", "typeof o1.PI;", "typeof o1[\"a-b\"];", "typeof o1[3];", "typeof o1?.a;",
                "typeof o1[-1];", "o1[\"k\"] = 1;", "typeof (delete o1.x);"), lines(js(b)));
    }

    // ------------------------------------------------------------------ constructs
    @Test void functionsDeclarationsAndExpressions() {
        Builder b = new Builder();
        int f;
        try (var fb = b.plainFunction(new Parameters(2, true), fn().named(true))) {
            f = fb.f();
            b.doReturn(b.getProperty(fb.arg(1), k("LENGTH")));
        }
        int seven = b.loadInt(7);
        try (var g = b.plainFunction(new Parameters(1, false, List.of(0), List.of()), fn().defaults(seven))) {
            try (var h = b.plainFunction(fn().named(true))) {        // nested named: still a statement (function body, depth 0)
                b.doReturn();
            }
            try (var br = b.if_(b.loadBool(true))) {
                try (var kk = b.plainFunction(fn().named(true))) {   // inside a block: expression form
                    // empty
                }
            }
        }
        try (var a = b.arrowFunction(new Parameters(1))) {
            b.doReturn(a.arg(0));
        }
        try (var gen = b.generatorFunction(fn().named(true).strict(true))) {
            b.yield_(b.loadInt(1));
            b.yieldEach(b.createArray());
        }
        b.callFunction(f, List.of(b.loadInt(1), b.loadInt(2)));
        assertEquals(List.of(
                "function f#(p#, ...p#) {", "    return p#.length;", "}",
                "var f# = function (p# = 7) {",
                "    function f#() {", "        return;", "    }",
                "    if (true) {", "        var f# = function () {", "        };", "    }",
                "};",
                "var f# = (p#) => {", "    return p#;", "};",
                "function* f#() {", "    \"use strict\";", "    yield 1;", "    yield* [];", "}",
                "f#(1, 2);"), norm(js(b)));
    }

    @Test void objectLiteralForms() {
        Builder b = new Builder();
        int one = b.loadInt(1);
        var o = b.objectLiteral();
        try (o) {
            o.addProperty(k("A"), one);
            o.addElement(2, one);
        }
        b.typeof(o.result());
        int key = b.loadString("k");
        int proto = b.loadNull();
        var o2 = b.objectLiteral();
        try (o2) {
            o2.addComputed(key, one);
            o2.setPrototype(proto);
            try (var m = o2.method(k("M"), new Parameters(1))) {
                b.doReturn(b.binary(b.getSuperProperty(k("A")), m.arg(0), BinaryOperator.ADD));
            }
            try (var m = o2.getter(k("G"))) {
                b.doReturn(m.this_());
            }
            try (var m = o2.setter(k("G"))) {
                b.setProperty(m.this_(), k("B"), m.value());
            }
        }
        b.callMethod(o2.result(), Env.method("OBJECT_HAS_OWN_PROPERTY"), List.of(b.loadString("a")));
        var o3 = b.objectLiteral();
        try (o3) {
            // empty
        }
        b.typeof(o3.result());
        assertEquals(List.of(
                "typeof { a: 1, 2: 1 };",
                "var o# = {", "    [\"k\"]: 1,", "    __proto__: null,",
                "    m(p#) {", "        return super.a + p#;", "    },",
                "    get g() {", "        return this;", "    },",
                "    set g(p#) {", "        this.b = p#;", "    },",
                "};",
                "o#.hasOwnProperty(\"a\");",
                "typeof {};"), norm(js(b)));
    }

    @Test void loopsAndControlFlow() {
        Builder b = new Builder();
        Scenarios.control(b);
        assertEquals(List.of(
                "var n# = 0;",
                "L#: while (n# < 3) {", "    n#++;", "    break L#;", "}",
                "do {", "    continue;", "} while (false);",
                "for (let i# = 0; i# < 2; i#++) {", "    n# += i#;", "}",
                "for (const i# of [n#]) {", "    typeof i#;", "}",
                "L#: for (var i# in Math) {", "    break L#;", "}",
                "L#: for (let i# = 0; i# < 5; i#++) {", "    if (!(i# == 2)) {", "        continue L#;", "    } else {",
                "        break;", "    }", "}",
                "try {", "    throw \"x\";", "} catch (e#) {", "    typeof e#;", "} finally {", "    typeof n#;", "}",
                "switch (n#) {", "    case 1:", "        break;", "        break;", "    case 2:", "        typeof n#;", "    default:",
                "        typeof n#;", "        break;", "}",
                "L#: {", "    break L#;", "}",
                "with (Math) {", "    typeof n#;", "}"), norm(js(b)));
    }

    @Test void destructuring() {
        Builder b = new Builder();
        int arr = b.createIntArray(List.of(1L, 2L, 3L));
        List<Integer> ar = b.destruct(arr, new ArrayPattern(List.of(new ArrayElement(new Binding()), new ArrayElement(null)), new Binding()));
        int rest = ar.get(1);
        int o = b.declare(b.builtin(Env.builtin("MATH")));
        var pat = new ObjectPattern(List.of(new ObjectProperty(k("PI"), new Binding()), new ObjectProperty(null, new Binding(), true)), true);
        b.destruct(o, pat, DeclarationKind.CONST, List.of(b.loadString("E"), b.loadInt(0)));
        b.destructAndReassign(arr, new ArrayPattern(List.of(new ArrayElement(new Binding()))), List.of(rest));
        var text = lines(js(b));
        assertEquals("var arr0 = [1, 2, 3];", text.get(0));
        assertEquals("var [n1, , ...v2] = arr0;", text.get(1));        // v2: later reassigned an integer
        assertEquals("const {PI: v7, [\"E\"]: v8 = 0, ...o9} = o4;", text.get(3));
        assertEquals("[v2] = arr0;", text.get(4));
    }

    @Test void probesCommentsAndStrict() {
        Builder b = new Builder();
        Scenarios.probes(b);
        var code = b.finish();
        String text = Lifter.lift(code, new LiftOptions(true, true, true, 6));
        assertEquals(List.of("\"use strict\";", "// [int]", "var n1 = 1;", "__probe(\"step\", n1);", "// [string]", "var s2 = \"x\";",
                "__probe(\"end\", s2, n1);"), lines(text));
        assertEquals(List.of("var n1 = 1;", "var s2 = \"x\";"), lines(Lifter.lift(code, PLAIN)));
    }

    @Test void codeStringsAreLiftedAndEscaped() {
        Builder b = new Builder();
        Scenarios.codeStrings(b);
        assertEquals(List.of("var v1 = 1;", "var s2 = \"v1 = \\\"a\\\\\\\"b\\\";\\n\";", "eval(s2);"), lines(js(b)));
    }

    /** A structural guard: the dispatcher names every operation class. */
    @Test void everyOperationClassHasALiftRule() throws Exception {
        String src = Files.readString(Path.of(System.getProperty("oxpecker.root"),
                "oxpecker-core/src/main/java/dev/oxpecker/il/Lifter.java"));
        for (Class<?> cls : Ops.ALL)
            assertTrue(src.contains(" " + cls.getSimpleName() + " ") || src.contains(" " + cls.getSimpleName() + ")")
                    || src.contains("instanceof " + cls.getSimpleName()) || src.contains("instanceof Ops." + cls.getSimpleName())
                    || Ops.FUNCTION_BEGINS.contains(cls), cls.getSimpleName());
    }

    @Test void configurePropertyAndTemplatesAndRegexps() {
        Builder b = new Builder();
        Scenarios.configure(b);
        var text = lines(js(b));
        assertEquals("Object.defineProperty(o1, \"x\", { configurable: true, enumerable: true, get: f2 });", text.get(4));
        assertEquals("Object.defineProperty(o1, 0, { writable: true, value: 2 });", text.get(5));
        assertEquals("typeof `a${1}b\\`\\${c${2}`;", text.get(6));
        assertEquals("typeof /a+/gi;", text.get(7));
    }

    /** Port-specific: Python's f"{x:.17e}" (18 correctly rounded digits), which Java's %e does not reproduce. */
    @Test void exponentStyleMatchesPythonFormatting() {
        assertEquals("1.00000000000000006e-1", Lifter.spellFloat(0.1, FloatStyle.EXPONENT));
        assertEquals("1.00000000000000005e300", Lifter.spellFloat(1e300, FloatStyle.EXPONENT));
        assertEquals("0e0", Lifter.spellFloat(0.0, FloatStyle.EXPONENT));
        assertEquals("1e0", Lifter.spellFloat(1.0, FloatStyle.EXPONENT));
        assertEquals("[1.5, -0.0, NaN, Infinity, -Infinity]", "[" + String.join(", ", Lifter.signedFloat(1.5), Lifter.signedFloat(-0.0),
                Lifter.signedFloat(Double.NaN), Lifter.signedFloat(Double.POSITIVE_INFINITY), Lifter.signedFloat(Double.NEGATIVE_INFINITY)) + "]");
    }
}
