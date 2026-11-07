// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/FuzzIL/JSTyper.swift (the branch-state typer, 3022-3371 and the per-operation
// rules) and Analyzer.swift (DefUseAnalyzer, VariableAnalyzer) at commit
// be2811d00282320381fc8007d64873745772011d, with the changes of docs/design-generation-layer.md
// §5.2 (two-pass loops, joining try/catch/finally) and the Rhino model of §5.3, which is derived
// from Rhino b95f75d: Parser.java, IRFactory.java, optimizer/Optimizer.java, optimizer/Block.java,
// optimizer/OptTransformer.java, optimizer/Codegen.java. Ported from oxpecker/il/analysis.py.
// See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.BOOLEAN;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NOTHING;
import static dev.oxpecker.il.Type.NULLISH;
import static dev.oxpecker.il.Type.NUMBER;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.PRIMITIVE;
import static dev.oxpecker.il.Type.STRING;

import dev.oxpecker.il.Ops.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Program analysis: scopes, storage kinds, def-use, flow-sensitive types, the Rhino model, and
 * {@link #verify}, which rejects what a mutation could have broken. Nothing here is stored on
 * instructions; every call recomputes from the code. The typer ({@link Typer}) is steppable so
 * the builder can run it per emitted instruction.
 *
 * <p>Port notes: Python's {@code None} for "no enclosing function" is {@link #TOP_LEVEL} (-1) in
 * {@link #functionOf()}; maps keyed by variable are insertion-ordered like Python's dicts; the
 * Python sets of the Rhino model are sorted sets here.
 */
public final class Analysis {
    public static final String RHINO_MODEL_SOURCE = "b95f75d";     // the Rhino commit the predicates of §5.3 were read from
    public static final int TOP_LEVEL = -1;

    public static final Type LABEL = Type.object("Label");
    public static final Type ARGUMENTS = Type.object("Arguments", Set.of(Keys.LENGTH_KEY));
    public static final Type GENERATOR_OBJECT = Type.object("Generator");
    public static final Type REGEXP = Type.object("RegExp");

    public static final Set<Storage> CONST_STORAGE = Collections.unmodifiableSet(
            EnumSet.of(Storage.TOP_CONST, Storage.LOCAL_CONST, Storage.BLOCK_CONST));
    public static final Set<Storage> NOT_ASSIGNABLE = Collections.unmodifiableSet(EnumSet.of(Storage.TOP_CONST,
            Storage.LOCAL_CONST, Storage.BLOCK_CONST, Storage.TEMP, Storage.LABEL, Storage.THIS, Storage.COUNTER, Storage.CODE));
    public static final Set<Storage> NOT_A_VALUE = Collections.unmodifiableSet(EnumSet.of(Storage.LABEL));
    public static final Set<Storage> DECLARED = Collections.unmodifiableSet(
            EnumSet.complementOf(EnumSet.of(Storage.TEMP, Storage.LABEL, Storage.CODE)));

    /** The block begins a loop label belongs to (break/continue targets). */
    public static final List<Class<? extends Operation>> LOOP_BODIES = List.of(BeginWhileLoopBody.class,
            BeginDoWhileLoopBody.class, BeginForLoopBody.class, BeginForEachLoop.class, BeginRepeatLoop.class);

    // ------------------------------------------------------------------------- the result
    private final List<Instruction> code;
    private final List<List<Integer>> visibleAt;
    private final List<Integer> contextAt;
    private final Map<Integer, Storage> storage;
    private final int[] defs;
    private final Map<Integer, List<Integer>> uses;
    private final Map<Integer, List<Integer>> assignments;
    private final List<Integer> functionOf;
    private final List<Boolean> strictAt;
    private final int[] labels;
    private final Type[] types;
    private final Type[] returns;
    private final RhinoModel rhino;
    private final List<Type[]> before;
    private final List<Type[]> after;
    private final int[] alias;

    private Analysis(List<Instruction> code, Structure s, TypeResult t, RhinoModel rhino) {
        this.code = code;
        this.visibleAt = s.visibleAt;
        this.contextAt = s.contextAt;
        this.storage = s.storage;
        this.defs = s.defs;
        this.uses = s.uses;
        this.assignments = s.assignments;
        this.functionOf = s.functionOf;
        this.strictAt = s.strictAt;
        this.labels = s.labels;
        this.types = Arrays.copyOf(t.joined, s.numVariables);
        this.returns = Arrays.copyOf(t.returns, s.numVariables);
        this.rhino = rhino;
        this.before = t.before;
        this.after = t.after;
        this.alias = t.alias;
    }

    public List<Instruction> code() { return code; }
    /** In-scope variables before each instruction, most recent first. */
    public List<List<Integer>> visibleAt() { return visibleAt; }
    public List<Integer> contextAt() { return contextAt; }
    public Map<Integer, Storage> storage() { return storage; }
    /** Variable -> the index of the instruction defining it; every variable of the program has one. */
    public int[] defs() { return defs; }
    public Map<Integer, List<Integer>> uses() { return uses; }
    /** Definition first, then every reassignment. */
    public Map<Integer, List<Integer>> assignments() { return assignments; }
    /** The enclosing subroutine begin index per instruction, {@link #TOP_LEVEL} outside any. */
    public List<Integer> functionOf() { return functionOf; }
    public List<Boolean> strictAt() { return strictAt; }
    /** Label variable -> the block start that defines it; -1 for other variables. */
    public int[] labels() { return labels; }
    /** Variable -> its type joined over the whole program; null where it has none. */
    public Type[] types() { return types; }
    /** Function variable -> return type; null for other variables. */
    public Type[] returns() { return returns; }
    public RhinoModel rhino() { return rhino; }

    public Set<Integer> assignable() {
        Set<Integer> out = new TreeSet<>();
        for (var e : storage.entrySet()) if (!NOT_ASSIGNABLE.contains(e.getValue())) out.add(e.getKey());
        return out;
    }

    /** Types at the end of the program (a function's full signature lives here); null where there is none. */
    public Type[] finalTypes() {
        return Arrays.copyOf(after.isEmpty() ? new Type[0] : after.get(after.size() - 1), defs.length);
    }

    private int canonical(int v) { return v < alias.length ? alias[v] : v; }

    /** {@code types[v]}, or null when {@code v} is past its end. */
    static Type typeIn(Type[] types, int v) { return v < types.length ? types[v] : null; }

    /** The type of {@code v} as seen by instruction {@code i}: before it for inputs, after it for its outputs. */
    public Type typeAt(int i, int v) {
        v = canonical(v);
        Type t = typeIn(before.get(i), v);
        if (t != null) return t;
        t = typeIn(after.get(i), v);
        return t != null ? t : ANY;
    }

    public Type typeAfter(int i, int v) {
        Type t = typeIn(after.get(i), canonical(v));
        return t != null ? t : ANY;
    }

    public int numUses(int v) { return uses.getOrDefault(v, List.of()).size(); }

    public int numAssignments(int v) { return assignments.getOrDefault(v, List.of()).size(); }

    public boolean isDeclared(int v) { return DECLARED.contains(storage.getOrDefault(v, Storage.TEMP)); }

    public List<Integer> declaredVisibleAt(int i) {
        List<Integer> out = new ArrayList<>();
        for (int v : visibleAt.get(i)) if (isDeclared(v)) out.add(v);
        return out;
    }

    /**
     * The IL with, per instruction, the types of its outputs, their storage, and the Rhino
     * model's predictions (activation reasons on functions, number variables, direct calls).
     */
    public String explain() {
        RhinoModel r = rhino;
        List<String> lines = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < code.size(); i++) {
            Instruction instr = code.get(i);
            if (instr.op().isBlockEnd()) depth = Math.max(0, depth - 1);
            List<String> notes = new ArrayList<>();
            for (int v : instr.allOutputs()) {
                Storage s = storage.getOrDefault(v, Storage.TEMP);
                String tag = "v" + v + ": " + typeAfter(i, v);
                if (s != Storage.TEMP) tag += " [" + s.name().toLowerCase() + "]";
                if (r.numberVar().contains(v)) tag += " number-var";
                if (r.directCallTarget().contains(v)) tag += " direct-call-target";
                notes.add(tag);
            }
            if (r.activation().containsKey(i)) {
                List<ActivationReason> reasons = r.activation().get(i);
                List<String> names = new ArrayList<>();
                for (ActivationReason x : reasons) names.add(x.name().toLowerCase());
                notes.add("activation: " + (!reasons.isEmpty() ? String.join(", ", names) : "none (optimizer runs)"));
            }
            if (r.directCallSite().contains(i)) notes.add("direct call");
            if (instr.lift() != null) notes.add("lift=" + instr.lift().value);
            String line = "    ".repeat(depth) + String.format("%3d  %s", i, instr);
            lines.add(line + (!notes.isEmpty() ? "    ; " + String.join("; ", notes) : ""));
            if (instr.op().isBlockStart()) depth++;
        }
        return String.join("\n", lines);
    }

    @Override public String toString() {
        return "<Analysis of " + code.size() + " instructions, " + Arrays.stream(types).filter(Objects::nonNull).count() + " variables>";
    }

    // =========================================================================== structure
    public static Storage declaredStorage(DeclarationKind kind, boolean inFunction, boolean inBlock) {
        if (kind == DeclarationKind.VAR) return inFunction ? Storage.LOCAL_VAR : Storage.TOP_VAR;
        if (kind == DeclarationKind.LET)
            return inBlock ? Storage.BLOCK_LET : (inFunction ? Storage.LOCAL_LET : Storage.TOP_LET);
        return inBlock ? Storage.BLOCK_CONST : (inFunction ? Storage.LOCAL_CONST : Storage.TOP_CONST);
    }

    public static Storage outputStorage(Operation op, boolean inFunction, boolean inBlock) {
        if (op instanceof Declare d) return declaredStorage(d.kind(), inFunction, inBlock);
        if (op instanceof Destruct d) return declaredStorage(d.kind(), inFunction, inBlock);
        if (op instanceof CreateNamedVariable) return Storage.NAMED;
        if (Ops.isAny(op, Ops.FUNCTION_BEGINS)) return Storage.FUNCTION;
        if (op instanceof BeginCodeString) return Storage.CODE;
        return Storage.TEMP;
    }

    public static Storage innerStorage(Operation op, int k) {
        if (Ops.isAny(op, Ops.FUNCTION_BEGINS)) return Storage.PARAM;
        if (op instanceof BeginObjectLiteralMethod || op instanceof BeginObjectLiteralComputedMethod
                || op instanceof BeginObjectLiteralGetter || op instanceof BeginObjectLiteralComputedGetter
                || op instanceof BeginObjectLiteralSetter || op instanceof BeginObjectLiteralComputedSetter)
            return k == 0 ? Storage.THIS : Storage.PARAM;
        if (op instanceof BeginCatch) return Storage.CATCH;
        if (op instanceof BeginForLoopCondition || op instanceof BeginForLoopAfterthought) return Storage.LOOPVAR;
        if (op instanceof BeginForLoopBody b) return k < b.numLoopVariables() ? Storage.LOOPVAR : Storage.LABEL;
        if (op instanceof BeginForEachLoop f) return k < f.innerOutputs() - 1 ? Storage.FOREACH : Storage.LABEL;
        if (op instanceof BeginRepeatLoop r) return (r.exposesCounter() && k == 0) ? Storage.COUNTER : Storage.LABEL;
        return Storage.LABEL;
    }

    /** Semantics.swift:46-56: which inputs an operation assigns. */
    public static boolean reassigns(Operation op, int k) {
        if (op instanceof Reassign || op instanceof Update) return k == 0;
        if (op instanceof UnaryOperation u) return u.op().reassignsInput();
        if (op instanceof DestructAndReassign d) return 1 <= k && k <= Patterns.numBindings(d.pattern());
        return false;
    }

    private record Frame(int fn, boolean strict, int depth) {}

    private static final class Structure {
        final List<List<Integer>> visibleAt = new ArrayList<>();
        List<Integer> contextAt;
        final Map<Integer, Storage> storage = new HashMap<>();
        int numVariables;
        int[] defs;
        final Map<Integer, List<Integer>> uses = new HashMap<>();
        final Map<Integer, List<Integer>> assignments = new HashMap<>();
        final List<Integer> functionOf = new ArrayList<>();
        final List<Boolean> strictAt = new ArrayList<>();
        int[] labels;
    }

    private static Structure structure(List<Instruction> code, boolean strict) {
        Structure s = new Structure();
        List<List<Integer>> scopes = new ArrayList<>();
        scopes.add(new ArrayList<>());
        List<Frame> frames = new ArrayList<>();
        frames.add(new Frame(TOP_LEVEL, strict, 0));
        s.contextAt = Program.contexts(code);
        int topVar = -1;
        for (Instruction instr : code) for (int v : instr.allOutputs()) topVar = Math.max(topVar, v);
        s.numVariables = topVar + 1;
        s.defs = new int[s.numVariables];
        s.labels = new int[s.numVariables];
        Arrays.fill(s.labels, -1);

        for (int i = 0; i < code.size(); i++) {
            Instruction instr = code.get(i);
            Operation op = instr.op();
            List<Integer> vis = new ArrayList<>();
            for (int a = scopes.size() - 1; a >= 0; a--) {
                List<Integer> sc = scopes.get(a);
                for (int b = sc.size() - 1; b >= 0; b--) vis.add(sc.get(b));
            }
            s.visibleAt.add(Collections.unmodifiableList(vis));
            Frame top = frames.get(frames.size() - 1);
            s.functionOf.add(top.fn);
            s.strictAt.add(top.strict);
            int[] inputs = instr.inputs();
            for (int v : inputs) s.uses.computeIfAbsent(v, x -> new ArrayList<>()).add(i);
            for (int k = 0; k < inputs.length; k++)
                if (reassigns(op, k)) s.assignments.computeIfAbsent(inputs[k], x -> new ArrayList<>()).add(i);
            if (op.isBlockEnd()) {
                scopes.remove(scopes.size() - 1);
                Frame f = frames.get(frames.size() - 1);
                if (f.depth == 0) frames.remove(frames.size() - 1);
                else frames.set(frames.size() - 1, new Frame(f.fn, f.strict, f.depth - 1));
            }
            Frame f = frames.get(frames.size() - 1);
            for (int v : instr.outputs()) {
                s.defs[v] = i;
                s.assignments.put(v, new ArrayList<>(List.of(i)));
                s.storage.put(v, outputStorage(op, f.fn != TOP_LEVEL, f.depth > 0));
                scopes.get(scopes.size() - 1).add(v);
            }
            if (op.isBlockStart()) {
                scopes.add(new ArrayList<>());
                if (Ops.isAny(op, Ops.SUBROUTINE_BEGINS)) frames.add(new Frame(i, f.strict || Typer.strictOf(op), 0));
                else frames.set(frames.size() - 1, new Frame(f.fn, f.strict, f.depth + 1));
            }
            int[] inner = instr.innerOutputs();
            for (int k = 0; k < inner.length; k++) {
                int v = inner[k];
                s.defs[v] = i;
                s.assignments.put(v, new ArrayList<>(List.of(i)));
                Storage st = innerStorage(op, k);
                s.storage.put(v, st);
                scopes.get(scopes.size() - 1).add(v);
                if (st == Storage.LABEL) s.labels[v] = i;
            }
        }
        return s;
    }

    // =========================================================================== typing rules
    private static Type maybeBigint(Type t, List<Type> inputs) {
        if (!inputs.isEmpty() && inputs.stream().allMatch(i -> i.is(BIGINT))) return BIGINT;
        if (inputs.stream().anyMatch(i -> i.mayBe(BIGINT))) return t.join(BIGINT);
        return t;
    }

    /** JSTyper.swift:1961-1988 with the string refinement for ADD. */
    public static Type binaryResult(BinaryOperator op, Type a, Type b) {
        if (op == BinaryOperator.ADD) {
            if (a.is(STRING) || b.is(STRING)) return STRING;
            if (a.is(NUMBER) && b.is(NUMBER)) return NUMBER;
            return maybeBigint(PRIMITIVE, List.of(a, b));
        }
        if (BinaryOperator.ARITHMETIC.contains(op)) return maybeBigint(NUMBER, List.of(a, b));
        if (BinaryOperator.BITWISE.contains(op)) return maybeBigint(INTEGER, List.of(a, b));
        return a.join(b);
    }

    public static Type unaryResult(UnaryOperator op, Type a) {
        if (op == UnaryOperator.LOGICAL_NOT) return BOOLEAN;
        if (op == UnaryOperator.BITWISE_NOT) return maybeBigint(INTEGER, List.of(a));
        if (op == UnaryOperator.PLUS) return NUMBER;
        return maybeBigint(NUMBER, List.of(a));
    }

    public static Type propertyType(Type recv, Key key, Map<String, Map<Key, Type>> literalProps) {
        boolean hasGroup = recv.group() != null && !recv.group().isEmpty();       // Python: `if recv.group` ("" is falsy)
        if (hasGroup && literalProps.containsKey(recv.group()) && literalProps.get(recv.group()).containsKey(key))
            return literalProps.get(recv.group()).get(key);
        if (key.equals(Keys.LENGTH_KEY) && (recv.is(STRING) || "Array".equals(recv.group()) || "TypedArray".equals(recv.group())
                || "Arguments".equals(recv.group())))
            return INTEGER;
        String group = hasGroup ? recv.group() : (recv.is(STRING) ? "String" : null);
        if (group != null) {
            for (Method m : Env.methodsOf(group)) {
                if (m.name().equals(key.name())) {
                    List<Param> ps = new ArrayList<>();
                    for (Type p : m.params()) ps.add(new Param(p));
                    return Type.function(new Signature(ps, m.returns()));
                }
            }
        }
        return ANY;
    }

    public static Type constructs(Type callee) {
        if (callee.group() != null && callee.group().endsWith("Ctor")) {      // "" never ends with "Ctor"
            String name = callee.group().substring(0, callee.group().length() - 4);
            Type t = Env.INSTANCE_TYPE.get(name);
            if (t != null) return t;
            return Type.object(Env.INSTANCE_GROUP.getOrDefault(name, name));
        }
        return OBJECT;
    }

    public static Type calls(Type callee) {
        if (callee.group() != null && callee.group().endsWith("Ctor"))
            return Env.CALLS.getOrDefault(callee.group().substring(0, callee.group().length() - 4), ANY);
        if (callee.signature() != null) return callee.signature().returns();
        return ANY;
    }

    /** Variable-wise join of type states (null: no type); a variable that joins to nothing has none. */
    static Type[] joinStates(Type[] pre, List<Type[]> states) {
        int n = pre.length;
        for (Type[] s : states) n = Math.max(n, s.length);
        Type[] out = new Type[n];
        for (int v = 0; v < n; v++) {
            Type p = typeIn(pre, v);
            boolean any = p != null;
            Type ty = p != null ? p : NOTHING;
            for (Type[] s : states) {
                Type t = typeIn(s, v);
                if (t != null) {
                    ty = ty.join(t);
                    any = true;
                }
            }
            if (any && !ty.isNothing()) out[v] = ty;
        }
        return out;
    }

    private static final Map<Class<? extends Operation>, Class<? extends Operation>> LOOP_STARTS = Map.of(
            EndWhileLoop.class, BeginWhileLoopHeader.class, EndDoWhileLoop.class, BeginDoWhileLoopBody.class,
            EndForLoop.class, BeginForLoopCondition.class, EndForEachLoop.class, BeginForEachLoop.class,
            EndRepeatLoop.class, BeginRepeatLoop.class, EndCodeString.class, BeginCodeString.class);

    /** The first block instruction of the loop ending at {@code end} whose typing a second pass repeats. */
    public static int loopPassStart(List<Instruction> code, int end) {
        Class<? extends Operation> target = LOOP_STARTS.get(code.get(end).op().getClass());
        if (target == null) throw new IllegalArgumentException("not a loop end: " + code.get(end).op().name());
        int depth = 0;
        for (int j = end - 1; j >= 0; j--) {
            Operation op = code.get(j).op();
            if (op.isBlockEnd() && !op.isBlockStart()) {
                depth++;
            } else if (op.isBlockStart() && !op.isBlockEnd()) {
                if (depth == 0) {
                    if (target.isInstance(op)) return j;
                } else {
                    depth--;
                }
            } else if (op.isBlockStart() && op.isBlockEnd() && depth == 0 && target.isInstance(op)) {
                return j;
            }
        }
        throw new InvalidProgram("block_mismatch", "loop end without its start", end);
    }

    public static List<Type> destructTypes(Patterns.Pattern pattern, Type source, Map<String, Map<Key, Type>> literalProps) {
        List<Type> out = new ArrayList<>();
        if (pattern instanceof Patterns.ObjectPattern o) {
            for (Patterns.ObjectProperty p : o.properties())
                destructTarget(out, p.target(), p.key() != null ? propertyType(source, p.key(), literalProps) : ANY, literalProps);
            if (o.hasRest()) out.add(OBJECT);
        } else {
            Patterns.ArrayPattern a = (Patterns.ArrayPattern) pattern;
            Type el = source.element() != null ? source.element() : ANY;
            for (Patterns.ArrayElement e : a.elements()) if (e.target() != null) destructTarget(out, e.target(), el, literalProps);
            if (a.rest() != null) destructTarget(out, a.rest(), a.rest() instanceof Patterns.Binding ? ARRAY : el, literalProps);
        }
        return out;
    }

    private static void destructTarget(List<Type> out, Patterns.Target tg, Type value, Map<String, Map<Key, Type>> literalProps) {
        if (tg instanceof Patterns.Binding) out.add(value);
        else if (tg instanceof Patterns.Pattern p) out.addAll(destructTypes(p, value, literalProps));
    }

    private static final class TypeResult {
        List<Type[]> before;
        List<Type[]> after;
        Type[] joined;
        Type[] returns;
        int[] alias;
    }

    /**
     * The batch typer: one {@link Typer} driven over the code, with a second pass per loop and
     * snapshots joined across passes so a type "at a point" covers a later iteration too.
     */
    private static TypeResult types(List<Instruction> code, Map<Integer, List<Type>> paramTypes) {
        int n = code.size();
        List<Type[]> before = new ArrayList<>(n);
        List<Type[]> after = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            before.add(null);
            after.add(null);
        }
        Type[] joined = new Type[0];
        Typer typer = new Typer(code, paramTypes);
        Set<Integer> loopPasses = new HashSet<>();
        int i = 0;
        while (i < n) {
            Instruction instr = code.get(i);
            record(typer, before, i);
            typer.step(i, instr);
            if (Ops.isAny(instr.op(), Typer.LOOP_ENDS) && !(instr.op() instanceof EndCodeString)) {
                int beginI = loopPassStart(code, i);
                if (loopPasses.add(beginI)) {
                    record(typer, after, i);
                    i = beginI;
                    continue;
                }
            }
            record(typer, after, i);
            Type[] snap = after.get(i);
            if (snap.length > joined.length) joined = Arrays.copyOf(joined, snap.length);
            for (int v = 0; v < snap.length; v++)
                if (snap[v] != null) joined[v] = joined[v] == null ? snap[v] : joined[v].join(snap[v]);
            i++;
        }
        TypeResult r = new TypeResult();
        r.before = before;
        r.after = after;
        r.joined = joined;
        r.returns = typer.returns();
        r.alias = typer.alias();
        for (int k = 0; k < n; k++) {         // instructions the walk never reached (it reaches them all)
            if (before.get(k) == null) before.set(k, new Type[0]);
            if (after.get(k) == null) after.set(k, new Type[0]);
        }
        return r;
    }

    private static void record(Typer typer, List<Type[]> slot, int i) {
        Type[] snap = typer.snapshot();
        Type[] seen = slot.get(i);
        slot.set(i, seen == null || isEmpty(seen) ? snap : joinStates(seen, List.<Type[]>of(snap)));
    }

    private static boolean isEmpty(Type[] state) {
        for (Type t : state) if (t != null) return false;
        return true;
    }

    // =========================================================================== Rhino model
    private static int safeBlockEnd(List<Instruction> code, int head) {
        try {
            return Program.blockEnd(code, head);
        } catch (IllegalArgumentException e) {
            return code.size();
        }
    }

    /**
     * §5.3: activation, number variables, direct calls. Works on open (unfinished) code too,
     * treating an open block as ending at the end of the code. {@code functionOf} uses
     * {@link #TOP_LEVEL} for top-level instructions.
     */
    public static RhinoModel rhinoModel(List<Instruction> code, Map<Integer, Storage> storage, int[] defs,
                                        Map<Integer, List<Integer>> assignments, List<Integer> functionOf) {
        Map<Integer, List<ActivationReason>> activation = new HashMap<>();
        List<Integer> functions = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) if (Ops.isAny(code.get(i).op(), Ops.SUBROUTINE_BEGINS)) functions.add(i);
        int[] ends = new int[code.size()];
        for (int f : functions) ends[f] = safeBlockEnd(code, f);

        for (int f : functions) {
            List<ActivationReason> reasons = new ArrayList<>();
            int j = f + 1;
            while (j < ends[f]) {
                Operation op = code.get(j).op();
                if (Ops.isAny(op, Ops.SUBROUTINE_BEGINS)) {
                    if (!reasons.contains(ActivationReason.NESTED_FUNCTION)) reasons.add(ActivationReason.NESTED_FUNCTION);
                    j = safeBlockEnd(code, j) + 1;
                    continue;
                }
                ActivationReason r = activationReason(code, j, defs);
                if (r != null && !reasons.contains(r)) reasons.add(r);
                j++;
            }
            Operation fop = code.get(f).op();
            boolean generator = fop instanceof BeginGeneratorFunction
                    || (fop instanceof BeginObjectLiteralMethod m && m.generator())
                    || (fop instanceof BeginObjectLiteralComputedMethod cm && cm.generator());
            if (generator && !reasons.contains(ActivationReason.YIELD)) reasons.add(ActivationReason.YIELD);
            activation.put(f, Collections.unmodifiableList(reasons));
        }

        Set<Integer> numberVars = new TreeSet<>();
        for (int f : functions) {
            if (!activation.get(f).isEmpty()) continue;
            Set<Integer> current = new TreeSet<>();
            for (var e : storage.entrySet())
                if (e.getValue() == Storage.LOCAL_VAR && functionOf.get(defs[e.getKey()]) == f) current.add(e.getKey());
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int v : new ArrayList<>(current)) {
                    for (int a : assignments.getOrDefault(v, List.of())) {
                        if (rhsType(code, a, v, current, storage, defs) != RhinoType.NUMBER) {
                            current.remove(v);
                            changed = true;
                            break;
                        }
                    }
                }
            }
            numberVars.addAll(current);
        }
        Set<Integer> numberVar = Collections.unmodifiableSet(numberVars);
        Map<Integer, RhinoType> exprType = new HashMap<>();
        for (int i = 0; i < code.size(); i++)
            if (code.get(i).hasValue()) exprType.put(i, exprType(code, i, numberVar, storage, defs));

        Set<Integer> targets = new TreeSet<>();
        Set<Integer> sites = new TreeSet<>();
        for (int f : functions) {
            if (!(code.get(f).op() instanceof BeginPlainFunction op && op.named() && functionOf.get(f) == TOP_LEVEL)) continue;
            if (!activation.get(f).isEmpty() || op.parameters().hasRest() || op.parameters().count() > 32) continue;
            int fvar = code.get(f).output();
            if (assignments.getOrDefault(fvar, List.of()).size() > 1) continue;
            for (int i = 0; i < code.size(); i++) {
                Instruction instr = code.get(i);
                if (instr.op() instanceof CallFunction c && instr.input(0) == fvar && functionOf.get(i) != TOP_LEVEL
                        && c.numArguments() == op.parameters().count()) {
                    sites.add(i);
                    targets.add(fvar);
                }
            }
        }
        return new RhinoModel(Collections.unmodifiableMap(activation), numberVar, Collections.unmodifiableSet(targets),
                Collections.unmodifiableSet(sites), Collections.unmodifiableMap(exprType));
    }

    private static ActivationReason activationReason(List<Instruction> code, int j, int[] defs) {
        Operation op = code.get(j).op();
        if (op instanceof BeginCatch) return ActivationReason.CATCH;
        if (op instanceof BeginWith) return ActivationReason.WITH;
        if (op instanceof LoadArguments) return ActivationReason.ARGUMENTS;
        if (op instanceof Yield || op instanceof YieldEach) return ActivationReason.YIELD;
        if (op instanceof CallSuperMethod || op instanceof GetSuperProperty || op instanceof SetSuperProperty
                || op instanceof SetComputedSuperProperty || op instanceof GetComputedSuperProperty || op instanceof UpdateSuperProperty)
            return ActivationReason.SUPER;
        if (op instanceof CallFunction || op instanceof CallFunctionWithSpread) {
            Operation callee = code.get(defs[code.get(j).input(0)]).op();
            if (callee instanceof LoadBuiltin lb && lb.builtin().name().equals("eval")) return ActivationReason.EVAL;
        }
        return null;
    }

    private static RhinoType rhsType(List<Instruction> code, int assignI, int v, Set<Integer> numberVars,
                                     Map<Integer, Storage> storage, int[] defs) {
        Instruction instr = code.get(assignI);
        Operation op = instr.op();
        if (op instanceof Declare) return varOrExpr(code, instr.input(0), numberVars, storage, defs);
        if (op instanceof Reassign) return varOrExpr(code, instr.input(1), numberVars, storage, defs);
        if (op instanceof Update u) {
            RhinoType a = varOrExpr(code, instr.input(0), numberVars, storage, defs);
            RhinoType b = varOrExpr(code, instr.input(1), numberVars, storage, defs);
            return binaryRhino(u.op(), a, b);
        }
        if (op instanceof UnaryOperation) return RhinoType.NUMBER;
        return RhinoType.ANY;
    }

    private static RhinoType varOrExpr(List<Instruction> code, int v, Set<Integer> numberVars,
                                       Map<Integer, Storage> storage, int[] defs) {
        Storage s = storage.getOrDefault(v, Storage.TEMP);
        if (s == Storage.TEMP) return exprType(code, defs[v], numberVars, storage, defs);
        return numberVars.contains(v) ? RhinoType.NUMBER : RhinoType.ANY;
    }

    private static RhinoType binaryRhino(BinaryOperator op, RhinoType a, RhinoType b) {
        if (op == BinaryOperator.ADD) {
            if (a == RhinoType.NUMBER && b == RhinoType.NUMBER) return RhinoType.NUMBER;
            return (a == RhinoType.NUMBER || b == RhinoType.NUMBER) ? RhinoType.MIXED : RhinoType.ANY;
        }
        if (BinaryOperator.ARITHMETIC.contains(op) || BinaryOperator.BITWISE.contains(op)) return RhinoType.NUMBER;
        if (op == BinaryOperator.LOGIC_AND || op == BinaryOperator.LOGIC_OR)
            return a == RhinoType.NUMBER && b == RhinoType.NUMBER ? RhinoType.NUMBER : RhinoType.ANY;
        return RhinoType.ANY;
    }

    /** Block.findExpressionType over one value-producing instruction. */
    private static RhinoType exprType(List<Instruction> code, int i, Set<Integer> numberVars,
                                      Map<Integer, Storage> storage, int[] defs) {
        Instruction instr = code.get(i);
        Operation op = instr.op();
        if (op instanceof LoadInteger || op instanceof LoadFloat) return RhinoType.NUMBER;
        if (op instanceof UnaryOperation u) return u.op() == UnaryOperator.LOGICAL_NOT ? RhinoType.ANY : RhinoType.NUMBER;
        if (op instanceof BinaryOperation bo)
            return binaryRhino(bo.op(), varOrExpr(code, instr.input(0), numberVars, storage, defs),
                    varOrExpr(code, instr.input(1), numberVars, storage, defs));
        if (op instanceof TernaryOperation) {
            RhinoType a = varOrExpr(code, instr.input(1), numberVars, storage, defs);
            RhinoType b = varOrExpr(code, instr.input(2), numberVars, storage, defs);
            return a == RhinoType.NUMBER && b == RhinoType.NUMBER ? RhinoType.NUMBER : RhinoType.ANY;
        }
        if (op instanceof Declare || op instanceof CreateNamedVariable) return varOrExpr(code, instr.input(0), numberVars, storage, defs);
        return RhinoType.ANY;
    }

    // =========================================================================== entry points
    /** Everything the analysis knows about a program. */
    public static Analysis analyse(List<Instruction> code) { return analyse(code, false, null); }

    public static Analysis analyse(List<Instruction> code, boolean strict) { return analyse(code, strict, null); }

    /** {@code paramTypes}: subroutine begin index -> declared parameter types (for begins that carry none). */
    public static Analysis analyse(List<Instruction> code, boolean strict, Map<Integer, List<Type>> paramTypes) {
        code = List.copyOf(code);
        Program.check(code);
        Structure s = structure(code, strict);
        TypeResult t = types(code, paramTypes == null ? Map.of() : paramTypes);
        RhinoModel rhino = rhinoModel(code, s.storage, s.defs, s.assignments, s.functionOf);
        return new Analysis(code, s, t, rhino);
    }

    /** Python's {@code analyse(program)}: sloppy unless {@code strict} is passed, whatever the program's options say. */
    public static Analysis analyse(Program program) { return analyse(program.code(), false, null); }

    /** {@code analyse(code).explain()}: the IL annotated with types, storage and the Rhino model. */
    public static String explain(List<Instruction> code, boolean strict) { return analyse(code, strict).explain(); }

    public static String explain(List<Instruction> code) { return explain(code, false); }

    private static final List<Class<? extends Operation>> RECEIVER_READS = List.of(GetProperty.class, DeleteProperty.class,
            GetElement.class, DeleteElement.class, GetComputedProperty.class, DeleteComputedProperty.class, CallMethod.class,
            CallMethodWithSpread.class, CallComputedMethod.class, CallComputedMethodWithSpread.class);
    private static final List<Class<? extends Operation>> RECEIVER_WRITES = List.of(SetProperty.class, UpdateProperty.class,
            SetElement.class, UpdateElement.class, SetComputedProperty.class, UpdateComputedProperty.class,
            ConfigureProperty.class, ConfigureElement.class, ConfigureComputedProperty.class);

    private static boolean receiverOptional(Operation op) {
        if (op instanceof GetProperty o) return o.receiverOptional();
        if (op instanceof DeleteProperty o) return o.receiverOptional();
        if (op instanceof GetElement o) return o.receiverOptional();
        if (op instanceof DeleteElement o) return o.receiverOptional();
        if (op instanceof GetComputedProperty o) return o.receiverOptional();
        if (op instanceof DeleteComputedProperty o) return o.receiverOptional();
        if (op instanceof CallMethod o) return o.receiverOptional();
        if (op instanceof CallMethodWithSpread o) return o.receiverOptional();
        if (op instanceof CallComputedMethod o) return o.receiverOptional();
        if (op instanceof CallComputedMethodWithSpread o) return o.receiverOptional();
        return false;
    }

    public static Analysis verify(List<Instruction> code) { return verify(code, false, null); }

    public static Analysis verify(List<Instruction> code, boolean strict) { return verify(code, strict, null); }

    /** {@code analyse} plus oxpecker's requirements (design §5.4). Throws InvalidProgram. */
    public static Analysis verify(List<Instruction> code, boolean strict, Map<Integer, List<Type>> paramTypes) {
        Analysis an = analyse(code, strict, paramTypes);
        code = an.code;
        List<Integer> literalProtos = new ArrayList<>();
        List<Integer> switchDefaults = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            Instruction instr = code.get(i);
            Operation op = instr.op();
            int[] inputs = instr.inputs();
            for (int k = 0; k < inputs.length; k++) {
                int v = inputs[k];
                Storage s = an.storage.getOrDefault(v, Storage.TEMP);
                if (reassigns(op, k) && NOT_ASSIGNABLE.contains(s))
                    throw new InvalidProgram("assignment", "v" + v + " (" + s.name() + ") is not assignable", i);
                if (an.storage.get(v) == Storage.LABEL && !(op instanceof LoopBreak || op instanceof LoopContinue || op instanceof BlockBreak))
                    throw new InvalidProgram("label", "label v" + v + " used as a value", i);
            }
            if ((op instanceof LoopBreak || op instanceof LoopContinue || op instanceof BlockBreak) && inputs.length > 0) {
                int target = instr.input(0);
                if (an.storage.get(target) != Storage.LABEL)
                    throw new InvalidProgram("label", "break/continue target is not a label", i);
                if ((op instanceof LoopBreak || op instanceof LoopContinue) && !Ops.isAny(code.get(an.labels[target]).op(), LOOP_BODIES))
                    throw new InvalidProgram("label", "loop break/continue needs a loop label", i);
            }
            if (op instanceof CallFunction || op instanceof CallFunctionWithSpread || op instanceof Construct || op instanceof ConstructWithSpread) {
                if (!an.typeAt(i, instr.input(0)).mayBe(FUNCTION))
                    throw new InvalidProgram("type", "v" + instr.input(0) + " is never a function", i);
            }
            if (Ops.isAny(op, RECEIVER_READS)) {
                if (!receiverOptional(op) && an.typeAt(i, instr.input(0)).is(NULLISH))
                    throw new InvalidProgram("type", "property access on nullish v" + instr.input(0), i);
            }
            if (Ops.isAny(op, RECEIVER_WRITES) && an.typeAt(i, instr.input(0)).is(NULLISH))
                throw new InvalidProgram("type", "property write on nullish v" + instr.input(0), i);
            if (op instanceof BeginObjectLiteral) {
                literalProtos.add(0);
            } else if (op instanceof ObjectLiteralSetPrototype) {
                int last = literalProtos.size() - 1;
                literalProtos.set(last, literalProtos.get(last) + 1);
                if (literalProtos.get(last) > 1) throw new InvalidProgram("singular", "two __proto__ entries in one object literal", i);
            } else if (op instanceof EndObjectLiteral) {
                literalProtos.remove(literalProtos.size() - 1);
            } else if (op instanceof BeginSwitch) {
                switchDefaults.add(0);
            } else if (op instanceof BeginSwitchDefaultCase) {
                int last = switchDefaults.size() - 1;
                switchDefaults.set(last, switchDefaults.get(last) + 1);
                if (switchDefaults.get(last) > 1) throw new InvalidProgram("singular", "two default cases", i);
            } else if (op instanceof EndSwitch) {
                switchDefaults.remove(switchDefaults.size() - 1);
            }
            if (op instanceof BeginWith && an.strictAt.get(i)) throw new InvalidProgram("strict", "with in strict code", i);
            if (op instanceof Directive d && d.kind() == DirectiveKind.USE_STRICT && an.functionOf.get(i) != TOP_LEVEL) {
                Parameters params = Typer.parametersOf(code.get(an.functionOf.get(i)).op());
                if (params != null && (params.hasRest() || !params.defaultIndices().isEmpty() || !params.destructuring().isEmpty()))
                    throw new InvalidProgram("strict", "a strict directive needs a simple parameter list (no rest, defaults or patterns)", i);
            }
        }
        return an;
    }
}
