// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/Base/ProgramBuilder.swift at commit be2811d00282320381fc8007d64873745772011d:
// the emitting core (emit/internalAppend/analyze, 2868-2902, 7863-8153), hidden variables
// (1444-1503, 7920-7957), the block helpers (3079-3086, 3873-3991, 4450-4685) and the
// ObjectLiteral/SwitchBlock helper classes (2980-3076, 4474-4509). Randomness is left out: the
// builder never draws; generators draw and the builder emits (docs/design-generation-layer.md §6, §18).
// Ported from oxpecker/il/builder.py. See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import dev.oxpecker.il.Ops.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The program builder: emits instructions, tracks scopes, context, strictness, hidden variables
 * and types as it goes, and answers the queries generators make (pools of visible variables,
 * types, the Rhino model).
 *
 * <h2>Mapping from builder.py</h2>
 * <ul>
 * <li><b>Names</b>: the Python name in camelCase ({@code load_int} → {@code loadInt},
 * {@code type_of} → {@code typeOf}, {@code do_return} → {@code doReturn}); a trailing underscore
 * stays ({@code if_}, {@code try_}, {@code with_}, {@code yield_}, {@code break_},
 * {@code continue_}, {@code else_}, {@code finally_}), and one is added where the camelCase name
 * is a Java keyword: {@code switch_}, {@code this_}, {@code void_}, {@code throw_},
 * {@code catch_}, {@code case_}, {@code default_}. Beware the Python pair kept as is:
 * {@link #typeof(int)} emits {@code typeof x}, {@link #typeOf(int)} asks the typer.</li>
 * <li><b>Variables</b> are {@code int}s; sequences of them are {@code List<Integer>} (inputs,
 * pools, the variables a block yields).</li>
 * <li><b>Keyword arguments</b>: optional booleans and kinds become overloads in the Python
 * argument order ({@code get_property(o, k, optional=True)} → {@code getProperty(o, k, true)};
 * {@code call_method(o, m, args, receiver_optional=..., call_optional=...)} →
 * {@code callMethod(o, m, args, ro, co)}); the function and method options
 * ({@code named, strict, defaults, types, recursive, generator}) are a {@link Fn} value:
 * {@code b.plain_function(Parameters(2), named=True, types=(NUMBER, NUMBER))} →
 * {@code b.plainFunction(new Parameters(2), Builder.fn().named(true).types(NUMBER, NUMBER))}.
 * {@code None} for "no value" is an absent argument ({@code do_return()} / {@code do_return(v)}).</li>
 * <li><b>Attributes</b> that generators read or assign are public fields ({@code code},
 * {@code options}, {@code depth}, {@code defaultKind}, {@code currentGen}, {@code origin},
 * {@code step}, {@code mark}, {@code storage}, {@code defs}, {@code labels}, {@code assignments},
 * {@code functionOf}, {@code typer}); Python properties are methods ({@code context()},
 * {@code strict()}, {@code inFunction()}, {@code currentFunction()}, {@code rhino()}, ...).
 * {@code b._hidden} is {@link #isHidden(int)}. "No function" is -1 ({@link Analysis#TOP_LEVEL}).</li>
 * <li><b>Blocks</b>: every Python context manager is a method returning an
 * {@link AutoCloseable} {@link Block}; {@code close()} does what leaving the {@code with} did (emit
 * the End, check the frame's protocol). What the Python {@code as} clause bound is on the
 * handle:
 * <pre>{@code
 * with b.plain_function(Parameters(1)) as (f, (p,)):      try (var fb = b.plainFunction(new Parameters(1))) {
 *     with b.if_(cond) as br:                                 int f = fb.f(), p = fb.arg(0);
 *         b.do_return(p)                                      try (var br = b.if_(cond)) {
 *         with br.else_():                                        b.doReturn(p);
 *             b.do_return(b.load_int(0))                          try (var e = br.else_()) { b.doReturn(b.loadInt(0)); }
 *                                                             }
 *                                                         }
 * }</pre>
 * Handles: {@link FunctionBlock} ({@code (f, args)}), {@link MethodBlock} ({@code (this, args)},
 * {@code this} or {@code (this, v)}), {@link VarBlock} (one variable: a label, a catch variable),
 * {@link VarsBlock} ({@code (vars, label)} of loops, the {@code (i, label)} of repeat, the
 * variables of a for-loop condition or afterthought), and the frames ({@link IfFrame},
 * {@link WhileFrame}, {@link DoWhileFrame}, {@link ForFrame}, {@link TryFrame},
 * {@link SwitchFrame}, {@link ObjectLiteralFrame}, {@link CodeStringFrame}).
 * <br><b>Exceptions</b>: Python's {@code @contextmanager}s have no {@code finally}, so an
 * exception in a body leaves the block open; try-with-resources closes it while unwinding (and
 * a frame check failing then is added as a suppressed exception). No caller uses a builder after
 * one of its generators threw (strategies.py aborts the draw, mutate.py drops the builder on
 * BuilderError), so the two behave the same.</li>
 * </ul>
 */
public final class Builder {
    private static final int HIDING_CONTEXTS = Context.SUBROUTINE | Context.OBJECT_LITERAL | Context.SWITCH_BLOCK;

    private record Frame(int fn, boolean strict, int depth) {}

    public final GenOptions options;
    /** The program so far; mutable (generators replace provenance in place, as {@code b.code[-1] = ...}). */
    public final List<Instruction> code = new ArrayList<>();
    private int nextVar;
    private final List<List<Integer>> scopes = new ArrayList<>();
    private final Set<Integer> hidden = new HashSet<>();
    private final List<List<Integer>> hiddenByScope = new ArrayList<>();
    private final List<Integer> contexts = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    public final Map<Integer, Storage> storage = new LinkedHashMap<>();
    public final Map<Integer, Integer> defs = new LinkedHashMap<>();
    public final Map<Integer, Integer> labels = new LinkedHashMap<>();
    public final Map<Integer, List<Integer>> assignments = new LinkedHashMap<>();
    /** Enclosing subroutine begin per instruction, -1 at top level. */
    public final List<Integer> functionOf = new ArrayList<>();
    public final Typer typer;
    private final List<ObjectLiteralFrame> literals = new ArrayList<>();
    private final List<SwitchFrame> switches = new ArrayList<>();
    /** Nesting budget, managed by the strategies. */
    public int depth;
    /** The kind {@link #declare(int)} uses when none is given (G25). */
    public DeclarationKind defaultKind = DeclarationKind.VAR;
    public String currentGen = "";
    public int origin = 0;
    public int step = 0;
    /** Where {@link #at} stopped replaying: {@link #newCode()} is everything after it. */
    public int mark = 0;
    private int rhinoCacheSize = -1;
    private RhinoModel rhinoCache;

    public Builder() { this(null, 0); }

    public Builder(GenOptions options) { this(options, 0); }

    public Builder(GenOptions options, int startVar) {
        this.options = options != null ? options : GenOptions.DEFAULT;
        this.nextVar = startVar;
        scopes.add(new ArrayList<>());
        hiddenByScope.add(new ArrayList<>());
        contexts.add(Context.JAVASCRIPT);
        frames.add(new Frame(Analysis.TOP_LEVEL, this.options.strict(), 0));
        typer = new Typer(code);
        depth = this.options.maxDepth();
    }

    // ------------------------------------------------------------------ state queries
    public int context() { return contexts.get(contexts.size() - 1); }

    public boolean strict() { return frames.get(frames.size() - 1).strict; }

    public boolean inFunction() { return frames.get(frames.size() - 1).fn != Analysis.TOP_LEVEL; }

    /** The begin index of the innermost open subroutine, -1 at top level. */
    public int currentFunction() { return frames.get(frames.size() - 1).fn; }

    public int nextIndex() { return code.size(); }

    public ObjectLiteralFrame currentLiteral() {
        if (literals.isEmpty()) throw new BuilderError("no open object literal");
        return literals.get(literals.size() - 1);
    }

    public SwitchFrame currentSwitch() {
        if (switches.isEmpty()) throw new BuilderError("no open switch");
        return switches.get(switches.size() - 1);
    }

    /** The typer's type of {@code v} now (not to be confused with {@link #typeof(int)}, which emits). */
    public Type typeOf(int v) { return typer.typeOf(v); }

    public RhinoModel rhino() {
        if (rhinoCache == null || rhinoCacheSize != code.size()) {
            rhinoCacheSize = code.size();
            rhinoCache = Analysis.rhinoModel(code, storage, defs, assignments, functionOf);
        }
        return rhinoCache;
    }

    public boolean inScope(int v) {
        for (List<Integer> s : scopes) if (s.contains(v)) return true;
        return false;
    }

    public boolean isHidden(int v) { return hidden.contains(v); }

    public boolean isDeclared(int v) { return Analysis.DECLARED.contains(storage.getOrDefault(v, Storage.TEMP)); }

    public boolean isAssignable(int v) { return !Analysis.NOT_ASSIGNABLE.contains(storage.getOrDefault(v, Storage.TEMP)); }

    public List<Integer> visible() { return visible(null, null); }

    /** Usable visible variables, most recent first: in scope, not hidden, not a label. Null: either. */
    public List<Integer> visible(Boolean assignable, Boolean declared) {
        List<Integer> out = new ArrayList<>();
        for (int a = scopes.size() - 1; a >= 0; a--) {
            List<Integer> scope = scopes.get(a);
            for (int k = scope.size() - 1; k >= 0; k--) {
                int v = scope.get(k);
                if (hidden.contains(v) || Analysis.NOT_A_VALUE.contains(storage.getOrDefault(v, Storage.TEMP))) continue;
                if (assignable != null && isAssignable(v) != assignable) continue;
                if (declared != null && isDeclared(v) != declared) continue;
                out.add(v);
            }
        }
        return out;
    }

    /** Visible variables that are statically of {@code t}, that may be, and all of them. */
    public record Pool(List<Integer> exact, List<Integer> maybe, List<Integer> all) {
        public Pool {
            exact = Collections.unmodifiableList(exact);
            maybe = Collections.unmodifiableList(maybe);
            all = Collections.unmodifiableList(all);
        }
    }

    public Pool pool(Type t) { return pool(t, null); }

    public Pool pool(Type t, Boolean assignable) {
        List<Integer> vs = visible(assignable, null);
        List<Integer> exact = new ArrayList<>();
        for (int v : vs) if (typeOf(v).is(t)) exact.add(v);
        List<Integer> maybe = new ArrayList<>();
        for (int v : vs) if (!exact.contains(v) && typeOf(v).mayBe(t)) maybe.add(v);
        return new Pool(exact, maybe, vs);
    }

    public void hide(int v) {
        if (!inScope(v)) throw new BuilderError("cannot hide v" + v + ": not in scope");
        hidden.add(v);
    }

    public void unhide(int v) { hidden.remove(v); }

    // ------------------------------------------------------------------ emitting
    private int fresh() { return nextVar++; }

    public Instruction emit(Operation op) { return emit(op, List.of(), null); }

    public Instruction emit(Operation op, Lift lift) { return emit(op, List.of(), lift); }

    public Instruction emit(Operation op, List<Integer> inputs) { return emit(op, inputs, null); }

    public Instruction emit(Operation op, List<Integer> inputs, Lift lift) {
        if (inputs.size() != op.inputs())
            throw new BuilderError(op.name() + " takes " + op.inputs() + " inputs, got " + inputs.size());
        for (int v : inputs) if (!inScope(v)) throw new BuilderError("v" + v + " is not visible here (" + op.name() + ")");
        if ((op.required() & ~context()) != 0)
            throw new BuilderError(op.name() + " needs " + Context.str(op.required()) + " but the context is " + Context.str(context()));
        if (op.isBlockEnd() && !matchesOpenBlock(op)) throw new BuilderError(op.name() + " does not close the open block");
        int[] inouts = new int[inputs.size() + op.outputs() + op.innerOutputs()];
        int k = 0;
        for (int v : inputs) inouts[k++] = v;
        for (int n = 0; n < op.outputs(); n++) inouts[k++] = fresh();
        for (int n = 0; n < op.innerOutputs(); n++) inouts[k++] = fresh();
        Instruction instr = new Instruction(op, inouts, code.size(), currentGen, origin, step, lift);
        apply(instr);
        return instr;
    }

    private boolean matchesOpenBlock(Operation end) {
        int d = 0;
        for (int j = code.size() - 1; j >= 0; j--) {
            Operation op = code.get(j).op();
            if (op.isBlockEnd() && !op.isBlockStart()) {
                d++;
            } else if (op.isBlockStart()) {
                if (d == 0) return Ops.matchesEnd(op, end);
                if (!op.isBlockEnd()) d--;
            }
        }
        return false;
    }

    /** The analysers' bookkeeping for one appended instruction (ProgramBuilder.swift:7893-8153). */
    private void apply(Instruction instr) {
        Operation op = instr.op();
        int i = code.size();
        code.add(instr);
        functionOf.add(frames.get(frames.size() - 1).fn);
        int[] inputs = instr.inputs();
        for (int k = 0; k < inputs.length; k++)
            if (Analysis.reassigns(op, k)) assignments.computeIfAbsent(inputs[k], x -> new ArrayList<>()).add(i);
        if (op.isBlockEnd()) {
            List<Integer> closed = scopes.remove(scopes.size() - 1);
            for (int v : closed) hidden.remove(v);
            for (int v : hiddenByScope.remove(hiddenByScope.size() - 1)) hidden.remove(v);
            contexts.remove(contexts.size() - 1);
            Frame f = frames.get(frames.size() - 1);
            if (f.depth == 0) frames.remove(frames.size() - 1);
            else frames.set(frames.size() - 1, new Frame(f.fn, f.strict, f.depth - 1));
        }
        Frame f = frames.get(frames.size() - 1);
        for (int v : instr.outputs()) {
            defs.put(v, i);
            assignments.put(v, new ArrayList<>(List.of(i)));
            storage.put(v, Analysis.outputStorage(op, f.fn != Analysis.TOP_LEVEL, f.depth > 0));
            scopes.get(scopes.size() - 1).add(v);
        }
        if (op.isBlockStart()) {
            int nw = op.opened();
            if (op.propagatesContext()) nw |= contexts.get(contexts.size() - 1);
            if (op.resumesContext()) nw |= contexts.get(contexts.size() - 2);
            if ((op.opened() & (Context.SWITCH_BLOCK | Context.SWITCH_CASE)) != 0) nw &= ~Context.LOOP;
            else if ((op.opened() & Context.LOOP) != 0) nw &= ~(Context.SWITCH_BLOCK | Context.SWITCH_CASE);
            contexts.add(nw);
            scopes.add(new ArrayList<>());
            List<Integer> hiddenNow = new ArrayList<>();
            if ((op.opened() & HIDING_CONTEXTS) != 0) {
                for (int a = 0; a < scopes.size() - 1; a++) {
                    for (int v : scopes.get(a)) {
                        if (storage.get(v) == Storage.LABEL && !hidden.contains(v)) {
                            hidden.add(v);
                            hiddenNow.add(v);
                        }
                    }
                }
            }
            hiddenByScope.add(hiddenNow);
            if (Ops.isAny(op, Ops.SUBROUTINE_BEGINS)) frames.add(new Frame(i, f.strict || Typer.strictOf(op), 0));
            else frames.set(frames.size() - 1, new Frame(f.fn, f.strict, f.depth + 1));
        }
        int[] inner = instr.innerOutputs();
        for (int k = 0; k < inner.length; k++) {
            int v = inner[k];
            defs.put(v, i);
            assignments.put(v, new ArrayList<>(List.of(i)));
            Storage st = Analysis.innerStorage(op, k);
            storage.put(v, st);
            scopes.get(scopes.size() - 1).add(v);
            if (st == Storage.LABEL) labels.put(v, i);
        }
        typer.step(i, instr);
    }

    public List<Instruction> finish() {
        if (scopes.size() != 1) throw new BuilderError((scopes.size() - 1) + " block(s) left open");
        List<Instruction> out = List.copyOf(code);
        Program.check(out);
        return out;
    }

    /**
     * A builder positioned to emit before {@code code[pos]}: replays {@code code[:pos]} as it is.
     * New variables are numbered above every variable of the whole program, so the caller
     * renumbers after splicing the new instructions back in.
     */
    public static Builder at(List<Instruction> code, int pos, GenOptions options) {
        int top = -1;
        for (Instruction instr : code) for (int v : instr.inouts()) top = Math.max(top, v);
        Builder b = new Builder(options, top + 1);
        for (Instruction instr : code.subList(0, pos)) b.apply(instr.withIndex(b.code.size()));
        b.mark = b.code.size();
        return b;
    }

    public static Builder at(List<Instruction> code, int pos) { return at(code, pos, null); }

    public List<Instruction> newCode() { return List.copyOf(code.subList(mark, code.size())); }

    public Map<Integer, Integer> replay(int start, int end) { return replay(start, end, null); }

    /**
     * Re-emit code[start:end] with fresh variables ("the same body twice", D22/D31): inputs are
     * mapped through {@code varMap} (identity for variables defined outside the range), outputs
     * get fresh variables, provenance is kept. Returns the mapping.
     */
    public Map<Integer, Integer> replay(int start, int end, Map<Integer, Integer> varMap) {
        Map<Integer, Integer> mapping = new LinkedHashMap<>(varMap == null ? Map.of() : varMap);
        for (Instruction instr : new ArrayList<>(code.subList(start, end))) {
            List<Integer> inputs = new ArrayList<>();
            for (int v : instr.inputs()) inputs.add(mapping.getOrDefault(v, v));
            Instruction nw = emit(instr.op(), inputs, instr.lift());
            int[] olds = instr.allOutputs(), news = nw.allOutputs();
            for (int k = 0; k < Math.min(olds.length, news.length); k++) mapping.put(olds[k], news[k]);
            code.set(code.size() - 1, nw.withProvenance(instr.gen(), instr.origin(), nw.step()));
        }
        return mapping;
    }

    // ------------------------------------------------------------------ value constructors
    public int loadInt(long value) { return loadInt(value, Radix.DEC); }

    public int loadInt(long value, Radix radix) { return emit(new LoadInteger(value, radix)).output(); }

    public int loadFloat(double value) { return loadFloat(value, FloatStyle.PLAIN); }

    public int loadFloat(double value, FloatStyle style) { return emit(new LoadFloat(value, style)).output(); }

    public int loadBigInt(BigInteger value) { return emit(new LoadBigInt(value)).output(); }

    public int loadBigInt(long value) { return loadBigInt(BigInteger.valueOf(value)); }

    public int loadString(String value) { return emit(new LoadString(value)).output(); }

    public int loadBool(boolean value) { return emit(new LoadBoolean(value)).output(); }

    public int loadUndefined() { return emit(new LoadUndefined()).output(); }

    public int loadNull() { return emit(new LoadNull()).output(); }

    public int loadRegExp(String pattern) { return loadRegExp(pattern, RegExpFlag.NONE); }

    public int loadRegExp(String pattern, Flags flags) { return emit(new LoadRegExp(pattern, flags)).output(); }

    public int builtin(Builtin b) { return emit(new LoadBuiltin(b)).output(); }

    public int this_() { return emit(new LoadThis()).output(); }

    public int arguments() { return emit(new LoadArguments()).output(); }

    public int newTarget() { return emit(new LoadNewTarget()).output(); }

    /**
     * {@code var|let|const v = value} of the builder's {@link #defaultKind}: the strategies draw
     * it once per generator invocation (G25). A generator that reassigns what it declares passes
     * a mutable kind explicitly (a drawn const would not verify).
     */
    public int declare(int value) { return declare(value, null); }

    public int declare(int value, DeclarationKind kind) {
        return emit(new Declare(kind != null ? kind : defaultKind), List.of(value)).output();
    }

    public int namedVariable(Key name, NamedVariableMode mode, int value) {
        return emit(new CreateNamedVariable(name, mode), List.of(value)).output();
    }

    // ------------------------------------------------------------------ operators
    public int unary(UnaryOperator op, int x) { return emit(new UnaryOperation(op), List.of(x)).output(); }

    public int binary(int x, int y, BinaryOperator op) { return emit(new BinaryOperation(op), List.of(x, y)).output(); }

    public int compare(int x, int y, CompareOperator op) { return emit(new Compare(op), List.of(x, y)).output(); }

    public int ternary(int cond, int a, int b) { return emit(new TernaryOperation(), List.of(cond, a, b)).output(); }

    /** Emits {@code typeof x} (not to be confused with {@link #typeOf(int)}, the typer query). */
    public int typeof(int x) { return emit(new TypeOf(), List.of(x)).output(); }

    public int void_(int x) { return emit(new Ops.Void(), List.of(x)).output(); }

    public int testIn(int key, int obj) { return emit(new TestIn(), List.of(key, obj)).output(); }

    public int testInstanceOf(int x, int ctor) { return emit(new TestInstanceOf(), List.of(x, ctor)).output(); }

    public void reassign(int target, int value) { emit(new Reassign(), List.of(target, value)); }

    public void update(int target, int value, BinaryOperator op) { emit(new Update(op), List.of(target, value)); }

    // ------------------------------------------------------------------ properties
    public int getProperty(int obj, Key key) { return getProperty(obj, key, false); }

    public int getProperty(int obj, Key key, boolean optional) { return emit(new GetProperty(key, optional), List.of(obj)).output(); }

    public void setProperty(int obj, Key key, int value) { emit(new SetProperty(key), List.of(obj, value)); }

    public void updateProperty(int obj, Key key, int value, BinaryOperator op) { emit(new UpdateProperty(key, op), List.of(obj, value)); }

    public int deleteProperty(int obj, Key key) { return deleteProperty(obj, key, false); }

    public int deleteProperty(int obj, Key key, boolean optional) {
        return emit(new DeleteProperty(key, optional), List.of(obj)).output();
    }

    public void configureProperty(int obj, Key key, Flags flags, PropertyType kind, int... values) {
        emit(new ConfigureProperty(key, flags, kind), cons(obj, values));
    }

    public int getElement(int obj, long index) { return getElement(obj, index, false); }

    public int getElement(int obj, long index, boolean optional) { return emit(new GetElement(index, optional), List.of(obj)).output(); }

    public void setElement(int obj, long index, int value) { emit(new SetElement(index), List.of(obj, value)); }

    public void updateElement(int obj, long index, int value, BinaryOperator op) { emit(new UpdateElement(index, op), List.of(obj, value)); }

    public int deleteElement(int obj, long index) { return deleteElement(obj, index, false); }

    public int deleteElement(int obj, long index, boolean optional) {
        return emit(new DeleteElement(index, optional), List.of(obj)).output();
    }

    public void configureElement(int obj, long index, Flags flags, PropertyType kind, int... values) {
        emit(new ConfigureElement(index, flags, kind), cons(obj, values));
    }

    public int getComputed(int obj, int key) { return getComputed(obj, key, false); }

    public int getComputed(int obj, int key, boolean optional) {
        return emit(new GetComputedProperty(optional), List.of(obj, key)).output();
    }

    public void setComputed(int obj, int key, int value) { emit(new SetComputedProperty(), List.of(obj, key, value)); }

    public void updateComputed(int obj, int key, int value, BinaryOperator op) {
        emit(new UpdateComputedProperty(op), List.of(obj, key, value));
    }

    public int deleteComputed(int obj, int key) { return deleteComputed(obj, key, false); }

    public int deleteComputed(int obj, int key, boolean optional) {
        return emit(new DeleteComputedProperty(optional), List.of(obj, key)).output();
    }

    public void configureComputed(int obj, int key, Flags flags, PropertyType kind, int... values) {
        List<Integer> ins = new ArrayList<>(List.of(obj, key));
        for (int v : values) ins.add(v);
        emit(new ConfigureComputedProperty(flags, kind), ins);
    }

    // ------------------------------------------------------------------ calls
    public int callFunction(int f) { return callFunction(f, List.of(), false); }

    public int callFunction(int f, List<Integer> args) { return callFunction(f, args, false); }

    public int callFunction(int f, List<Integer> args, boolean optional) {
        return emit(new CallFunction(args.size(), optional), cons(f, args)).output();
    }

    public int callFunctionSpread(int f, List<Integer> args, List<Boolean> spreads) { return callFunctionSpread(f, args, spreads, false); }

    public int callFunctionSpread(int f, List<Integer> args, List<Boolean> spreads, boolean optional) {
        return emit(new CallFunctionWithSpread(spreads, optional), cons(f, args)).output();
    }

    public int construct(int ctor) { return construct(ctor, List.of()); }

    public int construct(int ctor, List<Integer> args) { return emit(new Construct(args.size()), cons(ctor, args)).output(); }

    public int constructSpread(int ctor, List<Integer> args, List<Boolean> spreads) {
        return emit(new ConstructWithSpread(spreads), cons(ctor, args)).output();
    }

    public int callMethod(int obj, Method method) { return callMethod(obj, method, List.of(), false, false); }

    public int callMethod(int obj, Method method, List<Integer> args) { return callMethod(obj, method, args, false, false); }

    public int callMethod(int obj, Method method, List<Integer> args, boolean receiverOptional) {
        return callMethod(obj, method, args, receiverOptional, false);
    }

    public int callMethod(int obj, Method method, List<Integer> args, boolean receiverOptional, boolean callOptional) {
        return emit(new CallMethod(method, args.size(), receiverOptional, callOptional), cons(obj, args)).output();
    }

    public int callMethodSpread(int obj, Method method, List<Integer> args, List<Boolean> spreads) {
        return callMethodSpread(obj, method, args, spreads, false, false);
    }

    public int callMethodSpread(int obj, Method method, List<Integer> args, List<Boolean> spreads, boolean receiverOptional,
                                boolean callOptional) {
        return emit(new CallMethodWithSpread(method, spreads, receiverOptional, callOptional), cons(obj, args)).output();
    }

    public int callComputedMethod(int obj, int key) { return callComputedMethod(obj, key, List.of(), false, false); }

    public int callComputedMethod(int obj, int key, List<Integer> args) { return callComputedMethod(obj, key, args, false, false); }

    public int callComputedMethod(int obj, int key, List<Integer> args, boolean receiverOptional, boolean callOptional) {
        List<Integer> ins = new ArrayList<>(List.of(obj, key));
        ins.addAll(args);
        return emit(new CallComputedMethod(args.size(), receiverOptional, callOptional), ins).output();
    }

    public int callComputedMethodSpread(int obj, int key, List<Integer> args, List<Boolean> spreads) {
        return callComputedMethodSpread(obj, key, args, spreads, false, false);
    }

    public int callComputedMethodSpread(int obj, int key, List<Integer> args, List<Boolean> spreads, boolean receiverOptional,
                                        boolean callOptional) {
        List<Integer> ins = new ArrayList<>(List.of(obj, key));
        ins.addAll(args);
        return emit(new CallComputedMethodWithSpread(spreads, receiverOptional, callOptional), ins).output();
    }

    public int callSuperMethod(Key key) { return callSuperMethod(key, List.of()); }

    public int callSuperMethod(Key key, List<Integer> args) { return emit(new CallSuperMethod(key, args.size()), args).output(); }

    public int getSuperProperty(Key key) { return emit(new GetSuperProperty(key)).output(); }

    public void setSuperProperty(Key key, int value) { emit(new SetSuperProperty(key), List.of(value)); }

    public void updateSuperProperty(Key key, int value, BinaryOperator op) { emit(new UpdateSuperProperty(key, op), List.of(value)); }

    public int getComputedSuperProperty(int key) { return emit(new GetComputedSuperProperty(), List.of(key)).output(); }

    public void setComputedSuperProperty(int key, int value) { emit(new SetComputedSuperProperty(), List.of(key, value)); }

    // ------------------------------------------------------------------ arrays, strings, destructuring
    public int createArray() { return createArray(List.of()); }

    public int createArray(List<Integer> elements) { return emit(new CreateArray(elements.size()), elements).output(); }

    public int createArraySpread(List<Integer> elements, List<Boolean> spreads) {
        return emit(new CreateArrayWithSpread(spreads), elements).output();
    }

    public int createIntArray(List<Long> values) { return emit(new CreateIntArray(values)).output(); }

    public int createFloatArray(List<Double> values) { return emit(new CreateFloatArray(values)).output(); }

    public int createTemplate(List<String> parts, List<Integer> values) { return emit(new CreateTemplateString(parts), values).output(); }

    public List<Integer> destruct(int source, Patterns.Pattern pattern) { return destruct(source, pattern, DeclarationKind.VAR, List.of()); }

    public List<Integer> destruct(int source, Patterns.Pattern pattern, DeclarationKind kind) {
        return destruct(source, pattern, kind, List.of());
    }

    public List<Integer> destruct(int source, Patterns.Pattern pattern, DeclarationKind kind, List<Integer> extra) {
        return Typer.ints(emit(new Destruct(pattern, kind), cons(source, extra)).outputs());
    }

    public void destructAndReassign(int source, Patterns.Pattern pattern, List<Integer> targets) {
        destructAndReassign(source, pattern, targets, List.of());
    }

    public void destructAndReassign(int source, Patterns.Pattern pattern, List<Integer> targets, List<Integer> extra) {
        List<Integer> ins = new ArrayList<>(List.of(source));
        ins.addAll(targets);
        ins.addAll(extra);
        emit(new DestructAndReassign(pattern), ins);
    }

    // ------------------------------------------------------------------ statements
    public void doReturn() { emit(new Return(false)); }

    public void doReturn(int value) { emit(new Return(true), List.of(value)); }

    public int yield_() { return emit(new Yield(false)).output(); }

    public int yield_(int value) { return emit(new Yield(true), List.of(value)).output(); }

    public void yieldEach(int value) { emit(new YieldEach(), List.of(value)); }

    public void throw_(int value) { emit(new ThrowException(), List.of(value)); }

    public void directiveStrict() { emit(new Directive()); }

    public void probe() { probe(ProbeLabel.STEP, 0); }

    public void probe(ProbeLabel label) { probe(label, 0); }

    public void probe(ProbeLabel label, int number) { emit(new Probe(label, number)); }

    public void break_() { emit(new LoopBreak(false)); }

    public void break_(int label) { emit(new LoopBreak(true), List.of(label)); }

    public void continue_() { emit(new LoopContinue(false)); }

    public void continue_(int label) { emit(new LoopContinue(true), List.of(label)); }

    public void blockBreak(int label) { emit(new BlockBreak(), List.of(label)); }

    public void switchBreak() { emit(new SwitchBreak()); }

    // ------------------------------------------------------------------ function options
    /**
     * The keyword options of the function and method blocks (Python's {@code named=}, {@code strict=},
     * {@code defaults=}, {@code types=}, {@code recursive=}, {@code generator=}). Immutable; start
     * from {@link Builder#fn()}.
     */
    public record Fn(boolean named, boolean strict, List<Integer> defaults, List<Type> types, boolean recursive, boolean generator) {
        public static final Fn DEFAULT = new Fn(false, false, List.of(), null, false, false);

        public Fn {
            defaults = List.copyOf(defaults);
            types = types == null ? null : List.copyOf(types);
        }

        public Fn named(boolean v) { return new Fn(v, strict, defaults, types, recursive, generator); }
        public Fn strict(boolean v) { return new Fn(named, v, defaults, types, recursive, generator); }
        public Fn defaults(List<Integer> v) { return new Fn(named, strict, v, types, recursive, generator); }
        public Fn defaults(Integer... v) { return defaults(Arrays.asList(v)); }
        public Fn types(List<Type> v) { return new Fn(named, strict, defaults, v, recursive, generator); }
        public Fn types(Type... v) { return types(Arrays.asList(v)); }
        public Fn recursive(boolean v) { return new Fn(named, strict, defaults, types, v, generator); }
        public Fn generator(boolean v) { return new Fn(named, strict, defaults, types, recursive, v); }

        List<Type> typesOrEmpty() { return types == null ? List.of() : types; }
    }

    public static Fn fn() { return Fn.DEFAULT; }

    // ------------------------------------------------------------------ blocks
    /** A {@code with} block: {@link #close()} does what leaving it did, once. */
    public static class Block implements AutoCloseable {
        private boolean closed;

        /** What leaving the block does (emit the End, check the protocol). */
        protected void end() {}

        @Override public final void close() {
            if (closed) return;
            closed = true;
            end();
        }

        public boolean isClosed() { return closed; }
    }

    private static Block onClose(Runnable r) {
        return new Block() {
            @Override protected void end() { r.run(); }
        };
    }

    /** A block that yields one variable: a label ({@code else_}, a while body, a block statement) or a catch variable. */
    public static class VarBlock extends Block {
        private final int var;
        private final Runnable onEnd;

        VarBlock(int var, Runnable onEnd) {
            this.var = var;
            this.onEnd = onEnd;
        }

        public int var() { return var; }

        public int label() { return var; }

        @Override protected void end() { if (onEnd != null) onEnd.run(); }
    }

    /** A block that yields variables and (for loop bodies) a label: Python's {@code (vars, label)}. */
    public static class VarsBlock extends Block {
        private final List<Integer> vars;
        private final int label;
        private final Runnable onEnd;

        VarsBlock(List<Integer> vars, int label, Runnable onEnd) {
            this.vars = Collections.unmodifiableList(new ArrayList<>(vars));
            this.label = label;
            this.onEnd = onEnd;
        }

        public List<Integer> vars() { return vars; }

        public int var(int k) { return vars.get(k); }

        /** The loop label; -1 for a for-loop condition or afterthought (which yield no label). */
        public int label() { return label; }

        /** A repeat loop's counter, -1 when it has none (Python's {@code None}). */
        public int counter() { return vars.isEmpty() ? -1 : vars.get(0); }

        @Override protected void end() { if (onEnd != null) onEnd.run(); }
    }

    /** A function block: Python's {@code (f, args)}. */
    public static final class FunctionBlock extends Block {
        private final Builder b;
        private final int f;
        private final List<Integer> args;
        private final Operation end;

        FunctionBlock(Builder b, int f, List<Integer> args, Operation end) {
            this.b = b;
            this.f = f;
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
            this.end = end;
        }

        public int f() { return f; }

        public List<Integer> args() { return args; }

        public int arg(int k) { return args.get(k); }

        @Override protected void end() {
            b.unhide(f);
            b.emit(end);
        }
    }

    /** An object literal method, getter or setter: Python's {@code (this, args)}, {@code this}, {@code (this, v)}. */
    public static final class MethodBlock extends Block {
        private final Builder b;
        private final int self;
        private final List<Integer> args;
        private final Operation end;

        MethodBlock(Builder b, int self, List<Integer> args, Operation end) {
            this.b = b;
            this.self = self;
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
            this.end = end;
        }

        public int this_() { return self; }

        public List<Integer> args() { return args; }

        public int arg(int k) { return args.get(k); }

        /** A setter's value parameter. */
        public int value() { return args.get(0); }

        @Override protected void end() { b.emit(end); }
    }

    private FunctionBlock function(Operation begin, Fn opts, Operation end) {
        Instruction instr = emit(begin, opts.defaults());
        int f = instr.output();
        if (!opts.recursive()) hide(f);
        return new FunctionBlock(this, f, Typer.ints(instr.innerOutputs()), end);
    }

    public FunctionBlock plainFunction() { return plainFunction(Parameters.NONE, Fn.DEFAULT); }

    public FunctionBlock plainFunction(Parameters params) { return plainFunction(params, Fn.DEFAULT); }

    public FunctionBlock plainFunction(Fn opts) { return plainFunction(Parameters.NONE, opts); }

    public FunctionBlock plainFunction(Parameters params, Fn opts) {
        if (opts.generator()) throw new IllegalArgumentException("plainFunction takes no generator option");
        return function(new BeginPlainFunction(params, opts.typesOrEmpty(), opts.named(), opts.strict()), opts, new EndPlainFunction());
    }

    public FunctionBlock arrowFunction() { return arrowFunction(Parameters.NONE, Fn.DEFAULT); }

    public FunctionBlock arrowFunction(Parameters params) { return arrowFunction(params, Fn.DEFAULT); }

    public FunctionBlock arrowFunction(Parameters params, Fn opts) {
        if (opts.named() || opts.strict() || opts.generator())
            throw new IllegalArgumentException("arrowFunction takes only defaults, types and recursive");
        return function(new BeginArrowFunction(params, opts.typesOrEmpty()), opts, new EndArrowFunction());
    }

    public FunctionBlock generatorFunction() { return generatorFunction(Parameters.NONE, Fn.DEFAULT); }

    public FunctionBlock generatorFunction(Parameters params) { return generatorFunction(params, Fn.DEFAULT); }

    public FunctionBlock generatorFunction(Fn opts) { return generatorFunction(Parameters.NONE, opts); }

    public FunctionBlock generatorFunction(Parameters params, Fn opts) {
        if (opts.generator()) throw new IllegalArgumentException("generatorFunction takes no generator option");
        return function(new BeginGeneratorFunction(params, opts.typesOrEmpty(), opts.named(), opts.strict()), opts,
                new EndGeneratorFunction());
    }

    public ObjectLiteralFrame objectLiteral() {
        emit(new BeginObjectLiteral());
        ObjectLiteralFrame frame = new ObjectLiteralFrame(this);
        literals.add(frame);
        return frame;
    }

    public IfFrame if_(int cond) { return if_(cond, false); }

    public IfFrame if_(int cond, boolean inverted) {
        Instruction instr = emit(new BeginIf(inverted), List.of(cond));
        return new IfFrame(this, instr.innerOutput());
    }

    public WhileFrame whileLoop() {
        emit(new BeginWhileLoopHeader());
        return new WhileFrame(this);
    }

    public DoWhileFrame doWhileLoop() {
        Instruction instr = emit(new BeginDoWhileLoopBody());
        return new DoWhileFrame(this, instr.innerOutput());
    }

    public ForFrame forLoop() {
        emit(new BeginForLoopInitializer());
        return new ForFrame(this);
    }

    public VarsBlock forEach(ForEachKind kind, int iterable) { return forEach(kind, iterable, null, List.of(), DeclarationKind.VAR); }

    public VarsBlock forEach(ForEachKind kind, int iterable, DeclarationKind declaration) {
        return forEach(kind, iterable, null, List.of(), declaration);
    }

    public VarsBlock forEach(ForEachKind kind, int iterable, Patterns.Pattern pattern, List<Integer> extra) {
        return forEach(kind, iterable, pattern, extra, DeclarationKind.VAR);
    }

    public VarsBlock forEach(ForEachKind kind, int iterable, Patterns.Pattern pattern, List<Integer> extra, DeclarationKind declaration) {
        Instruction instr = emit(new BeginForEachLoop(kind, pattern, declaration), cons(iterable, extra));
        List<Integer> inner = Typer.ints(instr.innerOutputs());
        return new VarsBlock(inner.subList(0, inner.size() - 1), inner.get(inner.size() - 1), () -> emit(new EndForEachLoop()));
    }

    public VarsBlock repeat(long iterations) { return repeat(iterations, true, DeclarationKind.LET); }

    public VarsBlock repeat(long iterations, DeclarationKind declaration) { return repeat(iterations, true, declaration); }

    public VarsBlock repeat(long iterations, boolean counter) { return repeat(iterations, counter, DeclarationKind.LET); }

    public VarsBlock repeat(long iterations, boolean counter, DeclarationKind declaration) {
        Instruction instr = emit(new BeginRepeatLoop(iterations, counter, declaration));
        int[] inner = instr.innerOutputs();
        return new VarsBlock(counter ? List.of(inner[0]) : List.of(), inner[inner.length - 1], () -> emit(new EndRepeatLoop()));
    }

    public TryFrame try_() {
        emit(new BeginTry());
        return new TryFrame(this);
    }

    public SwitchFrame switch_(int value) {
        Instruction instr = emit(new BeginSwitch(), List.of(value));
        SwitchFrame frame = new SwitchFrame(this, instr.innerOutput());
        switches.add(frame);
        return frame;
    }

    public VarBlock block() {
        Instruction instr = emit(new BeginBlockStatement());
        return new VarBlock(instr.innerOutput(), () -> emit(new EndBlockStatement()));
    }

    public Block with_(int obj) {
        emit(new BeginWith(), List.of(obj));
        return onClose(() -> emit(new EndWith()));
    }

    public CodeStringFrame codeString() {
        Instruction instr = emit(new BeginCodeString());
        return new CodeStringFrame(this, instr.output());
    }

    // ------------------------------------------------------------------ frames
    /** {@code result()}: the string variable, usable after the block. */
    public static final class CodeStringFrame extends Block {
        private final Builder b;
        private final int result;

        CodeStringFrame(Builder b, int result) {
            this.b = b;
            this.result = result;
        }

        public int result() { return result; }

        @Override protected void end() { b.emit(new EndCodeString()); }
    }

    public static final class IfFrame extends Block {
        private final Builder b;
        private final int label;
        private boolean hasElse = false;

        IfFrame(Builder b, int label) {
            this.b = b;
            this.label = label;
        }

        public int label() { return label; }

        public boolean hasElse() { return hasElse; }

        /** Opened inside the if body's block (as in the Python); its close emits nothing. */
        public VarBlock else_() {
            if (hasElse) throw new BuilderError("an if has one else");
            hasElse = true;
            Instruction instr = b.emit(new BeginElse());
            return new VarBlock(instr.innerOutput(), null);
        }

        @Override protected void end() { b.emit(new EndIf()); }
    }

    public static final class WhileFrame extends Block {
        private final Builder b;
        private boolean opened = false;

        WhileFrame(Builder b) { this.b = b; }

        public boolean opened() { return opened; }

        public VarBlock body(int cond) {
            if (opened) throw new BuilderError("one body per while loop");
            opened = true;
            Instruction instr = b.emit(new BeginWhileLoopBody(), List.of(cond));
            return new VarBlock(instr.innerOutput(), null);
        }

        @Override protected void end() {
            if (!opened) throw new BuilderError("a while loop needs loop.body(cond)");
            b.emit(new EndWhileLoop());
        }
    }

    public static final class DoWhileFrame extends Block {
        private final Builder b;
        private final int label;
        private Integer cond = null;
        private boolean header = false;

        DoWhileFrame(Builder b, int label) {
            this.b = b;
            this.label = label;
        }

        public int label() { return label; }

        /** Python yields the frame itself; here a handle whose {@code done} is the frame's. */
        public DoWhileHeader header() {
            if (header) throw new BuilderError("one header per do-while loop");
            header = true;
            b.emit(new BeginDoWhileLoopHeader());
            return new DoWhileHeader(this);
        }

        public void done(int cond) { this.cond = cond; }

        @Override protected void end() {
            if (cond == null) throw new BuilderError("a do-while loop needs loop.header() with done(cond)");
            b.emit(new EndDoWhileLoop(), List.of(cond));
        }
    }

    public static final class DoWhileHeader extends Block {
        private final DoWhileFrame frame;

        DoWhileHeader(DoWhileFrame frame) { this.frame = frame; }

        public void done(int cond) { frame.done(cond); }
    }

    /**
     * {@code try (var loop = b.forLoop())} then {@code loop.condition(initial)},
     * {@code loop.afterthought(cond)}, {@code loop.body()} in that order; the initial values are
     * computed inside the {@code forLoop} block.
     */
    public static final class ForFrame extends Block {
        private final Builder b;
        private int stage = 0;
        private int n = 0;

        ForFrame(Builder b) { this.b = b; }

        public int stage() { return stage; }

        public VarsBlock condition(List<Integer> initial) { return condition(initial, DeclarationKind.LET); }

        public VarsBlock condition(List<Integer> initial, DeclarationKind declaration) {
            if (stage != 0) throw new BuilderError("condition comes first");
            stage = 1;
            n = initial.size();
            Instruction instr = b.emit(new BeginForLoopCondition(n, declaration), initial);
            return new VarsBlock(Typer.ints(instr.innerOutputs()), -1, null);
        }

        public VarsBlock afterthought(int cond) {
            if (stage != 1) throw new BuilderError("afterthought comes after the condition");
            stage = 2;
            Instruction instr = b.emit(new BeginForLoopAfterthought(n), List.of(cond));
            return new VarsBlock(Typer.ints(instr.innerOutputs()), -1, null);
        }

        public VarsBlock body() {
            if (stage != 2) throw new BuilderError("body comes after the afterthought");
            stage = 3;
            Instruction instr = b.emit(new BeginForLoopBody(n));
            List<Integer> inner = Typer.ints(instr.innerOutputs());
            return new VarsBlock(inner.subList(0, inner.size() - 1), inner.get(inner.size() - 1), null);
        }

        @Override protected void end() {
            if (stage != 3) throw new BuilderError("a for loop needs condition, afterthought and body");
            b.emit(new EndForLoop());
        }
    }

    public static final class TryFrame extends Block {
        private final Builder b;
        private boolean handled = false;
        private boolean hasCatch = false;
        private boolean hasFinally = false;

        TryFrame(Builder b) { this.b = b; }

        public boolean handled() { return handled; }

        /** Python's {@code catch}: yields the error variable. */
        public VarBlock catch_() {
            if (hasCatch || hasFinally) throw new BuilderError("catch comes once, before finally");
            hasCatch = handled = true;
            Instruction instr = b.emit(new BeginCatch());
            return new VarBlock(instr.innerOutput(), null);
        }

        public Block finally_() {
            if (hasFinally) throw new BuilderError("one finally per try");
            hasFinally = handled = true;
            b.emit(new BeginFinally());
            return new Block();
        }

        @Override protected void end() {
            if (!handled) throw new BuilderError("a try needs a catch or a finally");
            b.emit(new EndTryCatchFinally());
        }
    }

    public static final class SwitchFrame extends Block {
        private final Builder b;
        private final int label;
        private boolean hasDefault = false;

        SwitchFrame(Builder b, int label) {
            this.b = b;
            this.label = label;
        }

        public int label() { return label; }

        public boolean hasDefault() { return hasDefault; }

        public Block case_(int value) { return case_(value, false); }

        public Block case_(int value, boolean fallsThrough) {
            b.emit(new BeginSwitchCase(), List.of(value));
            return onClose(() -> b.emit(new EndSwitchCase(fallsThrough)));
        }

        public Block default_() { return default_(false); }

        public Block default_(boolean fallsThrough) {
            if (hasDefault) throw new BuilderError("one default per switch");
            hasDefault = true;
            b.emit(new BeginSwitchDefaultCase());
            return onClose(() -> b.emit(new EndSwitchCase(fallsThrough)));
        }

        @Override protected void end() {
            b.switches.remove(b.switches.size() - 1);
            b.emit(new EndSwitch());
        }
    }

    /** ProgramBuilder.swift:2980-3076. {@code result()} is the object, after the block. */
    public static final class ObjectLiteralFrame extends Block {
        private final Builder b;
        public final Set<Key> keys = new LinkedHashSet<>();
        public final Set<Long> elements = new LinkedHashSet<>();
        private boolean hasPrototype = false;
        private Integer result = null;

        ObjectLiteralFrame(Builder b) { this.b = b; }

        public boolean hasPrototype() { return hasPrototype; }

        public int result() {
            if (result == null) throw new BuilderError("the object literal is still open");
            return result;
        }

        public void addProperty(Key key, int value) {
            keys.add(key);
            b.emit(new ObjectLiteralAddProperty(key), List.of(value));
        }

        public void addElement(long index, int value) {
            elements.add(index);
            b.emit(new ObjectLiteralAddElement(index), List.of(value));
        }

        public void addComputed(int key, int value) { b.emit(new ObjectLiteralAddComputedProperty(), List.of(key, value)); }

        public void copyProperties(int obj) { b.emit(new ObjectLiteralCopyProperties(), List.of(obj)); }

        public void setPrototype(int proto) {
            if (hasPrototype) throw new BuilderError("one __proto__ per object literal");
            hasPrototype = true;
            b.emit(new ObjectLiteralSetPrototype(), List.of(proto));
        }

        private static void methodOptions(Fn opts) {
            if (opts.named() || opts.strict() || opts.recursive())
                throw new IllegalArgumentException("a method takes only generator, defaults and types");
        }

        public MethodBlock method(Key key) { return method(key, Parameters.NONE, Fn.DEFAULT); }

        public MethodBlock method(Key key, Parameters params) { return method(key, params, Fn.DEFAULT); }

        public MethodBlock method(Key key, Parameters params, Fn opts) {
            methodOptions(opts);
            keys.add(key);
            Instruction instr = b.emit(new BeginObjectLiteralMethod(key, params, opts.typesOrEmpty(), opts.generator()), opts.defaults());
            List<Integer> inner = Typer.ints(instr.innerOutputs());
            return new MethodBlock(b, inner.get(0), inner.subList(1, inner.size()), new EndObjectLiteralMethod());
        }

        public MethodBlock computedMethod(int key) { return computedMethod(key, Parameters.NONE, Fn.DEFAULT); }

        public MethodBlock computedMethod(int key, Parameters params) { return computedMethod(key, params, Fn.DEFAULT); }

        public MethodBlock computedMethod(int key, Parameters params, Fn opts) {
            methodOptions(opts);
            Instruction instr = b.emit(new BeginObjectLiteralComputedMethod(params, opts.typesOrEmpty(), opts.generator()),
                    cons(key, opts.defaults()));
            List<Integer> inner = Typer.ints(instr.innerOutputs());
            return new MethodBlock(b, inner.get(0), inner.subList(1, inner.size()), new EndObjectLiteralComputedMethod());
        }

        public MethodBlock getter(Key key) {
            keys.add(key);
            Instruction instr = b.emit(new BeginObjectLiteralGetter(key));
            return new MethodBlock(b, instr.innerOutput(), List.of(), new EndObjectLiteralGetter());
        }

        public MethodBlock computedGetter(int key) {
            Instruction instr = b.emit(new BeginObjectLiteralComputedGetter(), List.of(key));
            return new MethodBlock(b, instr.innerOutput(), List.of(), new EndObjectLiteralComputedGetter());
        }

        public MethodBlock setter(Key key) {
            keys.add(key);
            Instruction instr = b.emit(new BeginObjectLiteralSetter(key));
            return new MethodBlock(b, instr.innerOutput(0), List.of(instr.innerOutput(1)), new EndObjectLiteralSetter());
        }

        public MethodBlock computedSetter(int key) {
            Instruction instr = b.emit(new BeginObjectLiteralComputedSetter(), List.of(key));
            return new MethodBlock(b, instr.innerOutput(0), List.of(instr.innerOutput(1)), new EndObjectLiteralComputedSetter());
        }

        @Override protected void end() {
            b.literals.remove(b.literals.size() - 1);
            result = b.emit(new EndObjectLiteral()).output();
        }
    }

    private static List<Integer> cons(int first, List<Integer> rest) {
        List<Integer> out = new ArrayList<>(rest.size() + 1);
        out.add(first);
        out.addAll(rest);
        return out;
    }

    private static List<Integer> cons(int first, int... rest) {
        List<Integer> out = new ArrayList<>(rest.length + 1);
        out.add(first);
        for (int v : rest) out.add(v);
        return out;
    }
}
