// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/FuzzIL/JSTyper.swift (the branch-state typer, 3022-3371 and the per-operation
// rules) at commit be2811d00282320381fc8007d64873745772011d, with the changes of
// docs/design-generation-layer.md §5.2 (two-pass loops, joining try/catch/finally), by way of
// oxpecker/il/analysis.py. See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import static dev.oxpecker.il.Type.ANY;
import static dev.oxpecker.il.Type.ARRAY;
import static dev.oxpecker.il.Type.BIGINT;
import static dev.oxpecker.il.Type.BOOLEAN;
import static dev.oxpecker.il.Type.FLOAT;
import static dev.oxpecker.il.Type.FUNCTION;
import static dev.oxpecker.il.Type.INTEGER;
import static dev.oxpecker.il.Type.NOTHING;
import static dev.oxpecker.il.Type.NULL;
import static dev.oxpecker.il.Type.OBJECT;
import static dev.oxpecker.il.Type.STRING;
import static dev.oxpecker.il.Type.UNDEFINED;

import dev.oxpecker.il.Ops.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FuzzIL's per-operation typing over a stack of branch states, one instruction at a time.
 * {@code code} is the program so far (the builder's live list or the analysis's code); {@link #step}
 * looks back into it. All maps are insertion-ordered, as Python's dicts are.
 */
public final class Typer {
    // ------------------------------------------------------------------------- branch states
    static final class State {
        final Map<Integer, Type> types = new HashMap<>();
        Type returns = NOTHING;
        Type yields = NOTHING;
        boolean hasReturned = false;
        final boolean isSubroutine;

        State() { this(false); }
        State(boolean isSubroutine) { this.isSubroutine = isSubroutine; }
    }

    /**
     * JSTyper's AnalyzerState: a stack of levels; each level holds the sibling branch states of
     * one conditional construct; the newest state of the top level is active.
     */
    static final class Branches {
        final List<List<State>> levels = new ArrayList<>();
        /** One past the highest variable set or aliased. */
        private int size = 0;
        /** Variable -> its current type; null where it has none. */
        private Type[] overall = new Type[16];
        /** For-loop header copies -> the canonical loop variable; the identity elsewhere. */
        private int[] alias = identity(0, new int[16]);

        Branches() {
            List<State> first = new ArrayList<>();
            first.add(new State());
            levels.add(first);
        }

        State active() {
            List<State> top = levels.get(levels.size() - 1);
            return top.get(top.size() - 1);
        }

        State parent() {
            if (levels.size() <= 1)
                return null;
            List<State> l = levels.get(levels.size() - 2);
            return l.get(l.size() - 1);
        }

        private static int[] identity(int from, int[] xs) {
            for (int i = from; i < xs.length; i++) xs[i] = i;
            return xs;
        }

        private void grow(int v) {
            if (v >= overall.length) {
                int old = overall.length;
                int n = Math.max(v + 1, old * 2);
                overall = Arrays.copyOf(overall, n);
                alias = identity(old, Arrays.copyOf(alias, n));
            }
            if (v >= size) size = v + 1;
        }

        int canonical(int v) { return v < alias.length ? alias[v] : v; }

        /** The current type of the (canonical) variable {@code v}, or null. */
        Type get(int v) { return v < overall.length ? overall[v] : null; }

        Type typeOf(int v) {
            Type t = get(canonical(v));
            return t == null ? ANY : t;
        }

        void set(int v, Type t) {
            v = canonical(v);
            grow(v);
            State p = parent();
            if (p != null && !p.types.containsKey(v)) p.types.put(v, overall[v] == null ? NOTHING : overall[v]);
            active().types.put(v, t);
            overall[v] = t;
        }

        void setAlias(int v, int target) {
            grow(v);
            alias[v] = target;
        }

        /** The alias table up to the highest variable seen. */
        int[] alias() { return Arrays.copyOf(alias, size); }

        void startGroup() { levels.add(new ArrayList<>()); }

        /**
         * A new sibling branch: variables the previous sibling changed revert to the parent's
         * types, then {@code entry} (if any) overrides them (JSTyper.swift:3136-3160, plus the entry map).
         */
        void enterBranch(Type[] entry) {
            List<State> level = levels.get(levels.size() - 1);
            if (!level.isEmpty()) {
                State prev = level.get(level.size() - 1);
                State p = parent();
                for (int v : prev.types.keySet()) {
                    if (p != null && p.types.containsKey(v) && !p.types.get(v).isNothing()) {
                        overall[v] = p.types.get(v);
                    } else {
                        overall[v] = null;
                    }
                }
            }
            level.add(new State());
            if (entry != null) for (int v = 0; v < entry.length; v++) if (entry[v] != null) set(v, entry[v]);
        }

        void enterBranch() {
            enterBranch(null);
        }

        /** Variable -> current type, null where there is none. */
        Type[] snapshot() {
            return Arrays.copyOf(overall, size);
        }

        /** Merge the sibling branches into the parent (JSTyper.swift:3251-3345). */
        void endGroup() {
            List<State> siblings = levels.remove(levels.size() - 1);
            State parent = active();
            Map<Integer, List<Type>> changed = new HashMap<>();
            for (State s : siblings)
                for (var e : s.types.entrySet()) changed.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            for (var e : changed.entrySet()) {
                int v = e.getKey();
                List<Type> ts = e.getValue();
                Type nw = NOTHING;
                for (Type t : ts) nw = nw.join(t);
                if (ts.size() != siblings.size()) {
                    Type fallback = parent.types.containsKey(v)
                        ? parent.types.get(v)
                            : (get(v) == null ? NOTHING : get(v));
                    nw = nw.join(fallback);
                }
                if (nw.isNothing()) continue;
                set(v, nw);
            }
            Type rets = NOTHING;
            Type ylds = NOTHING;
            boolean allReturned = !siblings.isEmpty();
            for (State s : siblings) {
                rets = rets.join(s.returns);
                ylds = ylds.join(s.yields);
                allReturned = allReturned && s.hasReturned;
            }
            parent.returns = parent.returns.join(rets);
            parent.yields = parent.yields.join(ylds);
            if (allReturned && !parent.hasReturned) parent.hasReturned = true;
        }

        void startSubroutine() {
            List<State> level = new ArrayList<>();
            level.add(new State());               // the "not called" alternative
            level.add(new State(true));
            levels.add(level);
        }

        Type[] endSubroutine(Type defaultReturn) {
            State body = active();
            Type rets = body.hasReturned ? body.returns : body.returns.join(defaultReturn);
            Type ylds = body.yields;
            body.returns = NOTHING;
            body.hasReturned = false;
            endGroup();
            return new Type[] {rets, ylds};
        }

        void updateReturn(Type t) {
            State s = active();
            if (s.hasReturned) return;
            s.returns = s.returns.join(t);
            s.hasReturned = true;
        }

        void updateYield(Type t) {
            State s = active();
            if (!s.hasReturned) s.yields = s.yields.join(t);
        }
    }

    private record Try(Type[] pre, List<Type[]> exits, boolean hasCatch) {}

    // ------------------------------------------------------------------------- the typer
    final List<Instruction> code;
    final Map<Integer, List<Type>> paramTypes;
    final Branches b = new Branches();
    /** Function variable -> return type (a generator's: its Generator object type); null for other variables. */
    private Type[] returns = new Type[16];
    /** Object literal group ("_Object1", ...) -> its known members. */
    public final Map<String, Map<Key, Type>> literalProps = new HashMap<>();
    private final List<String> literals = new ArrayList<>();
    private final List<int[]> fns = new ArrayList<>();                 // (begin index, function variable or -1)
    private final List<Boolean> switchDefault = new ArrayList<>();
    private final List<Try> tries = new ArrayList<>();
    private int groups = 0;

    public Typer(List<Instruction> code) { this(code, null); }

    public Typer(List<Instruction> code, Map<Integer, List<Type>> paramTypes) {
        this.code = code;
        this.paramTypes = paramTypes == null ? new HashMap<>() : new HashMap<>(paramTypes);
    }

    // -- queries
    public Type typeOf(int v) { return b.typeOf(v); }

    public Type[] snapshot() { return b.snapshot(); }

    /** For-loop header copies -> the canonical loop variable, the identity elsewhere. */
    public int[] alias() { return b.alias(); }

    /** Function variable -> return type, null for other variables. */
    public Type[] returns() { return returns.clone(); }

    /** The group of the innermost open object literal, or null. */
    public String currentLiteralGroup() { return literals.isEmpty() ? null : literals.get(literals.size() - 1); }

    static final List<Class<? extends Operation>> LOOP_ENDS = List.of(EndWhileLoop.class, EndForEachLoop.class,
            EndRepeatLoop.class, EndForLoop.class, EndCodeString.class, EndDoWhileLoop.class);
    static final List<Class<? extends Operation>> SUBROUTINE_ENDS = List.of(EndPlainFunction.class, EndArrowFunction.class,
            EndGeneratorFunction.class, EndObjectLiteralMethod.class, EndObjectLiteralComputedMethod.class,
            EndObjectLiteralGetter.class, EndObjectLiteralComputedGetter.class, EndObjectLiteralSetter.class,
            EndObjectLiteralComputedSetter.class);

    /** The declared parameter types of a subroutine begin (Python's {@code getattr(op, "param_types", ())}). */
    static List<Type> paramTypesOf(Operation op) {
        if (op instanceof BeginPlainFunction o) return o.paramTypes();
        if (op instanceof BeginArrowFunction o) return o.paramTypes();
        if (op instanceof BeginGeneratorFunction o) return o.paramTypes();
        if (op instanceof BeginObjectLiteralMethod o) return o.paramTypes();
        if (op instanceof BeginObjectLiteralComputedMethod o) return o.paramTypes();
        return List.of();
    }

    /** Python's {@code getattr(op, "parameters", None)}. */
    static Parameters parametersOf(Operation op) {
        if (op instanceof BeginPlainFunction o) return o.parameters();
        if (op instanceof BeginArrowFunction o) return o.parameters();
        if (op instanceof BeginGeneratorFunction o) return o.parameters();
        if (op instanceof BeginObjectLiteralMethod o) return o.parameters();
        if (op instanceof BeginObjectLiteralComputedMethod o) return o.parameters();
        return null;
    }

    /** Python's {@code getattr(op, "strict", False)}. */
    static boolean strictOf(Operation op) {
        if (op instanceof BeginPlainFunction o) return o.strict();
        if (op instanceof BeginGeneratorFunction o) return o.strict();
        return false;
    }

    /** One instruction: scope changes, then its own effects, then the scope changes after it. */
    public void step(int i, Instruction instr) {
        Branches b = this.b;
        Operation op = instr.op();
        List<Type> ins = new ArrayList<>();
        for (int v : instr.inputs()) ins.add(b.typeOf(v));

        // scope changes before the instruction's own outputs (JSTyper processScopeChanges)
        if (op instanceof BeginIf) {
            b.startGroup();
            b.enterBranch();
        } else if (op instanceof BeginElse) {
            b.enterBranch();
        } else if (op instanceof EndIf) {
            if (b.levels.get(b.levels.size() - 1).size() == 1) b.enterBranch();
            b.endGroup();
        } else if (op instanceof BeginSwitch) {
            b.set(instr.innerOutput(), Analysis.LABEL);      // before the group opens: the label lives outside the cases
            b.startGroup();
            switchDefault.add(false);
        } else if (op instanceof BeginSwitchCase || op instanceof BeginSwitchDefaultCase) {
            b.enterBranch();
            if (op instanceof BeginSwitchDefaultCase) switchDefault.set(switchDefault.size() - 1, true);
        } else if (op instanceof EndSwitch) {
            if (!switchDefault.remove(switchDefault.size() - 1)) b.enterBranch();
            b.endGroup();
        } else if (op instanceof BeginWhileLoopBody || op instanceof BeginForEachLoop || op instanceof BeginRepeatLoop
                || op instanceof BeginCodeString || op instanceof BeginForLoopAfterthought) {
            b.startGroup();
            b.enterBranch();          // never runs
            b.enterBranch();          // runs
        } else if (op instanceof BeginTry) {
            b.startGroup();
            tries.add(new Try(b.snapshot(), new ArrayList<>(), false));
            b.enterBranch();
        } else if (op instanceof BeginCatch) {
            Try t = tries.get(tries.size() - 1);
            t.exits().add(b.snapshot());
            tries.set(tries.size() - 1, new Try(t.pre(), t.exits(), true));
            b.enterBranch(Analysis.joinStates(t.pre(), t.exits()));
        } else if (op instanceof BeginFinally) {
            Try t = tries.get(tries.size() - 1);
            t.exits().add(b.snapshot());
            if (!t.hasCatch()) b.enterBranch(Analysis.joinStates(t.pre(), t.exits()));
            b.endGroup();
            b.startGroup();            // the finally block runs unconditionally: a single branch
            b.enterBranch();
        } else if (op instanceof EndTryCatchFinally) {
            if (tries.get(tries.size() - 1).hasCatch() || previousBlockIs(code, i, BeginFinally.class)) {
                b.endGroup();
            } else {
                b.enterBranch();
                b.endGroup();
            }
            tries.remove(tries.size() - 1);
        } else if (Ops.isAny(op, Ops.SUBROUTINE_BEGINS)) {
            b.startSubroutine();
        }

        // the instruction's own effects (processTypeChangesAfterScopeChanges)
        if (Ops.isAny(op, Ops.SUBROUTINE_BEGINS)) {
            List<Type> params = new ArrayList<>(paramTypesOf(op));
            if (params.isEmpty()) params = new ArrayList<>(paramTypes.getOrDefault(i, List.of()));
            List<Integer> inner = ints(instr.innerOutputs());
            boolean function = Ops.isAny(op, Ops.FUNCTION_BEGINS);
            if (!function)
                b.set(inner.remove(0), literals.isEmpty() ? OBJECT : Type.object(literals.get(literals.size() - 1)));
            if (op instanceof BeginObjectLiteralSetter || op instanceof BeginObjectLiteralComputedSetter)
                params = new ArrayList<>(List.of(ANY));
            Parameters plist = parametersOf(op);
            if (plist == null) plist = new Parameters(0);
            for (int k = 0; k < inner.size(); k++) {
                Type ty = k < params.size() ? params.get(k) : ANY;
                if (plist.hasRest() && k == plist.count() - 1 && plist.destructuring().isEmpty()) ty = ARRAY;
                b.set(inner.get(k), ty);
            }
            if (function) {
                List<Param> ps = new ArrayList<>();
                for (int k = 0; k < plist.count(); k++) ps.add(new Param(k < params.size() ? params.get(k) : ANY));
                b.set(instr.output(), Type.function(new Signature(ps, ANY)));
                fns.add(new int[] {i, instr.output()});
            } else {
                fns.add(new int[] {i, -1});
            }
        } else if (Ops.isAny(op, SUBROUTINE_ENDS)) {
            int[] fn = fns.remove(fns.size() - 1);
            int beginI = fn[0], fvar = fn[1];
            Type[] ry = b.endSubroutine(UNDEFINED);
            Type rets = ry[0], ylds = ry[1];
            Operation begin = code.get(beginI).op();
            if (fvar != -1) {
                Type ft = b.typeOf(fvar);
                Type result;
                if (begin instanceof BeginGeneratorFunction)
                    result = Analysis.GENERATOR_OBJECT.withElement(!ylds.isNothing() ? ylds : ANY);
                else
                    result = !rets.isNothing() ? rets : UNDEFINED;
                if (ft.is(FUNCTION) && ft.signature() != null)
                    b.set(fvar, ft.withSignature(new Signature(ft.signature().params(), result)));
                if (fvar >= returns.length) returns = Arrays.copyOf(returns, Math.max(fvar + 1, returns.length * 2));
                returns[fvar] = result;
            }
            if (!literals.isEmpty() && (begin instanceof BeginObjectLiteralMethod || begin instanceof BeginObjectLiteralGetter)) {
                Type value = !rets.isNothing() ? rets : UNDEFINED;
                Key key = begin instanceof BeginObjectLiteralMethod m ? m.key() : ((BeginObjectLiteralGetter) begin).key();
                literalProps.get(literals.get(literals.size() - 1)).put(key,
                        begin instanceof BeginObjectLiteralMethod ? Type.function(new Signature(List.of(), value)) : value);
            }
        } else if (op instanceof LoadInteger) {
            b.set(instr.output(), INTEGER);
        } else if (op instanceof LoadBigInt) {
            b.set(instr.output(), BIGINT);
        } else if (op instanceof LoadFloat) {
            b.set(instr.output(), FLOAT);
        } else if (op instanceof LoadString) {
            b.set(instr.output(), STRING);
        } else if (op instanceof LoadBoolean) {
            b.set(instr.output(), BOOLEAN);
        } else if (op instanceof LoadUndefined) {
            b.set(instr.output(), UNDEFINED);
        } else if (op instanceof LoadNull) {
            b.set(instr.output(), NULL);
        } else if (op instanceof LoadThis) {
            b.set(instr.output(), OBJECT);
        } else if (op instanceof LoadArguments) {
            b.set(instr.output(), Analysis.ARGUMENTS);
        } else if (op instanceof LoadNewTarget) {
            b.set(instr.output(), FUNCTION.join(UNDEFINED));
        } else if (op instanceof LoadRegExp) {
            b.set(instr.output(), Analysis.REGEXP);
        } else if (op instanceof LoadBuiltin o) {
            b.set(instr.output(), o.builtin().type());
        } else if (op instanceof CreateNamedVariable || op instanceof Declare) {
            b.set(instr.output(), ins.get(0));
        } else if (op instanceof BeginObjectLiteral) {
            groups++;
            String group = "_Object" + groups;
            literalProps.put(group, new HashMap<>());
            literals.add(group);
        } else if (op instanceof ObjectLiteralAddProperty o) {
            literalProps.get(literals.get(literals.size() - 1)).put(o.key(), ins.get(0));
        } else if (op instanceof ObjectLiteralAddElement o) {
            literalProps.get(literals.get(literals.size() - 1)).put(new Key(Long.toString(o.index()), KeyKind.INDEX), ins.get(0));
        } else if (op instanceof ObjectLiteralSetPrototype) {
            literalProps.get(literals.get(literals.size() - 1)).put(new Key("__proto__", KeyKind.PROTO), ins.get(0));
        } else if (op instanceof EndObjectLiteral) {
            String group = literals.remove(literals.size() - 1);
            b.set(instr.output(), Type.object(group, literalProps.get(group).keySet()));
        } else if (op instanceof CreateArray) {
            Type el = NOTHING;
            for (Type x : ins) el = el.join(x);
            b.set(instr.output(), Type.array(el.isNothing() ? null : el));
        } else if (op instanceof CreateIntArray) {
            b.set(instr.output(), Type.array(INTEGER));
        } else if (op instanceof CreateFloatArray) {
            b.set(instr.output(), Type.array(FLOAT));
        } else if (op instanceof CreateArrayWithSpread o) {
            Type el = NOTHING;
            for (int k = 0; k < Math.min(ins.size(), o.spreads().size()); k++) {
                Type x = ins.get(k);
                el = el.join(o.spreads().get(k) ? (x.element() != null ? x.element() : ANY) : x);
            }
            b.set(instr.output(), Type.array(el.isNothing() || el.equals(ANY) ? null : el));
        } else if (op instanceof CreateTemplateString) {
            b.set(instr.output(), STRING);
        } else if (op instanceof GetProperty o) {
            b.set(instr.output(), Analysis.propertyType(ins.get(0), o.key(), literalProps));
        } else if (op instanceof SetProperty || op instanceof UpdateProperty || op instanceof ConfigureProperty) {
            Key key = op instanceof SetProperty s ? s.key() : op instanceof UpdateProperty u ? u.key() : ((ConfigureProperty) op).key();
            if (ins.get(0).is(OBJECT) || ins.get(0).is(FUNCTION)) b.set(instr.input(0), ins.get(0).withProp(key));
        } else if (op instanceof DeleteProperty o) {
            if (ins.get(0).is(OBJECT)) b.set(instr.input(0), ins.get(0).withoutProp(o.key()));
            b.set(instr.output(), BOOLEAN);
        } else if (op instanceof GetElement || op instanceof GetComputedProperty) {
            b.set(instr.output(), ins.get(0).element() != null ? ins.get(0).element() : ANY);
        } else if (op instanceof DeleteElement || op instanceof DeleteComputedProperty) {
            b.set(instr.output(), BOOLEAN);
        } else if (op instanceof TypeOf) {
            b.set(instr.output(), STRING);
        } else if (op instanceof Ops.Void) {
            b.set(instr.output(), UNDEFINED);
        } else if (op instanceof TestInstanceOf || op instanceof TestIn || op instanceof Compare) {
            b.set(instr.output(), BOOLEAN);
        } else if (op instanceof Return) {
            b.updateReturn(!ins.isEmpty() ? ins.get(0) : UNDEFINED);
        } else if (op instanceof Yield) {
            b.updateYield(!ins.isEmpty() ? ins.get(0) : UNDEFINED);
            b.set(instr.output(), ANY);
        } else if (op instanceof YieldEach) {
            b.updateYield(ins.get(0).element() != null ? ins.get(0).element() : ANY);
        } else if (op instanceof CallFunction || op instanceof CallFunctionWithSpread) {
            b.set(instr.output(), Analysis.calls(ins.get(0)));
        } else if (op instanceof Construct || op instanceof ConstructWithSpread) {
            b.set(instr.output(), Analysis.constructs(ins.get(0)));
        } else if (op instanceof CallMethod o) {
            b.set(instr.output(), o.method().returns());
        } else if (op instanceof CallMethodWithSpread o) {
            b.set(instr.output(), o.method().returns());
        } else if (op instanceof CallComputedMethod || op instanceof CallComputedMethodWithSpread || op instanceof CallSuperMethod
                || op instanceof GetSuperProperty || op instanceof GetComputedSuperProperty) {
            b.set(instr.output(), ANY);
        } else if (op instanceof UnaryOperation o) {
            Type r = Analysis.unaryResult(o.op(), ins.get(0));
            if (o.op().reassignsInput()) b.set(instr.input(0), r);
            b.set(instr.output(), r);
        } else if (op instanceof BinaryOperation o) {
            b.set(instr.output(), Analysis.binaryResult(o.op(), ins.get(0), ins.get(1)));
        } else if (op instanceof TernaryOperation) {
            b.set(instr.output(), ins.get(1).join(ins.get(2)));
        } else if (op instanceof Reassign) {
            b.set(instr.input(0), ins.get(1));
        } else if (op instanceof Update o) {
            b.set(instr.input(0), Analysis.binaryResult(o.op(), ins.get(0), ins.get(1)));
        } else if (op instanceof Destruct o) {
            int[] outs = instr.outputs();
            List<Type> tys = Analysis.destructTypes(o.pattern(), ins.get(0), literalProps);
            for (int k = 0; k < Math.min(outs.length, tys.size()); k++) b.set(outs[k], tys.get(k));
        } else if (op instanceof DestructAndReassign o) {
            int nb = Patterns.numBindings(o.pattern());
            int[] inputs = instr.inputs();
            List<Type> tys = Analysis.destructTypes(o.pattern(), ins.get(0), literalProps);
            for (int k = 0; k < Math.min(nb, tys.size()); k++) b.set(inputs[1 + k], tys.get(k));
        } else if (op instanceof BeginForLoopCondition) {
            int[] inner = instr.innerOutputs();
            for (int k = 0; k < Math.min(inner.length, ins.size()); k++) {
                int v = inner[k];
                Type ty = ins.get(k);
                // on a second pass the loop variable also carries what the afterthought and body made of it
                Type seen = b.get(b.canonical(v));
                b.set(v, seen != null ? ty.join(seen) : ty);
            }
        } else if (op instanceof BeginForLoopAfterthought || op instanceof BeginForLoopBody) {
            int[] condVars = code.get(headerCondition(code, i)).innerOutputs();
            List<Integer> inner = ints(instr.innerOutputs());
            if (op instanceof BeginForLoopBody) b.set(inner.remove(inner.size() - 1), Analysis.LABEL);
            for (int k = 0; k < Math.min(inner.size(), condVars.length); k++) b.setAlias(inner.get(k), condVars[k]);
        } else if (op instanceof BeginForEachLoop o) {
            List<Integer> inner = ints(instr.innerOutputs());
            b.set(inner.remove(inner.size() - 1), Analysis.LABEL);
            Type el = ins.get(0).element() != null ? ins.get(0).element() : ANY;
            if (o.pattern() == null) {
                b.set(inner.get(0), o.kind() == ForEachKind.IN ? STRING : el);
            } else {
                List<Type> tys = Analysis.destructTypes(o.pattern(), el, literalProps);
                for (int k = 0; k < Math.min(inner.size(), tys.size()); k++) b.set(inner.get(k), tys.get(k));
            }
        } else if (op instanceof BeginRepeatLoop o) {
            int[] inner = instr.innerOutputs();
            b.set(inner[inner.length - 1], Analysis.LABEL);
            if (o.exposesCounter()) b.set(inner[0], INTEGER);
        } else if (op instanceof BeginIf || op instanceof BeginElse || op instanceof BeginBlockStatement
                || op instanceof BeginWhileLoopBody || op instanceof BeginDoWhileLoopBody) {
            for (int v : instr.innerOutputs()) b.set(v, Analysis.LABEL);
        } else if (op instanceof BeginCatch) {
            b.set(instr.innerOutput(), ANY);
        } else if (op instanceof BeginCodeString) {
            b.set(instr.output(), STRING);
        }

        // scope changes after
        if (Ops.isAny(op, LOOP_ENDS) && !(op instanceof EndDoWhileLoop)) b.endGroup();
    }

    static List<Integer> ints(int[] xs) {
        List<Integer> out = new ArrayList<>(xs.length);
        for (int x : xs) out.add(x);
        return out;
    }

    static boolean previousBlockIs(List<Instruction> code, int end, Class<? extends Operation> cls) {
        int depth = 0;
        for (int j = end - 1; j >= 0; j--) {
            Operation op = code.get(j).op();
            if (op.isBlockEnd() && !op.isBlockStart()) {
                depth++;
            } else if (op.isBlockStart()) {
                if (depth == 0) return cls.isInstance(op);
                if (!op.isBlockEnd()) depth--;
            }
        }
        return false;
    }

    static int headerCondition(List<Instruction> code, int i) {
        int depth = 0;
        for (int j = i - 1; j >= 0; j--) {
            Operation op = code.get(j).op();
            if (op.isBlockEnd() && !op.isBlockStart()) {
                depth++;
            } else if (op.isBlockStart() && !op.isBlockEnd()) {
                if (depth == 0) throw new InvalidProgram("for_header", "for-loop part without a header", i);
                depth--;
            } else if (op.isBlockStart() && op.isBlockEnd() && depth == 0 && op instanceof BeginForLoopCondition) {
                return j;
            }
        }
        throw new InvalidProgram("for_header", "for-loop part without a condition block", i);
    }
}
