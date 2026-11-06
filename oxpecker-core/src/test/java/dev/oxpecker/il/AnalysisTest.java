package dev.oxpecker.il;

import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.BOOLEAN;
import static dev.oxpecker.il.Type.FLOAT;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.PRIMITIVE;
import static dev.oxpecker.il.Type.STRING;
import static dev.oxpecker.il.Type.UNDEFINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.il.Ops.*;
import dev.oxpecker.il.Patterns.ArrayElement;
import dev.oxpecker.il.Patterns.ArrayPattern;
import dev.oxpecker.il.Patterns.Binding;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** tests/il/test_analysis.py: scopes, def-use, the typer (branch joins, two-pass loops, try/catch), the Rhino model, verify(). */
class AnalysisTest {
    static Instruction I(Operation op, int... inouts) { return new Instruction(op, inouts); }

    static List<Instruction> prog(Instruction... instrs) {
        List<Instruction> out = new ArrayList<>();
        for (int n = 0; n < instrs.length; n++) out.add(instrs[n].withIndex(n));
        return out;
    }

    static Key k(String attr) { return Env.k(attr); }

    static final BinaryOperator ADD = BinaryOperator.ADD, MUL = BinaryOperator.MUL;

    // ------------------------------------------------------------------ structure
    @Test void storageKindsAndVisibility() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),
                I(new Declare(DeclarationKind.LET), 0, 2),
                I(new BeginPlainFunction(new Parameters(1)), 3, 4),
                I(new Declare(DeclarationKind.VAR), 4, 5),
                I(new Declare(DeclarationKind.CONST), 4, 6),
                I(new BeginIf(), 4, 7),
                I(new Declare(DeclarationKind.LET), 4, 8),
                I(new EndIf()),
                I(new BeginTry()),
                I(new BeginCatch(), 9),
                I(new EndTryCatchFinally()),
                I(new EndPlainFunction()),
                I(new BeginRepeatLoop(2), 10, 11),
                I(new EndRepeatLoop()));
        Analysis an = Analysis.analyse(p);
        var s = an.storage();
        assertSame(Storage.TEMP, s.get(0));
        assertSame(Storage.TOP_VAR, s.get(1));
        assertSame(Storage.TOP_LET, s.get(2));
        assertSame(Storage.FUNCTION, s.get(3));
        assertSame(Storage.PARAM, s.get(4));
        assertSame(Storage.LOCAL_VAR, s.get(5));
        assertSame(Storage.LOCAL_CONST, s.get(6));
        assertSame(Storage.BLOCK_LET, s.get(8));
        assertSame(Storage.LABEL, s.get(7));
        assertSame(Storage.CATCH, s.get(9));
        assertSame(Storage.COUNTER, s.get(10));
        assertSame(Storage.LABEL, s.get(11));
        assertEquals(Set.of(1, 2, 3, 4, 5, 8, 9), an.assignable());
        assertEquals(List.of(4, 3, 2, 1, 0), an.visibleAt().get(4));       // most recent first; the function sees its own variable
        assertEquals(List.of(3, 2, 1, 0), an.visibleAt().get(13));
        assertEquals(3, an.functionOf().get(4));
        assertEquals(Analysis.TOP_LEVEL, an.functionOf().get(13));
        assertEquals(4, an.defs().get(5));
        assertEquals(List.of(4, 5, 6, 7), an.uses().get(4));
        assertEquals(List.of(3, 2, 1), an.declaredVisibleAt(13));
    }

    @Test void defUseCountsReassignments() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),
                I(new Reassign(), 1, 0),
                I(new Update(ADD), 1, 0),
                I(new UnaryOperation(UnaryOperator.POST_INC), 1, 2));
        Analysis an = Analysis.analyse(p);
        assertEquals(List.of(1, 2, 3, 4), an.assignments().get(1));
        assertEquals(4, an.numAssignments(1));
        assertEquals(1, an.numAssignments(0));
        assertEquals(3, an.numUses(0));
    }

    // ------------------------------------------------------------------ typer
    @Test void literalAndOperatorTypes() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new LoadFloat(1.5), 1),
                I(new LoadString("a"), 2),
                I(new BinaryOperation(ADD), 0, 1, 3),
                I(new BinaryOperation(ADD), 0, 2, 4),
                I(new BinaryOperation(BinaryOperator.BIT_OR), 1, 0, 5),
                I(new Compare(CompareOperator.LESS), 0, 1, 6),
                I(new TypeOf(), 2, 7),
                I(new LoadBigInt(BigInteger.ONE), 8),
                I(new BinaryOperation(MUL), 8, 8, 9),
                I(new UnaryOperation(UnaryOperator.MINUS), 8, 10),
                I(new TernaryOperation(), 6, 0, 2, 11));
        var ty = Analysis.analyse(p).types();
        assertEquals(INTEGER, ty.get(0));
        assertEquals(FLOAT, ty.get(1));
        assertEquals(STRING, ty.get(2));
        assertEquals(NUMBER, ty.get(3));
        assertEquals(STRING, ty.get(4));
        assertEquals(INTEGER, ty.get(5));
        assertEquals(BOOLEAN, ty.get(6));
        assertEquals(STRING, ty.get(7));
        assertEquals(BIGINT, ty.get(9));
        assertEquals(BIGINT, ty.get(10));
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), ty.get(11).possible());
        assertTrue(Analysis.binaryResult(ADD, ANY, ANY).is(PRIMITIVE.join(BIGINT)));
    }

    @Test void reassignIsAStrongUpdateAndBranchesJoin() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),            // n: integer
                I(new LoadString("s"), 2),
                I(new LoadBoolean(true), 3),
                I(new BeginIf(), 3, 4),
                I(new Reassign(), 1, 2),                             // n = "s" in the then branch
                I(new TypeOf(), 1, 5),
                I(new EndIf()),
                I(new TypeOf(), 1, 6));
        Analysis an = Analysis.analyse(p);
        assertEquals(STRING, an.typeAt(6, 1));                                          // inside: strong update
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.typeAt(8, 1).possible());    // after: joined with the untaken branch
    }

    @Test void ifElseJoinsBothBranchesOnly() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),
                I(new LoadString("s"), 2),
                I(new LoadBoolean(true), 3),
                I(new BeginIf(), 3, 4),
                I(new Reassign(), 1, 2),
                I(new BeginElse(), 5),
                I(new Reassign(), 1, 3),
                I(new EndIf()),
                I(new TypeOf(), 1, 6));
        assertEquals(Kind.of(Kind.STRING, Kind.BOOLEAN), Analysis.analyse(p).typeAt(9, 1).possible());   // not integer: both branches assign
    }

    @Test void loopBodyIsAnalysedTwice() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),
                I(new LoadString("s"), 2),
                I(new BeginRepeatLoop(3), 3, 4),
                I(new TypeOf(), 1, 5),                   // sees integer on the first pass, integer|string on the second
                I(new Reassign(), 1, 2),
                I(new EndRepeatLoop()),
                I(new TypeOf(), 1, 6));
        Analysis an = Analysis.analyse(p);
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.typeAt(4, 1).possible());
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.typeAt(7, 1).possible());
        assertEquals(INTEGER, an.types().get(3));
    }

    @Test void catchEntryJoinsEverythingReachedInTry() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),
                I(new LoadString("s"), 2),
                I(new BeginTry()),
                I(new Reassign(), 1, 2),
                I(new BeginCatch(), 3),
                I(new TypeOf(), 1, 4),                   // entered from before or after the reassignment
                I(new EndTryCatchFinally()),
                I(new TypeOf(), 1, 5));
        Analysis an = Analysis.analyse(p);
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.typeAt(6, 1).possible());
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.typeAt(8, 1).possible());
        assertEquals(ANY, an.types().get(3));
    }

    @Test void tryFinallyWithoutCatch() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),
                I(new LoadString("s"), 2),
                I(new BeginTry()),
                I(new Reassign(), 1, 2),
                I(new BeginFinally()),
                I(new TypeOf(), 1, 3),
                I(new EndTryCatchFinally()),
                I(new TypeOf(), 1, 4));
        Analysis an = Analysis.analyse(p);
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.typeAt(6, 1).possible());
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.typeAt(8, 1).possible());
    }

    @Test void functionSignatureAndCallResult() {
        var p = prog(
                I(new BeginPlainFunction(new Parameters(1)), 0, 1),
                I(new LoadInteger(2), 2),
                I(new BinaryOperation(MUL), 1, 2, 3),
                I(new Return(), 3),
                I(new EndPlainFunction()),
                I(new LoadInteger(5), 4),
                I(new CallFunction(1), 0, 4, 5),
                I(new BeginPlainFunction(new Parameters(0)), 6),
                I(new EndPlainFunction()),
                I(new CallFunction(0), 6, 7),
                I(new BeginGeneratorFunction(new Parameters(0)), 8),
                I(new Yield(), 4, 9),
                I(new EndGeneratorFunction()),
                I(new CallFunction(0), 8, 10));
        Analysis an = Analysis.analyse(p, false, Map.of(0, List.of(NUMBER)));
        assertEquals(NUMBER, an.types().get(1));
        assertEquals(NUMBER, an.returns().get(0));
        assertEquals(NUMBER, an.types().get(5));
        assertEquals(UNDEFINED, an.returns().get(6));
        assertEquals(UNDEFINED, an.types().get(7));
        assertEquals("Generator", an.types().get(10).group());
        assertEquals(INTEGER, an.types().get(10).element());
        assertTrue(an.types().get(0).is(FUNCTION));
        assertEquals(NUMBER, an.finalTypes().get(0).signature().returns());
    }

    @Test void functionBodyChangesOuterVariablesConditionally() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new Declare(DeclarationKind.VAR), 0, 1),
                I(new LoadString("s"), 2),
                I(new BeginPlainFunction(new Parameters(0)), 3),
                I(new Reassign(), 1, 2),
                I(new EndPlainFunction()),
                I(new TypeOf(), 1, 4));
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), Analysis.analyse(p).typeAt(6, 1).possible());
    }

    @Test void builtinsMethodsAndObjectLiterals() {
        var p = prog(
                I(new LoadBuiltin(Env.builtin("MAP")), 0),
                I(new Construct(0), 0, 1),
                I(new LoadBuiltin(Env.builtin("MATH")), 2),
                I(new LoadInteger(3), 3),
                I(new CallMethod(Env.method("MATH_IMUL"), 2), 2, 3, 3, 4),
                I(new LoadBuiltin(Env.builtin("PARSE_INT")), 5),
                I(new LoadString("7"), 6),
                I(new CallFunction(1), 5, 6, 7),
                I(new BeginObjectLiteral()),
                I(new ObjectLiteralAddProperty(k("A")), 3),
                I(new EndObjectLiteral(), 8),
                I(new GetProperty(k("A")), 8, 9),
                I(new GetProperty(k("B")), 8, 10),
                I(new SetProperty(k("B")), 8, 6),
                I(new GetProperty(k("B")), 8, 11),
                I(new CreateArray(2), 3, 6, 12),
                I(new GetElement(0), 12, 13),
                I(new GetProperty(k("LENGTH")), 12, 14));
        Analysis an = Analysis.analyse(p);
        assertEquals("Map", an.types().get(1).group());
        assertEquals(INTEGER, an.types().get(4));
        assertEquals(NUMBER, an.types().get(7));
        assertTrue(an.types().get(8).group().startsWith("_Object"));
        assertTrue(an.types().get(8).props().contains(k("A")));
        assertEquals(INTEGER, an.types().get(9));
        assertEquals(ANY, an.types().get(10));
        assertTrue(an.typeAt(14, 8).props().contains(k("B")));
        assertEquals(Kind.of(Kind.INTEGER, Kind.STRING), an.types().get(13).possible());
        assertEquals(INTEGER, an.types().get(14));
    }

    @Test void forLoopHeaderVariablesAliasAndJoin() {
        var p = prog(
                I(new LoadInteger(0), 0),
                I(new LoadFloat(0.5), 1),
                I(new BeginForLoopInitializer()),
                I(new BeginForLoopCondition(1), 0, 2),
                I(new LoadInteger(3), 3),
                I(new Compare(CompareOperator.LESS), 2, 3, 4),
                I(new BeginForLoopAfterthought(1), 4, 5),
                I(new Update(ADD), 5, 1),              // i += 0.5
                I(new BeginForLoopBody(1), 6, 7),
                I(new TypeOf(), 6, 8),
                I(new EndForLoop()));
        Analysis an = Analysis.analyse(p);
        assertSame(Storage.LOOPVAR, an.storage().get(2));
        assertSame(Storage.LABEL, an.storage().get(7));
        assertEquals(NUMBER, an.typeAt(9, 6));       // integer joined with the float update on the second pass
    }

    @Test void forEachAndDestructuringTypes() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new CreateArray(1), 0, 1),
                I(new BeginForEachLoop(ForEachKind.OF), 1, 2, 3),
                I(new EndForEachLoop()),
                I(new BeginForEachLoop(ForEachKind.IN), 1, 4, 5),
                I(new EndForEachLoop()),
                I(new Destruct(new ArrayPattern(List.of(new ArrayElement(new Binding())), new Binding())), 1, 6, 7));
        Analysis an = Analysis.analyse(p);
        assertEquals(INTEGER, an.types().get(2));
        assertEquals(STRING, an.types().get(4));
        assertEquals(INTEGER, an.types().get(6));
        assertEquals("Array", an.types().get(7).group());
        assertSame(Storage.FOREACH, an.storage().get(2));
        assertSame(Storage.TOP_VAR, an.storage().get(6));
    }

    // ------------------------------------------------------------------ Rhino model
    @Test void numberVarsFollowRhinoRules() {
        // function f(a) { var n = 1; n = n * 2; var m = a + 1; var k = -b; }  with b a BigInt local
        var p = prog(
                I(new BeginPlainFunction(new Parameters(1), List.of(), true), 0, 1),
                I(new LoadInteger(1), 2),
                I(new Declare(DeclarationKind.VAR), 2, 3),                 // n = 1
                I(new LoadInteger(2), 4),
                I(new BinaryOperation(MUL), 3, 4, 5),
                I(new Reassign(), 3, 5),                                  // n = n * 2        -> number var
                I(new BinaryOperation(ADD), 1, 2, 6),
                I(new Declare(DeclarationKind.VAR), 6, 7),                 // m = a + 1        -> mixed, not a number var
                I(new LoadBigInt(BigInteger.ONE), 8),
                I(new Declare(DeclarationKind.VAR), 8, 9),                 // b = 1n           -> any
                I(new UnaryOperation(UnaryOperator.MINUS), 9, 10),
                I(new Declare(DeclarationKind.VAR), 10, 11),               // k = -b           -> number (the BigInt bug shape)
                I(new LoadString("s"), 12),
                I(new Declare(DeclarationKind.VAR), 2, 13),
                I(new Reassign(), 13, 12),                                // x = 1; x = "s"   -> any
                I(new Declare(DeclarationKind.LET), 2, 14),                // let: not predicted
                I(new EndPlainFunction()));
        RhinoModel r = Analysis.analyse(p).rhino();
        assertEquals(List.of(), r.activation().get(0));
        assertEquals(Set.of(3, 11), r.numberVar());
        assertSame(RhinoType.NUMBER, r.exprType().get(4));
        assertSame(RhinoType.NUMBER, r.exprType().get(10));
        assertSame(RhinoType.MIXED, r.exprType().get(6));        // a + 1: AnyType to the optimizer, add(Object, double) in Codegen (G32)
    }

    static List<Instruction> fn(Instruction... body) {
        List<Instruction> all = new ArrayList<>();
        all.add(I(new BeginPlainFunction(new Parameters(0)), 0));
        all.addAll(List.of(body));
        all.add(I(new EndPlainFunction()));
        return prog(all.toArray(Instruction[]::new));
    }

    @Test void activationReasons() {
        assertEquals(List.of(ActivationReason.ARGUMENTS), Analysis.analyse(fn(I(new LoadArguments(), 1))).rhino().activation().get(0));
        assertEquals(List.of(ActivationReason.CATCH), Analysis.analyse(fn(I(new BeginTry()), I(new BeginCatch(), 1),
                I(new EndTryCatchFinally()))).rhino().activation().get(0));
        assertEquals(List.of(ActivationReason.NESTED_FUNCTION), Analysis.analyse(fn(I(new BeginArrowFunction(new Parameters(0)), 1),
                I(new EndArrowFunction()))).rhino().activation().get(0));
        var ev = fn(I(new LoadBuiltin(Env.builtin("EVAL")), 1), I(new LoadString("1"), 2), I(new CallFunction(1), 1, 2, 3));
        assertEquals(List.of(ActivationReason.EVAL), Analysis.analyse(ev).rhino().activation().get(0));
        var with = fn(I(new LoadBuiltin(Env.builtin("MATH")), 1), I(new BeginWith(), 1), I(new EndWith()));
        assertEquals(List.of(ActivationReason.WITH), Analysis.analyse(with).rhino().activation().get(0));
        var gen = prog(I(new BeginGeneratorFunction(new Parameters(0)), 0), I(new EndGeneratorFunction()));
        assertTrue(Analysis.analyse(gen).rhino().activation().get(0).contains(ActivationReason.YIELD));
        var plain = fn(I(new LoadInteger(1), 1), I(new Declare(DeclarationKind.VAR), 1, 2));
        assertEquals(List.of(), Analysis.analyse(plain).rhino().activation().get(0));
        assertTrue(Analysis.analyse(plain).rhino().optimizerRuns(0));
        assertEquals(Set.of(2), Analysis.analyse(plain).rhino().numberVar());
    }

    @Test void directCallsNeedANamedTopLevelFunctionCalledFromAFunctionWithExactArity() {
        var p = prog(
                I(new BeginPlainFunction(new Parameters(1), List.of(), true), 0, 1),
                I(new Return(), 1),
                I(new EndPlainFunction()),
                I(new LoadInteger(1), 2),
                I(new CallFunction(1), 0, 2, 3),              // from script level: not a direct call
                I(new BeginPlainFunction(new Parameters(0)), 4),
                I(new CallFunction(1), 0, 2, 5),              // direct call
                I(new CallFunction(2), 0, 2, 2, 6),           // wrong arity
                I(new EndPlainFunction()));
        RhinoModel r = Analysis.analyse(p).rhino();
        assertEquals(Set.of(0), r.directCallTarget());
        assertEquals(Set.of(6), r.directCallSite());
        var anon = prog(I(new BeginPlainFunction(new Parameters(1)), 0, 1), I(new EndPlainFunction()),
                I(new BeginPlainFunction(new Parameters(0)), 2), I(new LoadInteger(1), 3), I(new CallFunction(1), 0, 3, 4),
                I(new EndPlainFunction()));
        assertEquals(Set.of(), Analysis.analyse(anon).rhino().directCallTarget());
    }

    // ------------------------------------------------------------------ verify
    static String kind(Runnable r) { return assertThrows(InvalidProgram.class, r::run).kind; }

    @Test void verifyAcceptsAndRejects() {
        Analysis.verify(prog(I(new LoadInteger(1), 0), I(new Declare(DeclarationKind.VAR), 0, 1), I(new Reassign(), 1, 0)));
        assertEquals("assignment", kind(() -> Analysis.verify(prog(I(new LoadInteger(1), 0), I(new Reassign(), 0, 0)))));   // a temporary
        assertEquals("assignment", kind(() -> Analysis.verify(prog(I(new LoadInteger(1), 0), I(new Declare(DeclarationKind.CONST), 0, 1),
                I(new Update(ADD), 1, 0)))));
        assertEquals("type", kind(() -> Analysis.verify(prog(I(new LoadInteger(1), 0), I(new CallFunction(0), 0, 1)))));   // never callable
        assertEquals("type", kind(() -> Analysis.verify(prog(I(new LoadNull(), 0), I(new GetProperty(k("A")), 0, 1)))));
        Analysis.verify(prog(I(new LoadNull(), 0), I(new GetProperty(k("A"), true), 0, 1)));
        assertEquals("label", kind(() -> Analysis.verify(prog(I(new BeginRepeatLoop(1), 0, 1), I(new TypeOf(), 1, 2),
                I(new EndRepeatLoop())))));                    // label as value
        assertEquals("assignment", kind(() -> Analysis.verify(prog(I(new BeginRepeatLoop(1), 0, 1), I(new Reassign(), 0, 0),
                I(new EndRepeatLoop())))));                    // counter
        assertEquals("singular", kind(() -> Analysis.verify(prog(I(new BeginObjectLiteral()), I(new EndObjectLiteral(), 0),
                I(new BeginObjectLiteral()), I(new ObjectLiteralSetPrototype(), 0), I(new ObjectLiteralSetPrototype(), 0),
                I(new EndObjectLiteral(), 1)))));
        assertEquals("strict", kind(() -> Analysis.verify(prog(I(new LoadBuiltin(Env.builtin("MATH")), 0), I(new BeginWith(), 0),
                I(new EndWith())), true)));
    }

    @Test void verifyAllowsWhatMerelyMayThrow() {
        var p = prog(I(new LoadBuiltin(Env.builtin("MAP")), 0), I(new Construct(0), 0, 1), I(new LoadInteger(1), 2),
                I(new BinaryOperation(ADD), 1, 2, 3), I(new CallMethod(Env.method("ARRAY_PUSH"), 1), 1, 2, 4));
        Analysis.verify(p);      // pushing on a Map throws at run time in both modes; that is a test, not an error
        assertFalse(Analysis.analyse(p).toString().isEmpty());
    }
}
