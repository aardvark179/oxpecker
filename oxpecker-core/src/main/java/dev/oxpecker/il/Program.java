// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/FuzzIL/Instruction.swift, Code.swift, Blocks.swift and Analyzer.swift
// (ContextAnalyzer) at commit be2811d00282320381fc8007d64873745772011d.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import dev.oxpecker.il.Ops.BeginForLoopAfterthought;
import dev.oxpecker.il.Ops.BeginForLoopBody;
import dev.oxpecker.il.Ops.BeginForLoopCondition;
import dev.oxpecker.il.Ops.Operation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** A program, plus the static functions over code: validity, contexts, blocks, renumbering, signatures. */
public record Program(List<Instruction> code, GenOptions options, String template) {
    public Program {
        code = List.copyOf(code);
        if (template == null) template = "";
    }

    public Program(List<Instruction> code, GenOptions options) { this(code, options, ""); }

    public int size() { return code.size(); }

    public String signature() { return signature(code); }

    @Override public String toString() { return dump(code); }

    public String describe() {
        return "<Program " + (template.isEmpty() ? "untitled" : template) + ": " + code.size() + " instructions, " + signature() + ">";
    }

    // --------------------------------------------------------------------------- context analysis
    /** ContextAnalyzer.analyze (Analyzer.swift:160-206) as a pure function on a stack of contexts. */
    public static List<Integer> nextContext(List<Integer> stack, Instruction instr) {
        List<Integer> out = new ArrayList<>(stack);
        Operation op = instr.op();
        if (op.isBlockEnd()) out.remove(out.size() - 1);
        if (op.isBlockStart()) {
            int ctx = op.opened();
            if (op.propagatesContext()) ctx |= out.get(out.size() - 1);
            if (op.resumesContext()) {
                if (out.size() < 2)
                    throw new InvalidProgram("context", op.name() + " resumes a context that does not exist", instr.index());
                ctx |= out.get(out.size() - 2);
            }
            if ((op.opened() & (Context.SWITCH_BLOCK | Context.SWITCH_CASE)) != 0) ctx &= ~Context.LOOP;
            else if ((op.opened() & Context.LOOP) != 0) ctx &= ~(Context.SWITCH_BLOCK | Context.SWITCH_CASE);
            out.add(ctx);
        }
        return out;
    }

    /** The context in which each instruction sits (before it is applied). */
    public static List<Integer> contexts(List<Instruction> code) {
        List<Integer> stack = List.of(Context.JAVASCRIPT);
        List<Integer> out = new ArrayList<>(code.size());
        for (Instruction instr : code) {
            out.add(stack.get(stack.size() - 1));
            stack = nextContext(stack, instr);
        }
        return out;
    }

    // --------------------------------------------------------------------------- check
    private record Active(int scope, Operation head) {}

    /**
     * Static validity (Code.swift:215-321): indexes, contiguous variable numbering, inputs defined
     * and visible, required contexts, balanced and matching blocks, consistent for-loop headers.
     */
    public static void check(List<Instruction> code) {
        int top = -1;
        for (Instruction instr : code) for (int v : instr.allOutputs()) top = Math.max(top, v);
        int[] defined = new int[top + 1];                       // variable -> scope id, -1 if not defined (yet)
        Arrays.fill(defined, -1);
        int scopeCounter = 0;
        List<Active> active = new ArrayList<>(List.of(new Active(0, null)));
        Deque<Integer> forHeader = new ArrayDeque<>();
        List<Integer> stack = List.of(Context.JAVASCRIPT);
        for (int idx = 0; idx < code.size(); idx++) {
            Instruction instr = code.get(idx);
            if (instr.index() != idx) throw new InvalidProgram("index", "instruction has index " + instr.index(), idx);
            Operation op = instr.op();
            for (int v : instr.inputs()) {
                int scope = v >= 0 && v < defined.length ? defined[v] : -1;
                if (scope == -1) throw new InvalidProgram("undefined", "v" + v + " is never defined", idx);
                boolean visible = false;
                for (Active a : active) if (a.scope == scope) { visible = true; break; }
                if (!visible) throw new InvalidProgram("invisible", "v" + v + " is not visible", idx);
            }
            int ctx = stack.get(stack.size() - 1);
            if ((op.required() & ~ctx) != 0)
                throw new InvalidProgram("context", op.name() + " needs " + Context.str(op.required()) + " but is in " + Context.str(ctx), idx);
            if (op.isBlockEnd()) {
                if (active.size() <= 1) throw new InvalidProgram("block_mismatch", op.name() + " closes nothing", idx);
                Operation head = active.remove(active.size() - 1).head;
                if (head == null || !Ops.matchesEnd(head, op))
                    throw new InvalidProgram("block_mismatch", op.name() + " does not close " + (head != null ? head.name() : "anything"), idx);
            }
            stack = nextContext(stack, instr);
            for (int v : instr.outputs()) define(defined, v, active.get(active.size() - 1).scope, idx);
            if (op.isBlockStart()) {
                scopeCounter++;
                active.add(new Active(scopeCounter, op));
                if (op instanceof BeginForLoopCondition c) {
                    forHeader.push(c.numLoopVariables());
                } else if (op instanceof BeginForLoopAfterthought a) {
                    if (forHeader.isEmpty() || a.numLoopVariables() != forHeader.peek())
                        throw new InvalidProgram("for_header", "afterthought does not match the condition", idx);
                } else if (op instanceof BeginForLoopBody b) {
                    if (forHeader.isEmpty() || b.numLoopVariables() != forHeader.pop())
                        throw new InvalidProgram("for_header", "body does not match the header", idx);
                }
            }
            for (int v : instr.innerOutputs()) define(defined, v, active.get(active.size() - 1).scope, idx);
        }
        if (active.size() != 1) throw new InvalidProgram("block_mismatch", (active.size() - 1) + " block(s) left open");
    }

    private static void define(int[] defined, int v, int scope, int index) {
        if (v >= 0 && defined[v] != -1) throw new InvalidProgram("numbering", "v" + v + " defined twice", index);
        if (v != 0 && (v < 0 || defined[v - 1] == -1)) throw new InvalidProgram("numbering", "v" + v + " defined before v" + (v - 1), index);
        defined[v] = scope;
    }

    public static boolean isValid(List<Instruction> code) {
        try {
            check(code);
            return true;
        } catch (InvalidProgram e) {
            return false;
        }
    }

    // --------------------------------------------------------------------------- blocks
    /** Index of the instruction closing the block opened at {@code head} (Code.swift:393-413). */
    public static int blockEnd(List<Instruction> code, int head) {
        if (!code.get(head).op().isBlockStart()) throw new IllegalArgumentException("not a block start");
        int depth = 1;
        for (int i = head + 1; i < code.size(); i++) {
            Operation op = code.get(i).op();
            if (op.isBlockEnd()) {
                depth--;
                if (depth == 0) return i;
            }
            if (op.isBlockStart()) depth++;
        }
        throw new IllegalArgumentException("unterminated block");
    }

    public static int blockBegin(List<Instruction> code, int tail) {
        if (!code.get(tail).op().isBlockEnd()) throw new IllegalArgumentException("not a block end");
        int depth = 1;
        for (int i = tail - 1; i >= 0; i--) {
            Operation op = code.get(i).op();
            if (op.isBlockStart()) {
                depth--;
                if (depth == 0) return i;
            }
            if (op.isBlockEnd()) depth++;
        }
        throw new IllegalArgumentException("unopened block");
    }

    /** Indexes of all block instructions of the group starting at {@code head} (BeginIf, BeginElse, EndIf). */
    public static List<Integer> blockGroup(List<Instruction> code, int head) {
        if (!code.get(head).op().isBlockGroupStart()) throw new IllegalArgumentException("not a block group start");
        List<Integer> out = new ArrayList<>(List.of(head));
        int depth = 1;
        for (int i = head + 1; i < code.size(); i++) {
            Operation op = code.get(i).op();
            if (op.isBlockEnd()) depth--;
            if (op.isBlockStart()) {
                if (depth == 0) out.add(i);
                depth++;
            }
            if (depth == 0) {
                out.add(i);
                return out;
            }
        }
        throw new IllegalArgumentException("unterminated block group");
    }

    /** All block groups, inner before outer, earlier before later (Code.swift:479-500). */
    public static List<List<Integer>> blockGroups(List<Instruction> code) {
        List<List<Integer>> groups = new ArrayList<>();
        Deque<List<Integer>> stack = new ArrayDeque<>();
        for (int i = 0; i < code.size(); i++) {
            Operation op = code.get(i).op();
            if (op.isBlockStart() && !op.isBlockEnd()) {
                stack.push(new ArrayList<>(List.of(i)));
            } else if (op.isBlockEnd()) {
                stack.peek().add(i);
                if (!op.isBlockStart()) groups.add(stack.pop());
            }
        }
        return groups;
    }

    /** Index of the innermost subroutine begin whose block contains {@code index}, or -1 at top level. */
    public static int enclosingSubroutine(List<Instruction> code, int index, List<Class<? extends Operation>> begins) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            Operation op = code.get(i).op();
            if (op.isBlockEnd() && !op.isBlockStart()) {
                depth++;
            } else if (op.isBlockStart() && !op.isBlockEnd()) {
                if (depth == 0) {
                    if (Ops.isAny(op, begins)) return i;
                } else {
                    depth--;
                }
            }
        }
        return -1;
    }

    // --------------------------------------------------------------------------- renumbering, signatures
    /** Contiguous variable numbers in definition order and fresh indexes (Code.swift:166-181). */
    public static List<Instruction> renumber(List<Instruction> code) {
        int top = -1;
        for (Instruction instr : code) for (int v : instr.inouts()) top = Math.max(top, v);
        int[] mapping = new int[top + 1];                         // old variable -> new, -1 if not defined yet
        Arrays.fill(mapping, -1);
        int defined = 0;
        List<Instruction> out = new ArrayList<>(code.size());
        for (int i = 0; i < code.size(); i++) {
            Instruction instr = code.get(i);
            for (int v : instr.allOutputs()) {
                if (mapping[v] != -1) throw new InvalidProgram("numbering", "v" + v + " defined twice", i);
                mapping[v] = defined++;
            }
            int[] inouts = instr.inouts();
            for (int k = 0; k < inouts.length; k++) {
                int m = inouts[k] >= 0 ? mapping[inouts[k]] : -1;
                if (m == -1) throw new InvalidProgram("undefined", "v" + inouts[k] + " used before definition", i);
                inouts[k] = m;
            }
            out.add(instr.withInouts(inouts).withIndex(i));
        }
        return List.copyOf(out);
    }

    public static List<Instruction> reindex(List<Instruction> code) {
        List<Instruction> out = new ArrayList<>(code.size());
        for (int i = 0; i < code.size(); i++) out.add(code.get(i).withIndex(i));
        return List.copyOf(out);
    }

    /**
     * A structural hash: operations, their parameters, variables and lifting choices; provenance is
     * ignored, so two programs that lift the same have the same signature.
     */
    public static String signature(List<Instruction> code) {
        try {
            MessageDigest h = MessageDigest.getInstance("SHA-1");
            for (Instruction instr : code) {
                h.update(instr.op().repr().getBytes(StandardCharsets.UTF_8));
                h.update(Arrays.toString(instr.inouts()).getBytes(StandardCharsets.UTF_8));
                h.update(String.valueOf(instr.lift()).getBytes(StandardCharsets.UTF_8));
                h.update((byte) '\n');
            }
            return HexFormat.of().formatHex(h.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The IL as indented text. */
    public static String dump(List<Instruction> code) {
        List<String> lines = new ArrayList<>();
        int depth = 0;
        for (Instruction instr : code) {
            if (instr.op().isBlockEnd()) depth = Math.max(0, depth - 1);
            lines.add("    ".repeat(depth) + instr);
            if (instr.op().isBlockStart()) depth++;
        }
        return String.join("\n", lines);
    }

    /** The set of variables defined anywhere in {@code code}. */
    public static Set<Integer> definedVariables(List<Instruction> code) {
        Set<Integer> out = new HashSet<>();
        for (Instruction i : code) for (int v : i.allOutputs()) out.add(v);
        return out;
    }
}
