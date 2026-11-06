package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oxpecker.il.Ops.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** tests/il/test_program.py: instructions, check(), blocks, renumbering, signatures. */
class ProgramTest {
    static Instruction I(Operation op, int... inouts) { return new Instruction(op, inouts); }

    static List<Instruction> prog(Instruction... instrs) {
        List<Instruction> out = new ArrayList<>();
        for (int n = 0; n < instrs.length; n++) out.add(instrs[n].withIndex(n));
        return out;
    }

    @Test void instructionLayout() {
        Instruction i = I(new BinaryOperation(BinaryOperator.ADD), 0, 1, 2);
        assertArrayEquals(new int[] {0, 1}, i.inputs());
        assertArrayEquals(new int[] {2}, i.outputs());
        assertEquals(2, i.output());
        assertArrayEquals(new int[] {}, i.innerOutputs());
        Instruction f = I(new BeginPlainFunction(new Parameters(2)), 3, 4, 5);
        assertArrayEquals(new int[] {3}, f.outputs());
        assertArrayEquals(new int[] {4, 5}, f.innerOutputs());
        assertArrayEquals(new int[] {3, 4, 5}, f.allOutputs());
        assertThrows(IllegalArgumentException.class, () -> I(new BinaryOperation(BinaryOperator.ADD), 0, 1));
        assertThrows(IllegalStateException.class, () -> I(new Return(), 4).output());        // no output
        assertArrayEquals(new int[] {1, 2}, I(new CallFunction(2), 0, 1, 2, 3).variadicInputs());
    }

    @Test void checkAcceptsASmallValidProgram() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new LoadInteger(2), 1),
                I(new BinaryOperation(BinaryOperator.ADD), 0, 1, 2),
                I(new BeginPlainFunction(new Parameters(1)), 3, 4),
                I(new Return(), 4),
                I(new EndPlainFunction()),
                I(new CallFunction(1), 3, 2, 5));
        Program.check(p);
        assertTrue(Program.isValid(p));
    }

    static Stream<Arguments> rejected() {
        return Stream.<Object[]>of(
                new Object[] {"index", (Supplier<List<Instruction>>) () -> List.of(new Instruction(new LoadInteger(1), new int[] {0}, 5, "", 0, 0, null))},
                new Object[] {"numbering", (Supplier<List<Instruction>>) () -> prog(I(new LoadInteger(1), 1))},
                new Object[] {"numbering", (Supplier<List<Instruction>>) () -> prog(I(new LoadInteger(1), 0), I(new LoadInteger(1), 0))},
                new Object[] {"undefined", (Supplier<List<Instruction>>) () -> prog(I(new TypeOf(), 7, 0))},
                new Object[] {"context", (Supplier<List<Instruction>>) () -> prog(I(new LoadInteger(1), 0), I(new Return(), 0))},
                new Object[] {"context", (Supplier<List<Instruction>>) () -> prog(I(new LoadInteger(1), 0), I(new ObjectLiteralAddProperty(new Key("a")), 0))},
                new Object[] {"block_mismatch", (Supplier<List<Instruction>>) () -> prog(I(new BeginTry()), I(new EndIf()))},
                new Object[] {"block_mismatch", (Supplier<List<Instruction>>) () -> prog(I(new EndIf()))},
                new Object[] {"block_mismatch", (Supplier<List<Instruction>>) () -> prog(I(new BeginTry()))},
                new Object[] {"for_header", (Supplier<List<Instruction>>) () -> prog(I(new LoadInteger(0), 0), I(new BeginForLoopInitializer()),
                        I(new BeginForLoopCondition(1), 0, 1), I(new LoadBoolean(true), 2), I(new BeginForLoopAfterthought(2), 2, 3, 4))}
        ).map(Arguments::of);
    }

    @ParameterizedTest @MethodSource("rejected")
    void checkRejects(String kind, Supplier<List<Instruction>> bad) {
        InvalidProgram e = assertThrows(InvalidProgram.class, () -> Program.check(bad.get()));
        assertEquals(kind, e.kind);
    }

    @Test void checkRejectsAVariableUsedOutsideItsBlock() {
        var p = prog(
                I(new LoadBoolean(true), 0),
                I(new BeginIf(), 0, 1),
                I(new LoadInteger(3), 2),
                I(new EndIf()),
                I(new TypeOf(), 2, 3));
        assertEquals("invisible", assertThrows(InvalidProgram.class, () -> Program.check(p)).kind);
    }

    @Test void functionVariableIsVisibleOutsideButParametersAreNot() {
        var p = prog(
                I(new BeginPlainFunction(new Parameters(1)), 0, 1),
                I(new EndPlainFunction()),
                I(new CallFunction(1), 0, 1, 2));
        assertEquals("invisible", assertThrows(InvalidProgram.class, () -> Program.check(p)).kind);
    }

    @Test void contextsFollowBlocksAndSwitchCasesResume() {
        var p = prog(
                I(new LoadInteger(1), 0),
                I(new BeginPlainFunction(new Parameters(0)), 1),
                I(new BeginRepeatLoop(3), 2, 3),
                I(new BeginSwitch(), 0, 4),
                I(new BeginSwitchCase(), 0),
                I(new SwitchBreak()),
                I(new EndSwitchCase()),
                I(new EndSwitch()),
                I(new LoopBreak()),
                I(new EndRepeatLoop()),
                I(new Return(false)),
                I(new EndPlainFunction()));
        Program.check(p);
        var cs = Program.contexts(p);
        assertEquals(Context.JAVASCRIPT | Context.SUBROUTINE, cs.get(2));
        assertNotEquals(0, cs.get(3) & Context.LOOP);
        assertEquals(Context.SWITCH_BLOCK, cs.get(4));                   // inside the switch, before a case: no javascript
        assertNotEquals(0, cs.get(5) & Context.SWITCH_CASE);
        assertNotEquals(0, cs.get(5) & Context.JAVASCRIPT);
        assertNotEquals(0, cs.get(5) & Context.SUBROUTINE);
        assertEquals(0, cs.get(5) & Context.LOOP);                      // a case drops the loop context
        assertNotEquals(0, cs.get(8) & Context.LOOP);
    }

    @Test void blocks() {
        var p = prog(
                I(new LoadBoolean(true), 0),
                I(new BeginIf(), 0, 1),
                I(new BeginTry()),
                I(new BeginCatch(), 2),
                I(new EndTryCatchFinally()),
                I(new BeginElse(), 3),
                I(new EndIf()));
        Program.check(p);
        assertEquals(5, Program.blockEnd(p, 1));
        assertEquals(6, Program.blockEnd(p, 5));
        assertEquals(3, Program.blockEnd(p, 2));
        assertEquals(5, Program.blockBegin(p, 6));
        assertEquals(3, Program.blockBegin(p, 4));
        assertEquals(List.of(1, 5, 6), Program.blockGroup(p, 1));
        assertEquals(List.of(2, 3, 4), Program.blockGroup(p, 2));
        assertEquals(List.of(List.of(2, 3, 4), List.of(1, 5, 6)), Program.blockGroups(p));
        assertEquals(-1, Program.enclosingSubroutine(p, 3, Ops.FUNCTION_BEGINS));
    }

    @Test void enclosingSubroutine() {
        var p = prog(
                I(new BeginPlainFunction(new Parameters(0)), 0),
                I(new BeginTry()),
                I(new LoadInteger(1), 1),
                I(new BeginFinally()),
                I(new EndTryCatchFinally()),
                I(new EndPlainFunction()),
                I(new LoadInteger(2), 2));
        Program.check(p);
        assertEquals(0, Program.enclosingSubroutine(p, 2, Ops.FUNCTION_BEGINS));
        assertEquals(-1, Program.enclosingSubroutine(p, 6, Ops.FUNCTION_BEGINS));
    }

    @Test void renumberMakesNumbersContiguousAndKeepsMetadata() {
        var p = List.of(
                new Instruction(new LoadInteger(1), new int[] {5}, -1, "int", 0, 0, Lift.VAR),
                I(new LoadInteger(2), 9),
                new Instruction(new BinaryOperation(BinaryOperator.MUL), new int[] {5, 9, 12}, -1, "", 0, 3, null));
        var r = Program.renumber(p);
        Program.check(r);
        assertArrayEquals(new int[] {0}, r.get(0).inouts());
        assertArrayEquals(new int[] {1}, r.get(1).inouts());
        assertArrayEquals(new int[] {0, 1, 2}, r.get(2).inouts());
        assertEquals("int", r.get(0).gen());
        assertSame(Lift.VAR, r.get(0).lift());
        assertEquals(3, r.get(2).step());
        assertThrows(InvalidProgram.class, () -> Program.renumber(List.of(I(new TypeOf(), 3, 4))));
    }

    @Test void signatureIgnoresProvenanceButNotLifting() {
        var a = prog(new Instruction(new LoadInteger(1), new int[] {0}, -1, "x", 0, 0, null), I(new TypeOf(), 0, 1));
        var b = prog(new Instruction(new LoadInteger(1), new int[] {0}, -1, "y", 4, 0, null), I(new TypeOf(), 0, 1));
        var c = prog(new Instruction(new LoadInteger(1), new int[] {0}, -1, "", 0, 0, Lift.CONST), I(new TypeOf(), 0, 1));
        assertEquals(Program.signature(a), Program.signature(b));
        assertNotEquals(Program.signature(b), Program.signature(c));
        assertEquals(Program.signature(a), new Program(a, GenOptions.DEFAULT).signature());
    }

    @Test void dumpIsIndentedAndShowsVariables() {
        var p = prog(I(new BeginPlainFunction(new Parameters(1)), 0, 1), I(new Return(), 1), I(new EndPlainFunction()));
        String[] lines = Program.dump(p).split("\n");
        assertTrue(lines[1].startsWith("    ") && lines[1].endsWith("v1"));
        assertTrue(lines[0].startsWith("v0 <- BeginPlainFunction"));
        assertFalse(lines[2].startsWith(" "));
    }
}
