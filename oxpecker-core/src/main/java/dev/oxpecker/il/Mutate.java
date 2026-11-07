// Derived from Fuzzilli (https://github.com/googleprojectzero/fuzzilli), Sources/Fuzzilli/Mutators/
// OperationMutator.swift, InputMutator.swift, CodeGenMutator.swift, SpliceMutator.swift,
// CombineMutator.swift and Core/ProgramBuilder.swift (splice, 1735-2050) at commit
// be2811d00282320381fc8007d64873745772011d.
// Copyright 2019 Google LLC. Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may obtain a copy of
// the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable law
// or agreed to in writing, software distributed under the License is distributed on an "AS IS"
// BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under the License.
// Translated to Python and adapted for oxpecker (Rhino, Hypothesis strategies, the Rhino model),
// then to Java (jetCheck); the declaration-kind, reassign, activation and number-flip mutators are
// oxpecker's own.
package dev.oxpecker.il;

import static dev.oxpecker.il.Strategies.LIFT_CHOICES;
import static dev.oxpecker.il.Strategies.liftingChoices;
import static dev.oxpecker.il.Strategies.pick;
import static dev.oxpecker.il.Strategies.programs;
import static dev.oxpecker.il.Strategies.statements;

import dev.oxpecker.gen.Draw;
import dev.oxpecker.gen.Gen;
import dev.oxpecker.gen.Reject;
import dev.oxpecker.il.Ops.*;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Mutators as {@link Gen}s over a program (mutate.py; design §9, §22; G24, G25).
 *
 * <p>Each mutator returns a new program with contiguous variables, a {@code lift} for every new
 * instruction and {@code step} stamped on what it changed, or null when it is not applicable;
 * {@link #mutation} picks an applicable mutator from the fixed simplest-first list, verifies the
 * child and rejects a child no generator may produce. {@link #evolved} is a lineage: a
 * {@code programs()} draw followed by mutation steps, shrinking to fewer steps.
 */
public final class Mutate {
    private Mutate() {}

    static final List<Class<? extends Operation>> JUMPS = List.of(Return.class, ThrowException.class, LoopBreak.class,
            LoopContinue.class, BlockBreak.class, SwitchBreak.class);
    static final List<Class<? extends Operation>> LABEL_USERS = List.of(LoopBreak.class, LoopContinue.class, BlockBreak.class);
    static final List<Long> ELEMENT_INDEXES = List.of(0L, 1L, 2L, 3L, 4L, 10L, 42L, 100L, (1L << 31) - 1, (1L << 32) - 2);
    static final List<Long> REPEAT_COUNTS = List.of(0L, 1L, 2L, 3L, 5L, 10L, 25L, 100L);
    static final List<DeclarationKind> KIND_POOL = List.of(DeclarationKind.VAR, DeclarationKind.LET, DeclarationKind.CONST);
    static final Map<DeclarationKind, NamedVariableMode> MODE_OF_KIND = Map.of(DeclarationKind.VAR, NamedVariableMode.VAR,
            DeclarationKind.LET, NamedVariableMode.LET, DeclarationKind.CONST, NamedVariableMode.CONST);
    static final Map<NamedVariableMode, DeclarationKind> KIND_OF_MODE = Map.of(NamedVariableMode.VAR, DeclarationKind.VAR,
            NamedVariableMode.LET, DeclarationKind.LET, NamedVariableMode.CONST, DeclarationKind.CONST);
    static final Map<DeclarationKind, Lift> LIFT_OF_KIND = Map.of(DeclarationKind.VAR, Lift.VAR, DeclarationKind.LET, Lift.LET,
            DeclarationKind.CONST, Lift.CONST);
    static final Map<Lift, DeclarationKind> KIND_OF_LIFT = Map.of(Lift.VAR, DeclarationKind.VAR, Lift.LET, DeclarationKind.LET,
            Lift.CONST, DeclarationKind.CONST);
    static final List<Flags> REGEXP_FLAG_SETS = List.of(
            RegExpFlag.NONE, RegExpFlag.of(RegExpFlag.GLOBAL), RegExpFlag.of(RegExpFlag.IGNORE_CASE),
            RegExpFlag.of(RegExpFlag.GLOBAL | RegExpFlag.IGNORE_CASE), RegExpFlag.of(RegExpFlag.MULTILINE),
            RegExpFlag.of(RegExpFlag.STICKY), RegExpFlag.of(RegExpFlag.DOT_ALL), RegExpFlag.of(RegExpFlag.UNICODE));
    /** {@code list(PropertyFlag)}: the canonical members only. */
    static final List<Flags> PROPERTY_FLAGS = List.of(PropertyFlag.of(PropertyFlag.WRITABLE),
            PropertyFlag.of(PropertyFlag.CONFIGURABLE), PropertyFlag.of(PropertyFlag.ENUMERABLE));
    static final List<BinaryOperator> BINARY_OPERATORS = List.of(BinaryOperator.values());
    static final List<UnaryOperator> UNARY_OPERATORS = List.of(UnaryOperator.values());
    static final List<CompareOperator> COMPARATORS = List.of(CompareOperator.values());
    /** {@code sorted(set(LIFT_CHOICES), key=name)}. */
    static final List<Lift> LIFTS_BY_NAME = new TreeSet<>(LIFT_CHOICES).stream()
            .sorted(java.util.Comparator.comparing(Lift::name)).toList();
    static final List<BigInteger> INTERESTING_BIGINTS = Env.INTERESTING_INTEGERS.stream().map(BigInteger::valueOf).toList();

    /** A mutator: {@code (draw, program, analysis, step) -> child or null}. */
    @FunctionalInterface
    public interface Mutator {
        Program mutate(Draw d, Program program, Analysis an, int step);
    }

    // ------------------------------------------------------------------------------- helpers
    /** Contiguous variables, lift choices for the new instructions, {@code step} on the changed ones. */
    static Program finish(List<Instruction> code, GenOptions options, String template, Draw d, int step, List<Integer> changed) {
        List<Instruction> out = Program.renumber(code);
        out = liftingChoices(d, out);
        if (!changed.isEmpty()) {
            Set<Integer> marks = new HashSet<>(changed);
            List<Instruction> stamped = new ArrayList<>(out.size());
            for (Instruction i : out) stamped.add(marks.contains(i.index()) ? i.withStep(step) : i);
            out = stamped;
        }
        return new Program(out, options, template);
    }

    /** A drawn element of {@code pool} that is not {@code current} (OperationMutator never keeps the value). */
    static <T> T other(Draw d, List<? extends T> pool, Object current) {
        List<T> candidates = new ArrayList<>();
        for (T x : pool) if (!Objects.equals(x, current)) candidates.add(x);
        if (candidates.isEmpty()) return null;
        return pick(d, candidates);
    }

    /**
     * Positions where a statement may be inserted: the context allows statements and the position
     * is not dead code after a jump.
     */
    public static List<Integer> insertionPoints(List<Instruction> code, Analysis an) {
        List<Integer> points = new ArrayList<>();
        for (int i = 0; i <= code.size(); i++) {
            int ctx = i < code.size() ? an.contextAt().get(i) : Context.JAVASCRIPT;
            if ((ctx & Context.JAVASCRIPT) == 0) continue;
            if (i > 0 && Ops.isAny(code.get(i - 1).op(), JUMPS)) continue;
            points.add(i);
        }
        return points;
    }

    /** Variables usable as an input at {@code i}: visible, not a label, not the excluded one. */
    static List<Integer> usable(Analysis an, int i, Integer exclude) {
        List<Integer> out = new ArrayList<>();
        for (int v : an.visibleAt().get(i))
            if (an.storage().get(v) != Storage.LABEL && (exclude == null || v != exclude)) out.add(v);
        return out;
    }

    static boolean neverAssigned(Analysis an, int v) { return an.assignments().getOrDefault(v, List.of()).size() <= 1; }

    /**
     * Replays code[:pos] into a builder, lets {@code emit} add instructions, splices them back.
     * Throws BuilderError when the new instructions do not fit (the caller rejects).
     */
    static List<Instruction> emitThroughBuilder(List<Instruction> code, int pos, GenOptions options, Consumer<Builder> emit) {
        Builder b = Builder.at(code, pos, options);
        emit.accept(b);
        List<Instruction> fresh = b.newCode();
        if (fresh.isEmpty()) throw new BuilderError("nothing emitted");
        List<Instruction> out = new ArrayList<>(code.subList(0, pos));
        out.addAll(fresh);
        out.addAll(code.subList(pos, code.size()));
        return out;
    }

    static List<Integer> range(int from, int to) {
        List<Integer> out = new ArrayList<>();
        for (int i = from; i < to; i++) out.add(i);
        return out;
    }

    private static Program done(List<Instruction> out, Program program, Draw d, int step, List<Integer> changed) {
        return finish(out, program.options(), program.template(), d, step, changed);
    }

    // ------------------------------------------------------------------------------- the mutators
    /**
     * One parameter of one mutable instruction (OperationMutator.swift's table): a literal's value
     * within its pool, an operator, a key within its kind, a method on the same receiver group, an
     * element index, a loop count, property flags, {@code inverted} on an {@code if}; or one
     * {@code lift} choice. Receiver/call-optional flags flip with p=0.1 on top (996-1002). Never the
     * current value.
     *
     * <p>One deviation from mutate.py: flipping the only spread of a {@code *WithSpread} call off
     * made Python's constructor raise out of the mutator (and the draw); here the mutation is not
     * applicable instead.
     */
    public static Program operationMutation(Draw d, Program program, Analysis an, int step) {
        List<Instruction> code = program.code();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < code.size(); i++)
            if (code.get(i).op().isMutable() || code.get(i).lift() != null) candidates.add(i);
        if (candidates.isEmpty()) return null;
        int i = pick(d, candidates);
        Instruction instr = code.get(i);
        Operation op = instr.op();
        if (instr.lift() != null && (!op.isMutable() || d.maybe(0.3))) {
            Lift newLift = other(d, LIFTS_BY_NAME, instr.lift());
            if (newLift == null) return null;
            List<Instruction> out = new ArrayList<>(code);
            out.set(i, instr.withLift(newLift));
            return done(out, program, d, step, List.of(i));
        }
        Operation newOp;
        try {
            newOp = mutateOperation(d, op);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (newOp == null) return null;
        Record r = (Record) newOp;
        try {
            for (String flag : List.of("receiverOptional", "callOptional"))
                if (Records.has(r, flag) && d.maybe(0.1)) r = Records.with(r, flag, !(Boolean) Records.get(r, flag));
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<Instruction> out = new ArrayList<>(code);
        out.set(i, instr.withOp((Operation) r));
        return done(out, program, d, step, List.of(i));
    }

    private static boolean hasTyped(Record r, String name, Class<?>... types) {
        RecordComponent c = Records.component(r, name);
        if (c == null) return false;
        for (Class<?> t : types) if (t.isAssignableFrom(c.getType())) return true;
        return false;
    }

    private static Operation mutateOperation(Draw d, Operation op) {
        Record r = (Record) op;
        if (op instanceof LoadInteger o) {
            Long v = other(d, Env.INTERESTING_INTEGERS, o.value());
            return v != null ? new LoadInteger(v, o.radix()) : null;
        } else if (op instanceof LoadBigInt o) {
            BigInteger v = other(d, INTERESTING_BIGINTS, o.value());
            return v != null ? new LoadBigInt(v) : null;
        } else if (op instanceof LoadFloat o) {
            Double v = other(d, Env.INTERESTING_FLOATS, o.value());
            return v != null ? new LoadFloat(v, o.style()) : null;
        } else if (op instanceof LoadString o) {
            String v = other(d, Env.INTERESTING_STRINGS, o.value());
            return v != null ? new LoadString(v) : null;
        } else if (op instanceof LoadBoolean o) {
            return new LoadBoolean(!o.value());
        } else if (op instanceof LoadRegExp o) {
            if (d.maybe(0.5)) {
                List<Env.RegExpPattern> pool = Env.REGEXP_PATTERNS.stream().filter(p -> !p.pattern().equals(o.pattern())).toList();
                Env.RegExpPattern p = pick(d, pool);
                return new LoadRegExp(p.pattern(), o.flags().with(o.flags().bits() & ~p.incompatible().bits()));
            }
            Flags incompatible = Env.REGEXP_PATTERNS.stream().filter(p -> p.pattern().equals(o.pattern()))
                    .map(Env.RegExpPattern::incompatible).findFirst().orElse(RegExpFlag.NONE);
            List<Flags> sets = REGEXP_FLAG_SETS.stream().map(f -> f.with(f.bits() & ~incompatible.bits())).toList();
            Flags flags = other(d, sets, o.flags());
            return flags != null ? new LoadRegExp(o.pattern(), flags) : null;
        } else if (op instanceof CreateTemplateString o && !o.parts().isEmpty()) {
            int k = d.ints(0, o.parts().size() - 1);
            String part = other(d, Env.INTERESTING_STRINGS, o.parts().get(k));
            List<String> parts = new ArrayList<>(o.parts());
            parts.set(k, part);
            return new CreateTemplateString(parts);
        } else if (hasTyped(r, "key", Key.class) && Records.get(r, "key") != null) {
            Key current = (Key) Records.get(r, "key");
            List<Key> pool = Env.INTERESTING_KEYS.stream().filter(k -> k.kind() == current.kind()).toList();
            Key key = other(d, pool, current);
            if (key == null) return null;
            Record n = Records.with(r, "key", key);
            if (hasTyped(r, "op", BinaryOperator.class) && d.maybe(0.5))
                n = Records.with(n, "op", other(d, BINARY_OPERATORS, Records.get(r, "op")));
            return (Operation) n;
        } else if (hasTyped(r, "index", long.class, int.class) && !(op instanceof CallMethod)) {
            Long index = other(d, ELEMENT_INDEXES, ((Number) Records.get(r, "index")).longValue());
            Record n = Records.with(r, "index", index);
            if (hasTyped(r, "op", BinaryOperator.class) && d.maybe(0.5))
                n = Records.with(n, "op", other(d, BINARY_OPERATORS, Records.get(r, "op")));
            return (Operation) n;
        } else if (op instanceof CallMethod o) {
            List<Method> sameGroup = Env.methodsOf(o.method().receiver()).stream().filter(m -> !m.equals(o.method())).toList();
            return sameGroup.isEmpty() ? null : Records.with(o, "method", pick(d, sameGroup));
        } else if (op instanceof UnaryOperation o) {
            return new UnaryOperation(other(d, UNARY_OPERATORS, o.op()));
        } else if (op instanceof BinaryOperation || op instanceof Update || op instanceof UpdateComputedProperty
                || op instanceof UpdateSuperProperty) {
            return (Operation) Records.with(r, "op", other(d, BINARY_OPERATORS, Records.get(r, "op")));
        } else if (op instanceof Compare o) {
            return new Compare(other(d, COMPARATORS, o.op()));
        } else if (op instanceof BeginIf o) {
            return new BeginIf(!o.inverted());
        } else if (op instanceof BeginRepeatLoop o) {
            Long n = other(d, REPEAT_COUNTS, o.iterations());
            return Records.with(o, "iterations", n);
        } else if (hasTyped(r, "flags", Flags.class) && "PropertyFlag".equals(((Flags) Records.get(r, "flags")).type())) {
            Flags flags = other(d, PROPERTY_FLAGS, Records.get(r, "flags"));
            return flags != null ? (Operation) Records.with(r, "flags", flags) : null;
        } else if (Records.has(r, "spreads")) {
            @SuppressWarnings("unchecked") List<Boolean> spreads = new ArrayList<>((List<Boolean>) Records.get(r, "spreads"));
            if (spreads.isEmpty()) return null;
            int k = d.ints(0, spreads.size() - 1);
            spreads.set(k, !spreads.get(k));
            return (Operation) Records.with(r, "spreads", spreads);
        }
        return null;
    }

    /**
     * One input of one instruction rewired to another variable visible there that the slot accepts
     * (InputMutator.swift, type-aware mode): callee slots take functions, assignable slots take
     * assignable variables, property receivers are never nullish.
     */
    public static Program inputMutation(Draw d, Program program, Analysis an, int step) {
        List<Instruction> code = program.code();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            Instruction instr = code.get(i);
            if (instr.op().inputs() > 0 && !Ops.isAny(instr.op(), LABEL_USERS)) candidates.add(i);
        }
        if (candidates.isEmpty()) return null;
        int i = pick(d, candidates);
        Instruction instr = code.get(i);
        int k = d.ints(0, instr.op().inputs() - 1);
        int current = instr.input(k);
        List<Integer> pool = usable(an, i, current);
        Operation op = instr.op();
        Record r = (Record) op;
        if ((op instanceof CallFunction || op instanceof Construct || op instanceof CallFunctionWithSpread
                || op instanceof ConstructWithSpread) && k == 0) {
            pool.removeIf(v -> !an.typeAt(i, v).mayBe(Type.FUNCTION));
        } else if (((op instanceof Reassign || op instanceof Update) && k == 0)
                || (op instanceof UnaryOperation u && u.op().reassignsInput())) {
            pool.removeIf(v -> Analysis.NOT_ASSIGNABLE.contains(an.storage().get(v)));
        } else if (k == 0 && (op instanceof CallMethod || Records.has(r, "key") || Records.has(r, "index"))) {
            pool.removeIf(v -> an.typeAt(i, v).is(Type.NULLISH));
        }
        if (pool.isEmpty()) return null;
        int fresh = pick(d, pool);
        int[] inouts = instr.inouts();
        inouts[k] = fresh;
        List<Instruction> out = new ArrayList<>(code);
        out.set(i, instr.withInouts(inouts));
        return done(out, program, d, step, List.of(i));
    }

    record KindSite(int index, DeclarationKind current, boolean constOk) {}

    /**
     * (index, current kind, const allowed) for every declaration site: Declare, named variables,
     * loop headers, and lifted temporaries' keywords.
     */
    static List<KindSite> kindSites(List<Instruction> code, Analysis an) {
        List<KindSite> sites = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            Instruction instr = code.get(i);
            Operation op = instr.op();
            if (op instanceof Declare o) {
                sites.add(new KindSite(i, o.kind(), neverAssigned(an, instr.output())));
            } else if (op instanceof CreateNamedVariable o && KIND_OF_MODE.containsKey(o.mode())) {
                sites.add(new KindSite(i, KIND_OF_MODE.get(o.mode()), neverAssigned(an, instr.output())));
            } else if (op instanceof BeginForLoopCondition o) {
                boolean all = true;
                for (int v : instr.innerOutputs()) all &= neverAssigned(an, v);
                sites.add(new KindSite(i, o.declaration(), all));
            } else if (op instanceof BeginForEachLoop o) {
                int[] inner = instr.innerOutputs();
                boolean all = true;
                for (int j = 0; j < inner.length - 1; j++) all &= neverAssigned(an, inner[j]);
                sites.add(new KindSite(i, o.declaration(), all));
            } else if (op instanceof BeginRepeatLoop o) {
                sites.add(new KindSite(i, o.declaration(), false));          // the counter is incremented
            } else if (instr.lift() != null && KIND_OF_LIFT.containsKey(instr.lift())) {
                sites.add(new KindSite(i, KIND_OF_LIFT.get(instr.lift()), true));   // a temporary is never reassigned
            }
        }
        return sites;
    }

    /**
     * Flips the kind of one declaration (G25): {@code var <-> let} always, {@code -> const} only for
     * a never-assigned variable; the pool is ordered VAR, LET, CONST so shrinking moves towards var.
     */
    public static Program declarationKindMutation(Draw d, Program program, Analysis an, int step) {
        List<Instruction> code = program.code();
        List<KindSite> sites = kindSites(code, an);
        if (sites.isEmpty() || !program.options().toggles().contains(GenOptions.TOGGLE_LET)) return null;
        KindSite site = pick(d, sites);
        List<DeclarationKind> pool = KIND_POOL.stream()
                .filter(k -> k != site.current && (site.constOk || k != DeclarationKind.CONST)).toList();
        if (pool.isEmpty()) return null;
        DeclarationKind kind = pick(d, pool);
        Instruction instr = code.get(site.index);
        Operation op = instr.op();
        Instruction fresh;
        if (op instanceof Declare o) fresh = instr.withOp(new Declare(kind));
        else if (op instanceof BeginForLoopCondition || op instanceof BeginForEachLoop || op instanceof BeginRepeatLoop)
            fresh = instr.withOp((Operation) Records.with((Record) op, "declaration", kind));
        else if (op instanceof CreateNamedVariable o) fresh = instr.withOp(new CreateNamedVariable(o.identifier(), MODE_OF_KIND.get(kind)));
        else fresh = instr.withLift(LIFT_OF_KIND.get(kind));
        List<Instruction> out = new ArrayList<>(code);
        out.set(site.index, fresh);
        return done(out, program, d, step, List.of(site.index));
    }

    /**
     * Inserts {@code Reassign(v, w)} for a declared, assignable {@code v} and a {@code w} of another
     * type class at a drawn point (critique §2: reassignment is where Rhino's typing goes wrong).
     */
    public static Program reassignMutation(Draw d, Program program, Analysis an, int step) {
        List<Instruction> code = program.code();
        if (code.isEmpty()) return null;
        List<int[]> targets = new ArrayList<>();
        for (int i : insertionPoints(code, an)) {
            for (int v : usable(an, i < code.size() ? i : code.size() - 1, null)) {
                Storage s = an.storage().get(v);
                if (!Analysis.NOT_ASSIGNABLE.contains(s) && s != Storage.TEMP && i > an.defs()[v])
                    targets.add(new int[]{i, v});
            }
        }
        if (targets.isEmpty()) return null;
        int[] t = pick(d, targets);
        int i = t[0], v = t[1];
        int at = i < code.size() ? i : code.size() - 1;
        List<Integer> others = usable(an, at, v);
        Type vt = an.typeAt(at, v);
        List<Integer> different = others.stream().filter(w -> an.typeAt(at, w).possible() != vt.possible()).toList();
        List<Integer> pool = different.isEmpty() ? others : different;
        if (pool.isEmpty()) return null;
        int w = pick(d, pool);
        Instruction fresh = new Instruction(new Reassign(), new int[]{v, w}, -1, "reassign", 0, 0, null);
        List<Instruction> out = new ArrayList<>(code.subList(0, i));
        out.add(fresh);
        out.addAll(code.subList(i, code.size()));
        return done(out, program, d, step, List.of(i));
    }

    /**
     * {@code statements()} at a drawn point with the pool of that point's context
     * (CodeGenMutator.swift), one to three statements, through a builder positioned at the point.
     */
    public static Program codegenMutation(Draw d, Program program, Analysis an, int step) {
        List<Instruction> code = program.code();
        List<Integer> points = insertionPoints(code, an);
        if (points.isEmpty()) return null;
        int pos = pick(d, points);
        List<Instruction> out;
        try {
            out = emitThroughBuilder(code, pos, program.options(), b -> statements(d, b, 1, 3));
        } catch (BuilderError e) {
            return null;
        }
        return done(out, program, d, step, range(pos, pos + (out.size() - code.size())));
    }

    record Slice(List<Integer> included, List<Integer> external) {}

    /**
     * The backward slice of donor[j] (ProgramBuilder.swift:1735-2050, simplified): the whole block
     * group if j is a block instruction, then every non-block instruction defining a needed
     * variable, transitively. Variables defined by block instructions outside the slice are returned
     * as {@code external} for remapping.
     */
    static Slice slice(List<Instruction> donor, int j) {
        int first = j, last = j;
        if (donor.get(j).op().isBlock()) {
            int head = j;
            boolean found = false;
            for (int i = 0; i < donor.size(); i++)
                if (donor.get(i).op().isBlockStart() && i <= j && Program.blockEnd(donor, i) >= j) { head = i; found = true; }
            if (!found) head = j;
            first = head;
            last = Program.blockEnd(donor, head);
        }
        Set<Integer> included = new TreeSet<>();
        for (int i = first; i <= last; i++) included.add(i);
        Set<Integer> defined = new HashSet<>();
        for (int i : included) for (int v : donor.get(i).allOutputs()) defined.add(v);
        List<Integer> needed = new ArrayList<>();
        for (int i : included) for (int v : donor.get(i).inputs()) if (!defined.contains(v)) needed.add(v);
        List<Integer> external = new ArrayList<>();
        int[] defs = new int[numVariables(donor)];
        for (int i = 0; i < donor.size(); i++) for (int v : donor.get(i).allOutputs()) defs[v] = i;
        while (!needed.isEmpty()) {
            int v = needed.remove(needed.size() - 1);
            if (defined.contains(v) || external.contains(v)) continue;
            int dd = defs[v];
            if (donor.get(dd).op().isBlock() || included.contains(dd)) {
                external.add(v);
                continue;
            }
            included.add(dd);
            for (int w : donor.get(dd).allOutputs()) defined.add(w);
            for (int w : donor.get(dd).inputs()) if (!defined.contains(w)) needed.add(w);
        }
        return new Slice(new ArrayList<>(included), external);
    }

    /** One past the highest variable {@code code} defines. */
    private static int numVariables(List<Instruction> code) {
        int top = -1;
        for (Instruction instr : code) for (int v : instr.allOutputs()) top = Math.max(top, v);
        return top + 1;
    }

    private static int[] unmapped(int n) {
        int[] xs = new int[n];
        Arrays.fill(xs, -1);
        return xs;
    }

    private static List<Integer> ints(int[] xs) {
        List<Integer> out = new ArrayList<>(xs.length);
        for (int x : xs) out.add(x);
        return out;
    }

    /**
     * A donor instruction's backward slice emitted at a drawn point (SpliceMutator.swift), blocks
     * whole, variables renumbered; a needed variable the slice cannot define (a block's output or
     * inner output) is remapped to a host variable of the same type, as are slice outputs with
     * p=0.10 (ProgramBuilder.swift's remap probabilities). The donor is another {@code programs()}
     * draw unless given.
     */
    public static Program spliceMutation(Draw d, Program program, Analysis an, Program donor, int step) {
        List<Instruction> code = program.code();
        GenOptions options = program.options();
        if (donor == null) donor = d.draw(programs(options)).program();
        List<Instruction> dcode = donor.code();
        Analysis dan = Analysis.analyse(dcode);
        List<Integer> points = insertionPoints(code, an);
        if (points.isEmpty() || dcode.isEmpty()) return null;
        int pos = pick(d, points);
        int j = d.ints(0, dcode.size() - 1);
        Slice s = slice(dcode, j);
        int hostCtx = pos < code.size() ? an.contextAt().get(pos) : Context.JAVASCRIPT;
        int depth = 0;
        for (int i : s.included) {
            Operation op = dcode.get(i).op();
            if (depth == 0 && (op.required() & ~hostCtx) != 0) return null;
            if (op.isBlockStart()) depth++;
            if (op.isBlockEnd() && !op.isBlockStart()) depth--;
        }
        Consumer<Builder> emit = b -> {
            int[] mapping = unmapped(numVariables(dcode));     // donor variable -> host variable
            for (int v : s.external) {
                Type want = dan.types()[v] != null ? dan.types()[v] : Type.ANY;
                List<Integer> same = b.visible().stream().filter(w -> b.typeOf(w).is(want)).toList();
                if (same.isEmpty()) same = b.visible();
                if (same.isEmpty()) throw new BuilderError("no host variable for an external slice variable");
                mapping[v] = pick(d, same);
            }
            for (int i : s.included) {
                Instruction instr = dcode.get(i);
                List<Integer> inputs = new ArrayList<>();
                for (int v : instr.inputs()) {
                    if (mapping[v] == -1) mapping[v] = v;
                    inputs.add(mapping[v]);
                }
                Instruction fresh = b.emit(instr.op(), inputs, instr.lift());
                int[] olds = instr.allOutputs(), news = fresh.allOutputs();
                List<Integer> outputs = ints(instr.outputs());
                for (int k = 0; k < Math.min(olds.length, news.length); k++) {
                    int old = olds[k], nv = news[k];
                    if (outputs.contains(old) && d.maybe(0.10)) {
                        List<Integer> same = b.visible().stream().filter(w -> w != nv && b.typeOf(w).is(b.typeOf(nv))).toList();
                        if (!same.isEmpty()) {
                            mapping[old] = pick(d, same);
                            continue;
                        }
                    }
                    mapping[old] = nv;
                }
                String gen = "splice:" + (instr.gen().isEmpty() ? instr.op().name() : instr.gen());
                b.code.set(b.code.size() - 1, fresh.withProvenance(gen, instr.origin(), fresh.step()));
            }
        };
        List<Instruction> out;
        try {
            out = emitThroughBuilder(code, pos, options, emit);
        } catch (BuilderError e) {
            return null;
        }
        return done(out, program, d, step, range(pos, pos + (out.size() - code.size())));
    }

    /** A whole donor program emitted at a drawn point (CombineMutator.swift). */
    public static Program combineMutation(Draw d, Program program, Analysis an, Program donor, int step) {
        List<Instruction> code = program.code();
        GenOptions options = program.options();
        if (donor == null) donor = d.draw(programs(options)).program();
        List<Instruction> dcode = donor.code();
        List<Integer> points = new ArrayList<>();
        for (int p : insertionPoints(code, an))
            if ((p < code.size() ? an.contextAt().get(p) : Context.JAVASCRIPT) == Context.JAVASCRIPT) points.add(p);
        if (points.isEmpty() || dcode.isEmpty()) return null;
        int pos = pick(d, points);
        Consumer<Builder> emit = b -> {
            int[] mapping = unmapped(numVariables(dcode));     // donor variable -> host variable
            for (Instruction instr : dcode) {
                List<Integer> inputs = new ArrayList<>();
                for (int v : instr.inputs()) {
                    if (mapping[v] == -1) throw new NullPointerException("unmapped donor variable");
                    inputs.add(mapping[v]);
                }
                Instruction fresh = b.emit(instr.op(), inputs, instr.lift());
                int[] olds = instr.allOutputs(), news = fresh.allOutputs();
                for (int k = 0; k < Math.min(olds.length, news.length); k++) mapping[olds[k]] = news[k];
                b.code.set(b.code.size() - 1, fresh.withProvenance(instr.gen(), instr.origin(), fresh.step()));
            }
        };
        List<Instruction> out;
        try {
            out = emitThroughBuilder(code, pos, options, emit);
        } catch (BuilderError e) {
            return null;
        }
        return done(out, program, d, step, range(pos, pos + dcode.size()));
    }

    static final List<Consumer<Builder>> ACTIVATION_TRIGGERS = List.of(
            b -> b.emit(new LoadArguments()),
            Mutate::emptyArrow,
            Mutate::emptyTryCatch);

    static void emptyArrow(Builder b) {
        try (var f = b.arrowFunction()) {
            // nothing
        }
    }

    static void emptyTryCatch(Builder b) {
        try (var t = b.try_()) {
            try (var c = t.catch_()) {
                // nothing
            }
        }
    }

    /**
     * Indexes of the one activation trigger of function {@code f} when it is its only reason and can
     * be removed without changing anything else: an unused {@code arguments}, an empty arrow
     * function, an empty try/catch. Removing it makes the optimizer run.
     */
    static List<Integer> removableTrigger(List<Instruction> code, Analysis an, int f) {
        List<ActivationReason> reasons = an.rhino().activation().getOrDefault(f, List.of());
        if (reasons.size() != 1) return null;
        ActivationReason reason = reasons.get(0);
        int end = Program.blockEnd(code, f);
        int depth = 0;
        for (int i = f + 1; i < end; i++) {
            Operation op = code.get(i).op();
            if (depth == 0) {
                if (reason == ActivationReason.ARGUMENTS && op instanceof LoadArguments && an.numUses(code.get(i).output()) == 0)
                    return List.of(i);
                if (reason == ActivationReason.NESTED_FUNCTION && op instanceof BeginArrowFunction
                        && code.get(i + 1).op() instanceof EndArrowFunction && an.numUses(code.get(i).output()) == 0)
                    return List.of(i, i + 1);
                if (reason == ActivationReason.CATCH && op instanceof BeginTry && code.get(i + 1).op() instanceof BeginCatch
                        && code.get(i + 2).op() instanceof EndTryCatchFinally)
                    return List.of(i, i + 1, i + 2);
            }
            if (op.isBlockStart()) depth++;
            if (op.isBlockEnd() && !op.isBlockStart()) depth--;
        }
        return null;
    }

    record ActivationChoice(boolean insert, int f, List<Integer> remove) {}

    /**
     * Inserts one activation trigger ({@code arguments}, an empty arrow, an empty try/catch) at the
     * top of a function the Rhino model says is optimizer-typed, or removes one from a function it
     * says is not: the compiled path changes, the result must not.
     */
    public static Program activationMutation(Draw d, Program program, Analysis an, int step) {
        List<Instruction> code = program.code();
        List<Integer> functions = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) if (Ops.isAny(code.get(i).op(), Ops.FUNCTION_BEGINS)) functions.add(i);
        List<ActivationChoice> choices = new ArrayList<>();
        for (int f : functions) if (an.rhino().optimizerRuns(f)) choices.add(new ActivationChoice(true, f, null));
        for (int f : functions) {
            if (an.rhino().optimizerRuns(f)) continue;
            List<Integer> r = removableTrigger(code, an, f);
            if (r != null) choices.add(new ActivationChoice(false, f, r));
        }
        if (choices.isEmpty()) return null;
        ActivationChoice c = pick(d, choices);
        if (!c.insert) {
            Set<Integer> drop = new HashSet<>(c.remove);
            List<Instruction> out = new ArrayList<>();
            for (int i = 0; i < code.size(); i++) if (!drop.contains(i)) out.add(code.get(i));
            return done(out, program, d, step, List.of());
        }
        Consumer<Builder> trigger = pick(d, ACTIVATION_TRIGGERS);
        List<Instruction> out;
        try {
            out = emitThroughBuilder(code, c.f + 1, program.options(), trigger);
        } catch (BuilderError e) {
            return null;
        }
        return done(out, program, d, step, range(c.f + 1, c.f + 1 + (out.size() - code.size())));
    }

    /**
     * For a number variable of the Rhino model, inserts one assignment whose right-hand side is
     * AnyType (a string, undefined, a BigInt, an object) at a drawn point in its function: the shape
     * of the recorded compiled-mode bugs (§5.3).
     */
    public static Program numberFlipMutation(Draw d, Program program, Analysis an, int step) {
        List<Instruction> code = program.code();
        List<Integer> numberVars = new ArrayList<>(new TreeSet<>(an.rhino().numberVar()));
        numberVars.removeIf(v -> Analysis.NOT_ASSIGNABLE.contains(an.storage().get(v)));
        if (numberVars.isEmpty()) return null;
        int v = pick(d, numberVars);
        int def = an.defs()[v];
        int f = an.functionOf().get(def);
        int end = f != Analysis.TOP_LEVEL ? Program.blockEnd(code, f) : code.size();
        List<Integer> points = new ArrayList<>();
        for (int p : insertionPoints(code, an))
            if (def < p && p <= end && an.visibleAt().get(Math.min(p, code.size() - 1)).contains(v)) points.add(p);
        if (points.isEmpty()) return null;
        int pos = pick(d, points);
        int shape = d.ints(0, 3);
        Consumer<Builder> emit = b -> {
            int w;
            if (shape == 0) w = b.loadString(pick(d, Env.INTERESTING_STRINGS));
            else if (shape == 1) w = b.loadUndefined();
            else if (shape == 2) w = b.loadBigInt(pick(d, Env.INTERESTING_INTEGERS));
            else w = b.createArray(List.of());
            b.reassign(v, w);
            for (int k = Math.max(0, b.code.size() - 2); k < b.code.size(); k++) {
                Instruction instr = b.code.get(k);
                b.code.set(k, instr.withProvenance("number_flip", instr.origin(), instr.step()));
            }
        };
        List<Instruction> out;
        try {
            out = emitThroughBuilder(code, pos, program.options(), emit);
        } catch (BuilderError e) {
            return null;
        }
        return done(out, program, d, step, range(pos, pos + (out.size() - code.size())));
    }

    public record Named(String name, Mutator mutator) {}

    public static final List<Named> MUTATORS = List.of(
            new Named("operation", Mutate::operationMutation),
            new Named("input", Mutate::inputMutation),
            new Named("declaration_kind", Mutate::declarationKindMutation),
            new Named("reassign", Mutate::reassignMutation),
            new Named("codegen", Mutate::codegenMutation),
            new Named("splice", (d, p, an, step) -> spliceMutation(d, p, an, null, step)),
            new Named("combine", (d, p, an, step) -> combineMutation(d, p, an, null, step)),
            new Named("activation", Mutate::activationMutation),
            new Named("number_flip", Mutate::numberFlipMutation));
    public static final Map<String, Mutator> MUTATOR_BY_NAME;
    /**
     * Draw weights (G50). {@code combine} appends a whole fresh program, so it reaches new coverage
     * almost by construction and drags the corpus towards large programs; {@code codegen} inserts a
     * generated statement and was next.
     */
    public static final Map<String, Integer> MUTATOR_WEIGHTS = Map.of("combine", 1, "codegen", 2);
    public static final int DEFAULT_MUTATOR_WEIGHT = 3;
    static final List<Integer> WEIGHTED;

    static {
        Map<String, Mutator> m = new LinkedHashMap<>();
        for (Named n : MUTATORS) m.put(n.name, n.mutator);
        MUTATOR_BY_NAME = Collections.unmodifiableMap(m);
        List<Integer> w = new ArrayList<>();
        for (int i = 0; i < MUTATORS.size(); i++)
            for (int k = 0; k < MUTATOR_WEIGHTS.getOrDefault(MUTATORS.get(i).name, DEFAULT_MUTATOR_WEIGHT); k++) w.add(i);
        WEIGHTED = List.copyOf(w);
    }

    public static List<String> mutators() { return MUTATORS.stream().map(Named::name).toList(); }

    /** Each mutator's draw weight; its share of lineage steps is weight over the total. */
    public static Map<String, Integer> mutatorWeights() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Named n : MUTATORS) out.put(n.name, MUTATOR_WEIGHTS.getOrDefault(n.name, DEFAULT_MUTATOR_WEIGHT));
        return out;
    }

    /** One index draw into the fixed weight-expanded list (as the generator pools, G6). */
    static Named weightedMutator(Draw d) { return MUTATORS.get(WEIGHTED.get(d.ints(0, WEIGHTED.size() - 1))); }

    // ------------------------------------------------------------------------------- mutation and lineages
    /** The mutator produced nothing, or a child {@code verify} refuses. */
    public static final class Rejected extends RuntimeException {
        public Rejected(String message, Throwable cause) { super(message, cause, false, false); }

        public Rejected(String message) { this(message, null); }
    }

    /** A mutator's name and its child. */
    public record Result(String name, Program child) {}

    /**
     * One mutation by a drawn (or named) mutator; throws {@link Rejected} when the mutator was not
     * applicable or the child does not verify.
     */
    public static Result tryMutation(Draw d, Program program, String name, int step, Analysis an) {
        return d.draw(dd -> {
            Analysis a = an != null ? an : Analysis.analyse(program.code(), program.options().strict());
            String chosen = name;
            Mutator m;
            if (chosen == null) {
                Named n = weightedMutator(dd);
                chosen = n.name;
                m = n.mutator;
            } else {
                m = Objects.requireNonNull(MUTATOR_BY_NAME.get(chosen), "unknown mutator");
            }
            String fixed = chosen;
            Program child = dd.draw(e -> m.mutate(e, program, a, step));
            if (child == null) throw new Rejected(fixed + ": not applicable");
            try {
                Analysis.verify(child.code(), program.options().strict());
                Lifter.lift(child.code(), LiftOptions.DEFAULT.withProbes(false));
            } catch (InvalidProgram | BuilderError | IllegalArgumentException | java.util.NoSuchElementException e) {
                throw new Rejected(fixed + ": " + e.getMessage(), e);
            }
            return new Result(fixed, child);
        });
    }

    public static Result tryMutation(Draw d, Program program, String name, int step) { return tryMutation(d, program, name, step, null); }

    /** A verified child of {@code program}; a rejected mutation rejects the draw (design §9). */
    public static Program mutation(Draw d, Program program, String name, int step) {
        try {
            return tryMutation(d, program, name, step).child();
        } catch (Rejected e) {
            return Reject.reject(e.getMessage());
        }
    }

    public record Step(String name, Program program) {}

    /** A base program and the mutation steps applied to it; {@link #program()} is the last one. */
    public static final class Lineage {
        public final Program base;
        public final List<Step> steps;
        public int rejected;
        /** The {@code steps} the lineage was drawn with: its maximum depth. */
        public final int cap;
        public final Map<String, Integer> attemptedBy;
        public final Map<String, Integer> rejectedBy;

        public Lineage(Program base, List<Step> steps, int rejected, int cap, Map<String, Integer> attemptedBy,
                       Map<String, Integer> rejectedBy) {
            this.base = base;
            this.steps = new ArrayList<>(steps);
            this.rejected = rejected;
            this.cap = cap;
            this.attemptedBy = new LinkedHashMap<>(attemptedBy);
            this.rejectedBy = new LinkedHashMap<>(rejectedBy);
        }

        public Lineage(Program base, int cap) { this(base, List.of(), 0, cap, Map.of(), Map.of()); }

        /** Steps drawn so far, accepted or rejected: what {@code evolved} counts against its cap. */
        public int depth() { return steps.size() + rejected; }

        /** Ended by the cap rather than by a "one more?" false: its last choice is not a stop flag. */
        public boolean atCap() { return depth() >= cap; }

        public Program program() { return steps.isEmpty() ? base : steps.get(steps.size() - 1).program(); }

        public List<Instruction> code() { return program().code(); }

        public GenOptions options() { return base.options(); }

        public String template() { return base.template(); }

        public String source() { return new Strategies.Generated(program(), template()).source(); }

        public String plainSource() { return new Strategies.Generated(program(), template()).plainSource(); }

        public List<String> stepNames() { return steps.stream().map(Step::name).toList(); }

        @Override public String toString() { return source(); }

        public String describe() { return "<Lineage " + template() + " + " + String.join(", ", stepNames()) + ">"; }
    }

    /**
     * A lineage: a {@code programs()} draw (or {@code base}) and up to {@code steps} mutations, each
     * preceded by a "one more?" boolean (proposal §3.5): the loop shrinks to fewer steps, and the
     * last choice of a complete lineage is the false that ended it, which is what extending a corpus
     * entry flips ({@code Fuzz.extend}). A rejected step is skipped and counted, never fatal.
     *
     * <p>With {@code parent}, the lineage continues that one: its base and steps are kept (copied),
     * no choice is drawn for them, and the step count and numbering carry on where it stopped, so the
     * choices drawn here are exactly those a full replay draws after the parent's stop flag.
     * {@code steps} is the cap on the whole lineage's depth.
     */
    public static Gen<Lineage> evolved(GenOptions options, int steps, Program base, Lineage parent) {
        return d -> {
            Lineage lineage;
            if (parent != null) {
                lineage = new Lineage(parent.base, parent.steps, parent.rejected, steps, parent.attemptedBy, parent.rejectedBy);
            } else {
                Program b = base != null ? base : d.draw(programs(options)).program();
                lineage = new Lineage(b, steps);
            }
            int k = lineage.depth();
            while (k < steps && d.bool()) {
                k++;
                int stepNo = k;
                Lineage l = lineage;
                d.draw("step", dd -> {
                    String name = weightedMutator(dd).name;
                    l.attemptedBy.merge(name, 1, Integer::sum);
                    try {
                        Result r = tryMutation(dd, l.program(), name, stepNo);
                        l.steps.add(new Step(r.name(), r.child()));
                    } catch (Rejected e) {
                        l.rejected++;
                        l.rejectedBy.merge(name, 1, Integer::sum);
                    }
                    return null;
                });
            }
            return lineage;
        };
    }

    public static Gen<Lineage> evolved(GenOptions options, int steps) { return evolved(options, steps, null, null); }

    // ------------------------------------------------------------------------------- from a shell
    /** One mutation applied by hand: the parent, the child, and the mutator's name. */
    public record Mutated(Program parent, String name, Program program) {
        public List<Instruction> code() { return program.code(); }

        public String source() { return new Strategies.Generated(program, program.template()).source(); }

        public String plainSource() { return new Strategies.Generated(program, program.template()).plainSource(); }

        /** The IL before and after, as a unified diff (two lines of context). */
        public String diff() { return unifiedDiff(Program.dump(parent.code()).lines().toList(), Program.dump(program.code()).lines().toList(), "parent", name, 2); }

        @Override public String toString() { return source(); }
    }

    /** A unified diff of two line lists (an LCS diff, for the small IL dumps difflib was used on). */
    static String unifiedDiff(List<String> a, List<String> b, String from, String to, int context) {
        int n = a.size(), m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        List<String> ops = new ArrayList<>();          // " x", "-x", "+x"
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) { ops.add(" " + a.get(i)); i++; j++; }
            else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) ops.add("+" + b.get(j++));
            else ops.add("-" + a.get(i++));
        }
        StringBuilder out = new StringBuilder("--- " + from + "\n+++ " + to + "\n");
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k).charAt(0) == ' ') { k++; continue; }
            int start = Math.max(0, k - context), end = k;
            while (end < ops.size()) {
                int next = end;
                while (next < ops.size() && ops.get(next).charAt(0) == ' ') next++;
                if (next >= ops.size() || next - end > 2 * context) break;
                end = next;
                while (end < ops.size() && ops.get(end).charAt(0) != ' ') end++;
            }
            int stop = Math.min(ops.size(), end + context);
            int ai = 1, bi = 1;
            for (int q = 0; q < start; q++) { if (ops.get(q).charAt(0) != '+') ai++; if (ops.get(q).charAt(0) != '-') bi++; }
            int al = 0, bl = 0;
            for (int q = start; q < stop; q++) { if (ops.get(q).charAt(0) != '+') al++; if (ops.get(q).charAt(0) != '-') bl++; }
            out.append("@@ -").append(ai).append(',').append(al).append(" +").append(bi).append(',').append(bl).append(" @@\n");
            for (int q = start; q < stop; q++) out.append(ops.get(q)).append('\n');
            k = stop;
        }
        return out.toString().stripTrailing();
    }

    /**
     * One mutation of a program, deterministically from a seed; {@code name} fixes the mutator.
     * Throws {@link Rejected} when that seed's choice is not applicable or does not verify, so another
     * seed can be tried.
     */
    public static Mutated mutate(Program program, long seed, String name, int step) {
        Result r = Strategies.draw(d -> tryMutation(d, program, name, step), seed);
        return new Mutated(program, r.name(), r.child());
    }

    public static Mutated mutate(Program program, long seed) { return mutate(program, seed, null, 1); }

    /** A base program and up to {@code steps} mutations from one seed. */
    public static Lineage lineage(long seed, int steps, String template, GenOptions options) {
        Program base = Strategies.draw(programs(options, template), seed).program();
        return Strategies.draw(evolved(options, steps, base, null), seed);
    }

    public static Lineage lineage(long seed, int steps) { return lineage(seed, steps, null, GenOptions.DEFAULT); }
}
