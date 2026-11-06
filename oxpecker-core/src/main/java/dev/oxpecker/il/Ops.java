// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/FuzzIL/JsOperations.swift, Operation.swift and Context.swift at commit
// be2811d00282320381fc8007d64873745772011d, restricted to what Rhino supports as decided in
// docs/design-generation-layer.md §3.3. See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
// GENERATED from the table in the port's genops.py; edit the records here directly from now on.
package dev.oxpecker.il;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operations: every construct a generator can build is one of the records below. Each record's
 * components are the operation's parameters; {@link Spec} describes inputs, outputs, inner
 * outputs and context (Operation.swift:19-98). Operations whose counts depend on their
 * parameters override {@code inputs()}, {@code outputs()}, {@code innerOutputs()} or {@code opened()}.
 */
public final class Ops {
    private Ops() {}

    /** The static description of an operation class. */
    public record Spec(int inputs, int outputs, int innerOutputs, int firstVariadic, int attrs, int required, int opened) {}

    /** Base of every operation. */
    public sealed interface Operation permits LoadInteger, LoadBigInt, LoadFloat, LoadString, LoadBoolean, LoadUndefined, LoadNull, LoadThis, LoadArguments, LoadNewTarget, LoadRegExp, LoadBuiltin, CreateNamedVariable, Declare, BeginObjectLiteral, ObjectLiteralAddProperty, ObjectLiteralAddElement, ObjectLiteralAddComputedProperty, ObjectLiteralCopyProperties, ObjectLiteralSetPrototype, BeginObjectLiteralMethod, EndObjectLiteralMethod, BeginObjectLiteralComputedMethod, EndObjectLiteralComputedMethod, BeginObjectLiteralGetter, EndObjectLiteralGetter, BeginObjectLiteralComputedGetter, EndObjectLiteralComputedGetter, BeginObjectLiteralSetter, EndObjectLiteralSetter, BeginObjectLiteralComputedSetter, EndObjectLiteralComputedSetter, EndObjectLiteral, CreateArray, CreateIntArray, CreateFloatArray, CreateArrayWithSpread, CreateTemplateString, GetProperty, SetProperty, UpdateProperty, DeleteProperty, ConfigureProperty, GetElement, SetElement, UpdateElement, DeleteElement, ConfigureElement, GetComputedProperty, SetComputedProperty, UpdateComputedProperty, DeleteComputedProperty, ConfigureComputedProperty, TypeOf, Void, TestInstanceOf, TestIn, BeginPlainFunction, EndPlainFunction, BeginArrowFunction, EndArrowFunction, BeginGeneratorFunction, EndGeneratorFunction, Directive, Return, Yield, YieldEach, CallFunction, CallFunctionWithSpread, Construct, ConstructWithSpread, CallMethod, CallMethodWithSpread, CallComputedMethod, CallComputedMethodWithSpread, UnaryOperation, BinaryOperation, TernaryOperation, Reassign, Update, Compare, CallSuperMethod, GetSuperProperty, SetSuperProperty, SetComputedSuperProperty, GetComputedSuperProperty, UpdateSuperProperty, BeginIf, BeginElse, EndIf, BeginWhileLoopHeader, BeginWhileLoopBody, EndWhileLoop, BeginDoWhileLoopBody, BeginDoWhileLoopHeader, EndDoWhileLoop, BeginForLoopInitializer, BeginForLoopCondition, BeginForLoopAfterthought, BeginForLoopBody, EndForLoop, BeginForEachLoop, EndForEachLoop, BeginRepeatLoop, EndRepeatLoop, LoopBreak, LoopContinue, BeginTry, BeginCatch, BeginFinally, EndTryCatchFinally, ThrowException, BeginBlockStatement, EndBlockStatement, BlockBreak, BeginSwitch, BeginSwitchCase, BeginSwitchDefaultCase, EndSwitchCase, EndSwitch, SwitchBreak, BeginWith, EndWith, BeginCodeString, EndCodeString, Destruct, DestructAndReassign, Probe {
        Spec spec();

        default int inputs() { return spec().inputs(); }
        default int outputs() { return spec().outputs(); }
        default int innerOutputs() { return spec().innerOutputs(); }
        default int opened() { return spec().opened(); }
        default int required() { return spec().required(); }
        /** -1 when the operation is not variadic. */
        default int firstVariadic() { return spec().firstVariadic(); }
        default int attrs() { return spec().attrs(); }

        default String name() { return getClass().getSimpleName(); }
        default boolean isBlockStart() { return (attrs() & Attr.BLOCK_START) != 0; }
        default boolean isBlockEnd() { return (attrs() & Attr.BLOCK_END) != 0; }
        default boolean isBlock() { return (attrs() & (Attr.BLOCK_START | Attr.BLOCK_END)) != 0; }
        default boolean isBlockGroupStart() { return isBlockStart() && !isBlockEnd(); }
        default boolean isBlockGroupEnd() { return isBlockEnd() && !isBlockStart(); }
        default boolean isJump() { return (attrs() & Attr.JUMP) != 0; }
        default boolean isVariadic() { return (attrs() & Attr.VARIADIC) != 0; }
        default boolean isCall() { return (attrs() & Attr.CALL) != 0; }
        default boolean isMutable() { return (attrs() & Attr.MUTABLE) != 0; }
        default boolean isSingular() { return (attrs() & Attr.SINGULAR) != 0; }
        default boolean propagatesContext() { return (attrs() & Attr.PROPAGATES_CONTEXT) != 0; }
        default boolean resumesContext() { return (attrs() & Attr.RESUMES_CONTEXT) != 0; }
        default String repr() { return Repr.record((Record) this); }
    }

    public record LoadInteger(long value, Radix radix) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public LoadInteger {
            if (value < 0) throw new IllegalArgumentException("JavaScript has no negative literals: use UnaryOperation(MINUS, ...)");
            if (value > (1L << 53)) throw new IllegalArgumentException("integer literal beyond 2**53; use LoadBigInt or LoadFloat");
        }
        public LoadInteger(long value) { this(value, Radix.DEC); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadBigInt(java.math.BigInteger value) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public LoadBigInt {
            if (value.signum() < 0) throw new IllegalArgumentException("no negative literals");
        }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadFloat(double value, FloatStyle style) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public LoadFloat {
            if (!Double.isFinite(value) || Math.copySign(1.0, value) < 0) throw new IllegalArgumentException("float literals are finite and non-negative; NaN/Infinity are builtins, -x is a unary minus");
        }
        public LoadFloat(double value) { this(value, FloatStyle.PLAIN); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadString(String value) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadBoolean(boolean value) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadUndefined() implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadNull() implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadThis() implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadArguments() implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT | Context.SUBROUTINE, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadNewTarget() implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT | Context.SUBROUTINE, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoadRegExp(String pattern, Flags flags) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public LoadRegExp {
            if (pattern.isEmpty()) throw new IllegalArgumentException("an empty regexp pattern would lift as a comment");
        }
        public LoadRegExp(String pattern) { this(pattern, RegExpFlag.NONE); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** A global from the registry (replaces FuzzIL's CreateNamedVariable with mode `none`). */
    public record LoadBuiltin(Builtin builtin) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record CreateNamedVariable(Key identifier, NamedVariableMode mode) implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CreateNamedVariable {
            if (!identifier.isIdentifier()) throw new IllegalArgumentException("a named variable needs an identifier name");
        }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** `var|let|const v = <input>`: the declared, assignable class of variable (design §3.2). */
    public record Declare(DeclarationKind kind) implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public Declare() { this(DeclarationKind.VAR); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginObjectLiteral() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START, Context.JAVASCRIPT, Context.OBJECT_LITERAL);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ObjectLiteralAddProperty(Key key) implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.MUTABLE, Context.OBJECT_LITERAL, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ObjectLiteralAddElement(long index) implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.MUTABLE, Context.OBJECT_LITERAL, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ObjectLiteralAddComputedProperty() implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.NONE, Context.OBJECT_LITERAL, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ObjectLiteralCopyProperties() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.NONE, Context.OBJECT_LITERAL, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ObjectLiteralSetPrototype() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.SINGULAR, Context.OBJECT_LITERAL, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** Inner outputs: this, then parameters. */
    public record BeginObjectLiteralMethod(Key key, Parameters parameters, List<Type> paramTypes, boolean generator) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.MUTABLE, Context.OBJECT_LITERAL, Context.EMPTY);
        public BeginObjectLiteralMethod {
            paramTypes = List.copyOf(paramTypes);
        }
        public BeginObjectLiteralMethod(Key key, Parameters parameters, List<Type> paramTypes) { this(key, parameters, paramTypes, false); }
        public BeginObjectLiteralMethod(Key key, Parameters parameters) { this(key, parameters, List.of(), false); }
        public BeginObjectLiteralMethod(Key key) { this(key, Parameters.NONE, List.of(), false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return parameters.numDefaults(); }
        @Override public int innerOutputs() { return 1 + parameters.numBindings(); }
        @Override public int opened() { return Context.JAVASCRIPT | Context.SUBROUTINE | Context.METHOD | (generator ? Context.GENERATOR_FUNCTION : Context.EMPTY); }
        @Override public String toString() { return repr(); }
    }

    public record EndObjectLiteralMethod() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** Input 0 = key, then defaults. */
    public record BeginObjectLiteralComputedMethod(Parameters parameters, List<Type> paramTypes, boolean generator) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START, Context.OBJECT_LITERAL, Context.EMPTY);
        public BeginObjectLiteralComputedMethod {
            paramTypes = List.copyOf(paramTypes);
        }
        public BeginObjectLiteralComputedMethod(Parameters parameters, List<Type> paramTypes) { this(parameters, paramTypes, false); }
        public BeginObjectLiteralComputedMethod(Parameters parameters) { this(parameters, List.of(), false); }
        public BeginObjectLiteralComputedMethod() { this(Parameters.NONE, List.of(), false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + parameters.numDefaults(); }
        @Override public int innerOutputs() { return 1 + parameters.numBindings(); }
        @Override public int opened() { return Context.JAVASCRIPT | Context.SUBROUTINE | Context.METHOD | (generator ? Context.GENERATOR_FUNCTION : Context.EMPTY); }
        @Override public String toString() { return repr(); }
    }

    public record EndObjectLiteralComputedMethod() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginObjectLiteralGetter(Key key) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 1, -1, Attr.BLOCK_START | Attr.MUTABLE, Context.OBJECT_LITERAL, Context.JAVASCRIPT | Context.SUBROUTINE | Context.METHOD);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndObjectLiteralGetter() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginObjectLiteralComputedGetter() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 1, -1, Attr.BLOCK_START, Context.OBJECT_LITERAL, Context.JAVASCRIPT | Context.SUBROUTINE | Context.METHOD);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndObjectLiteralComputedGetter() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginObjectLiteralSetter(Key key) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 2, -1, Attr.BLOCK_START | Attr.MUTABLE, Context.OBJECT_LITERAL, Context.JAVASCRIPT | Context.SUBROUTINE | Context.METHOD);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndObjectLiteralSetter() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginObjectLiteralComputedSetter() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 2, -1, Attr.BLOCK_START, Context.OBJECT_LITERAL, Context.JAVASCRIPT | Context.SUBROUTINE | Context.METHOD);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndObjectLiteralComputedSetter() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndObjectLiteral() implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.BLOCK_END, Context.OBJECT_LITERAL, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record CreateArray(int numElements) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 0, Attr.VARIADIC, Context.JAVASCRIPT, Context.EMPTY);
        public CreateArray() { this(0); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return numElements; }
        @Override public String toString() { return repr(); }
    }

    public record CreateIntArray(List<Long> values) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CreateIntArray {
            values = List.copyOf(values);
        }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record CreateFloatArray(List<Double> values) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CreateFloatArray {
            values = List.copyOf(values);
        }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record CreateArrayWithSpread(List<Boolean> spreads) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 0, Attr.VARIADIC | Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CreateArrayWithSpread {
            spreads = List.copyOf(spreads);
        }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return spreads.size(); }
        @Override public String toString() { return repr(); }
    }

    public record CreateTemplateString(List<String> parts) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 0, Attr.VARIADIC | Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CreateTemplateString {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) throw new IllegalArgumentException("a template has at least one part");
        }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return parts.size() - 1; }
        @Override public String toString() { return repr(); }
    }

    public record GetProperty(Key key, boolean receiverOptional) implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public GetProperty(Key key) { this(key, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record SetProperty(Key key) implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record UpdateProperty(Key key, BinaryOperator op) implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record DeleteProperty(Key key, boolean receiverOptional) implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public DeleteProperty(Key key) { this(key, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ConfigureProperty(Key key, Flags flags, PropertyType type) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return type == PropertyType.GETTER_SETTER ? 3 : 2; }
        @Override public String toString() { return repr(); }
    }

    public record GetElement(long index, boolean receiverOptional) implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public GetElement(long index) { this(index, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record SetElement(long index) implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record UpdateElement(long index, BinaryOperator op) implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record DeleteElement(long index, boolean receiverOptional) implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public DeleteElement(long index) { this(index, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ConfigureElement(long index, Flags flags, PropertyType type) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return type == PropertyType.GETTER_SETTER ? 3 : 2; }
        @Override public String toString() { return repr(); }
    }

    public record GetComputedProperty(boolean receiverOptional) implements Operation {
        static final Spec SPEC = new Spec(2, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        public GetComputedProperty() { this(false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record SetComputedProperty() implements Operation {
        static final Spec SPEC = new Spec(3, 0, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record UpdateComputedProperty(BinaryOperator op) implements Operation {
        static final Spec SPEC = new Spec(3, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record DeleteComputedProperty(boolean receiverOptional) implements Operation {
        static final Spec SPEC = new Spec(2, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        public DeleteComputedProperty() { this(false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ConfigureComputedProperty(Flags flags, PropertyType type) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return type == PropertyType.GETTER_SETTER ? 4 : 3; }
        @Override public String toString() { return repr(); }
    }

    public record TypeOf() implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record Void() implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record TestInstanceOf() implements Operation {
        static final Spec SPEC = new Spec(2, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record TestIn() implements Operation {
        static final Spec SPEC = new Spec(2, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** `named`: a function statement with a name (direct-call target) where legal. */
    public record BeginPlainFunction(Parameters parameters, List<Type> paramTypes, boolean named, boolean strict) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.BLOCK_START, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.SUBROUTINE);
        public BeginPlainFunction {
            paramTypes = List.copyOf(paramTypes);
        }
        public BeginPlainFunction(Parameters parameters, List<Type> paramTypes, boolean named) { this(parameters, paramTypes, named, false); }
        public BeginPlainFunction(Parameters parameters, List<Type> paramTypes) { this(parameters, paramTypes, false, false); }
        public BeginPlainFunction(Parameters parameters) { this(parameters, List.of(), false, false); }
        public BeginPlainFunction() { this(Parameters.NONE, List.of(), false, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return parameters.numDefaults(); }
        @Override public int innerOutputs() { return parameters.numBindings(); }
        @Override public String toString() { return repr(); }
    }

    public record EndPlainFunction() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginArrowFunction(Parameters parameters, List<Type> paramTypes) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.BLOCK_START, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.SUBROUTINE);
        public BeginArrowFunction {
            paramTypes = List.copyOf(paramTypes);
        }
        public BeginArrowFunction(Parameters parameters) { this(parameters, List.of()); }
        public BeginArrowFunction() { this(Parameters.NONE, List.of()); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return parameters.numDefaults(); }
        @Override public int innerOutputs() { return parameters.numBindings(); }
        @Override public String toString() { return repr(); }
    }

    public record EndArrowFunction() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginGeneratorFunction(Parameters parameters, List<Type> paramTypes, boolean named, boolean strict) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.BLOCK_START, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.SUBROUTINE | Context.GENERATOR_FUNCTION);
        public BeginGeneratorFunction {
            paramTypes = List.copyOf(paramTypes);
        }
        public BeginGeneratorFunction(Parameters parameters, List<Type> paramTypes, boolean named) { this(parameters, paramTypes, named, false); }
        public BeginGeneratorFunction(Parameters parameters, List<Type> paramTypes) { this(parameters, paramTypes, false, false); }
        public BeginGeneratorFunction(Parameters parameters) { this(parameters, List.of(), false, false); }
        public BeginGeneratorFunction() { this(Parameters.NONE, List.of(), false, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return parameters.numDefaults(); }
        @Override public int innerOutputs() { return parameters.numBindings(); }
        @Override public String toString() { return repr(); }
    }

    public record EndGeneratorFunction() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record Directive(DirectiveKind kind) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        public Directive() { this(DirectiveKind.USE_STRICT); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record Return(boolean hasValue) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.JUMP, Context.JAVASCRIPT | Context.SUBROUTINE, Context.EMPTY);
        public Return() { this(true); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return hasValue ? 1 : 0; }
        @Override public String toString() { return repr(); }
    }

    /** Output = the value sent in. */
    public record Yield(boolean hasValue) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT | Context.GENERATOR_FUNCTION, Context.EMPTY);
        public Yield() { this(true); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return hasValue ? 1 : 0; }
        @Override public String toString() { return repr(); }
    }

    public record YieldEach() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.NONE, Context.JAVASCRIPT | Context.GENERATOR_FUNCTION, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record CallFunction(int numArguments, boolean callOptional) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 1, Attr.VARIADIC | Attr.CALL, Context.JAVASCRIPT, Context.EMPTY);
        public CallFunction(int numArguments) { this(numArguments, false); }
        public CallFunction() { this(0, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + numArguments; }
        @Override public String toString() { return repr(); }
    }

    public record CallFunctionWithSpread(List<Boolean> spreads, boolean callOptional) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 1, Attr.VARIADIC | Attr.CALL | Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CallFunctionWithSpread {
            spreads = List.copyOf(spreads);
            if (!spreads.contains(true)) throw new IllegalArgumentException("use CallFunction when nothing is spread");
        }
        public CallFunctionWithSpread(List<Boolean> spreads) { this(spreads, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + spreads.size(); }
        @Override public String toString() { return repr(); }
    }

    public record Construct(int numArguments) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 1, Attr.VARIADIC | Attr.CALL, Context.JAVASCRIPT, Context.EMPTY);
        public Construct() { this(0); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + numArguments; }
        @Override public String toString() { return repr(); }
    }

    public record ConstructWithSpread(List<Boolean> spreads) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 1, Attr.VARIADIC | Attr.CALL | Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public ConstructWithSpread {
            spreads = List.copyOf(spreads);
            if (!spreads.contains(true)) throw new IllegalArgumentException("use Construct when nothing is spread");
        }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + spreads.size(); }
        @Override public String toString() { return repr(); }
    }

    public record CallMethod(Method method, int numArguments, boolean receiverOptional, boolean callOptional) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 1, Attr.VARIADIC | Attr.CALL | Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CallMethod(Method method, int numArguments, boolean receiverOptional) { this(method, numArguments, receiverOptional, false); }
        public CallMethod(Method method, int numArguments) { this(method, numArguments, false, false); }
        public CallMethod(Method method) { this(method, 0, false, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + numArguments; }
        @Override public String toString() { return repr(); }
    }

    public record CallMethodWithSpread(Method method, List<Boolean> spreads, boolean receiverOptional, boolean callOptional) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 1, Attr.VARIADIC | Attr.CALL | Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CallMethodWithSpread {
            spreads = List.copyOf(spreads);
        }
        public CallMethodWithSpread(Method method, List<Boolean> spreads, boolean receiverOptional) { this(method, spreads, receiverOptional, false); }
        public CallMethodWithSpread(Method method, List<Boolean> spreads) { this(method, spreads, false, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + spreads.size(); }
        @Override public String toString() { return repr(); }
    }

    public record CallComputedMethod(int numArguments, boolean receiverOptional, boolean callOptional) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 2, Attr.VARIADIC | Attr.CALL, Context.JAVASCRIPT, Context.EMPTY);
        public CallComputedMethod(int numArguments, boolean receiverOptional) { this(numArguments, receiverOptional, false); }
        public CallComputedMethod(int numArguments) { this(numArguments, false, false); }
        public CallComputedMethod() { this(0, false, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 2 + numArguments; }
        @Override public String toString() { return repr(); }
    }

    public record CallComputedMethodWithSpread(List<Boolean> spreads, boolean receiverOptional, boolean callOptional) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 2, Attr.VARIADIC | Attr.CALL | Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public CallComputedMethodWithSpread {
            spreads = List.copyOf(spreads);
        }
        public CallComputedMethodWithSpread(List<Boolean> spreads, boolean receiverOptional) { this(spreads, receiverOptional, false); }
        public CallComputedMethodWithSpread(List<Boolean> spreads) { this(spreads, false, false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 2 + spreads.size(); }
        @Override public String toString() { return repr(); }
    }

    public record UnaryOperation(UnaryOperator op) implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BinaryOperation(BinaryOperator op) implements Operation {
        static final Spec SPEC = new Spec(2, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record TernaryOperation() implements Operation {
        static final Spec SPEC = new Spec(3, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record Reassign() implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record Update(BinaryOperator op) implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record Compare(CompareOperator op) implements Operation {
        static final Spec SPEC = new Spec(2, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record CallSuperMethod(Key key, int numArguments) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, 0, Attr.VARIADIC | Attr.CALL | Attr.MUTABLE, Context.JAVASCRIPT | Context.METHOD, Context.EMPTY);
        public CallSuperMethod(Key key) { this(key, 0); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return numArguments; }
        @Override public String toString() { return repr(); }
    }

    public record GetSuperProperty(Key key) implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT | Context.METHOD, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record SetSuperProperty(Key key) implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT | Context.METHOD, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record SetComputedSuperProperty() implements Operation {
        static final Spec SPEC = new Spec(2, 0, 0, -1, Attr.NONE, Context.JAVASCRIPT | Context.METHOD, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record GetComputedSuperProperty() implements Operation {
        static final Spec SPEC = new Spec(1, 1, 0, -1, Attr.NONE, Context.JAVASCRIPT | Context.METHOD, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record UpdateSuperProperty(Key key, BinaryOperator op) implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT | Context.METHOD, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginIf(boolean inverted) implements Operation {
        static final Spec SPEC = new Spec(1, 0, 1, -1, Attr.BLOCK_START | Attr.MUTABLE | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        public BeginIf() { this(false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginElse() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 1, -1, Attr.BLOCK_END | Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndIf() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginWhileLoopHeader() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginWhileLoopBody() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 1, -1, Attr.BLOCK_START | Attr.BLOCK_END | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.LOOP);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndWhileLoop() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginDoWhileLoopBody() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 1, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.LOOP);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginDoWhileLoopHeader() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.BLOCK_END | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndDoWhileLoop() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginForLoopInitializer() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginForLoopCondition(int numLoopVariables, DeclarationKind declaration) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.BLOCK_END | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        public BeginForLoopCondition(int numLoopVariables) { this(numLoopVariables, DeclarationKind.LET); }
        public BeginForLoopCondition() { this(1, DeclarationKind.LET); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return numLoopVariables; }
        @Override public int innerOutputs() { return numLoopVariables; }
        @Override public String toString() { return repr(); }
    }

    public record BeginForLoopAfterthought(int numLoopVariables) implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.BLOCK_START | Attr.BLOCK_END | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        public BeginForLoopAfterthought() { this(1); }
        @Override public Spec spec() { return SPEC; }
        @Override public int innerOutputs() { return numLoopVariables; }
        @Override public String toString() { return repr(); }
    }

    public record BeginForLoopBody(int numLoopVariables) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.BLOCK_END | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.LOOP);
        public BeginForLoopBody() { this(1); }
        @Override public Spec spec() { return SPEC; }
        @Override public int innerOutputs() { return numLoopVariables + 1; }
        @Override public String toString() { return repr(); }
    }

    public record EndForLoop() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** FuzzIL's `ForLoop`: `for (x of|in <input>)`, with an optional destructuring header. Inner outputs: the bound variables, then the loop label. */
    public record BeginForEachLoop(ForEachKind kind, Patterns.Pattern pattern, DeclarationKind declaration) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.LOOP);
        public BeginForEachLoop {
            if (kind == ForEachKind.IN && pattern != null) throw new IllegalArgumentException("for-in has no destructuring header");
        }
        public BeginForEachLoop(ForEachKind kind, Patterns.Pattern pattern) { this(kind, pattern, DeclarationKind.VAR); }
        public BeginForEachLoop(ForEachKind kind) { this(kind, null, DeclarationKind.VAR); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + (pattern != null ? Patterns.numExtraInputs(pattern) : 0); }
        @Override public int innerOutputs() { return (pattern != null ? Patterns.numBindings(pattern) : 1) + 1; }
        @Override public String toString() { return repr(); }
    }

    public record EndForEachLoop() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** `for (let i = 0; i < n; i++)`; inner: [counter,] label. */
    public record BeginRepeatLoop(long iterations, boolean exposesCounter, DeclarationKind declaration) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.LOOP);
        public BeginRepeatLoop {
            if (iterations < 0) throw new IllegalArgumentException("iterations");
        }
        public BeginRepeatLoop(long iterations, boolean exposesCounter) { this(iterations, exposesCounter, DeclarationKind.LET); }
        public BeginRepeatLoop(long iterations) { this(iterations, true, DeclarationKind.LET); }
        @Override public Spec spec() { return SPEC; }
        @Override public int innerOutputs() { return exposesCounter ? 2 : 1; }
        @Override public String toString() { return repr(); }
    }

    public record EndRepeatLoop() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record LoopBreak(boolean hasLabel) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.JUMP, Context.JAVASCRIPT | Context.LOOP, Context.EMPTY);
        public LoopBreak() { this(false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return hasLabel ? 1 : 0; }
        @Override public String toString() { return repr(); }
    }

    public record LoopContinue(boolean hasLabel) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.JUMP, Context.JAVASCRIPT | Context.LOOP, Context.EMPTY);
        public LoopContinue() { this(false); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return hasLabel ? 1 : 0; }
        @Override public String toString() { return repr(); }
    }

    public record BeginTry() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginCatch() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 1, -1, Attr.BLOCK_START | Attr.BLOCK_END | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginFinally() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.BLOCK_END | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndTryCatchFinally() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record ThrowException() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.JUMP, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginBlockStatement() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 1, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndBlockStatement() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BlockBreak() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.JUMP, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginSwitch() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 1, -1, Attr.BLOCK_START, Context.JAVASCRIPT, Context.SWITCH_BLOCK);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginSwitchCase() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.BLOCK_START | Attr.RESUMES_CONTEXT, Context.SWITCH_BLOCK, Context.SWITCH_CASE | Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginSwitchDefaultCase() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_START | Attr.RESUMES_CONTEXT | Attr.SINGULAR, Context.SWITCH_BLOCK, Context.SWITCH_CASE | Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndSwitchCase(boolean fallsThrough) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        public EndSwitchCase() { this(false); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndSwitch() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.SWITCH_BLOCK, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record SwitchBreak() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.JUMP, Context.JAVASCRIPT | Context.SWITCH_CASE, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record BeginWith() implements Operation {
        static final Spec SPEC = new Spec(1, 0, 0, -1, Attr.BLOCK_START | Attr.PROPAGATES_CONTEXT, Context.JAVASCRIPT, Context.JAVASCRIPT | Context.WITH);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndWith() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** A nested program whose source is the output string. */
    public record BeginCodeString() implements Operation {
        static final Spec SPEC = new Spec(0, 1, 0, -1, Attr.BLOCK_START, Context.JAVASCRIPT, Context.JAVASCRIPT);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    public record EndCodeString() implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.BLOCK_END, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** Declares the pattern's bindings. */
    public record Destruct(Patterns.Pattern pattern, DeclarationKind kind) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        public Destruct(Patterns.Pattern pattern) { this(pattern, DeclarationKind.VAR); }
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + Patterns.numExtraInputs(pattern); }
        @Override public int outputs() { return Patterns.numBindings(pattern); }
        @Override public String toString() { return repr(); }
    }

    /** Assigns into existing variables / members. */
    public record DestructAndReassign(Patterns.Pattern pattern) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.MUTABLE, Context.JAVASCRIPT, Context.EMPTY);
        @Override public Spec spec() { return SPEC; }
        @Override public int inputs() { return 1 + Patterns.numBindings(pattern) + Patterns.numExtraInputs(pattern); }
        @Override public String toString() { return repr(); }
    }

    /** `__probe(label, <visible declared variables>)`; no inputs, so no mutation breaks it. */
    public record Probe(ProbeLabel label, int number) implements Operation {
        static final Spec SPEC = new Spec(0, 0, 0, -1, Attr.NONE, Context.JAVASCRIPT, Context.EMPTY);
        public Probe(ProbeLabel label) { this(label, 0); }
        public Probe() { this(ProbeLabel.STEP, 0); }
        @Override public Spec spec() { return SPEC; }
        @Override public String toString() { return repr(); }
    }

    /** Semantics.swift:62-: which end operations may close a begin operation. */
    public static final Map<Class<? extends Operation>, Set<Class<? extends Operation>>> END_OF = Map.ofEntries(
            Map.entry(BeginObjectLiteral.class, Set.of(EndObjectLiteral.class)),
            Map.entry(BeginObjectLiteralMethod.class, Set.of(EndObjectLiteralMethod.class)),
            Map.entry(BeginObjectLiteralComputedMethod.class, Set.of(EndObjectLiteralComputedMethod.class)),
            Map.entry(BeginObjectLiteralGetter.class, Set.of(EndObjectLiteralGetter.class)),
            Map.entry(BeginObjectLiteralComputedGetter.class, Set.of(EndObjectLiteralComputedGetter.class)),
            Map.entry(BeginObjectLiteralSetter.class, Set.of(EndObjectLiteralSetter.class)),
            Map.entry(BeginObjectLiteralComputedSetter.class, Set.of(EndObjectLiteralComputedSetter.class)),
            Map.entry(BeginPlainFunction.class, Set.of(EndPlainFunction.class)),
            Map.entry(BeginArrowFunction.class, Set.of(EndArrowFunction.class)),
            Map.entry(BeginGeneratorFunction.class, Set.of(EndGeneratorFunction.class)),
            Map.entry(BeginIf.class, Set.of(BeginElse.class, EndIf.class)),
            Map.entry(BeginElse.class, Set.of(EndIf.class)),
            Map.entry(BeginWhileLoopHeader.class, Set.of(BeginWhileLoopBody.class)),
            Map.entry(BeginWhileLoopBody.class, Set.of(EndWhileLoop.class)),
            Map.entry(BeginDoWhileLoopBody.class, Set.of(BeginDoWhileLoopHeader.class)),
            Map.entry(BeginDoWhileLoopHeader.class, Set.of(EndDoWhileLoop.class)),
            Map.entry(BeginForLoopInitializer.class, Set.of(BeginForLoopCondition.class)),
            Map.entry(BeginForLoopCondition.class, Set.of(BeginForLoopAfterthought.class)),
            Map.entry(BeginForLoopAfterthought.class, Set.of(BeginForLoopBody.class)),
            Map.entry(BeginForLoopBody.class, Set.of(EndForLoop.class)),
            Map.entry(BeginForEachLoop.class, Set.of(EndForEachLoop.class)),
            Map.entry(BeginRepeatLoop.class, Set.of(EndRepeatLoop.class)),
            Map.entry(BeginTry.class, Set.of(BeginCatch.class, BeginFinally.class)),
            Map.entry(BeginCatch.class, Set.of(BeginFinally.class, EndTryCatchFinally.class)),
            Map.entry(BeginFinally.class, Set.of(EndTryCatchFinally.class)),
            Map.entry(BeginBlockStatement.class, Set.of(EndBlockStatement.class)),
            Map.entry(BeginSwitch.class, Set.of(EndSwitch.class)),
            Map.entry(BeginSwitchCase.class, Set.of(EndSwitchCase.class)),
            Map.entry(BeginSwitchDefaultCase.class, Set.of(EndSwitchCase.class)),
            Map.entry(BeginWith.class, Set.of(EndWith.class)),
            Map.entry(BeginCodeString.class, Set.of(EndCodeString.class))
    );

    public static boolean matchesEnd(Operation begin, Operation end) {
        Set<Class<? extends Operation>> ends = END_OF.get(begin.getClass());
        return ends != null && ends.contains(end.getClass());
    }

    /** Every concrete operation class, in definition order. */
    public static final List<Class<? extends Operation>> ALL = List.of(LoadInteger.class, LoadBigInt.class, LoadFloat.class, LoadString.class, LoadBoolean.class, LoadUndefined.class, LoadNull.class, LoadThis.class, LoadArguments.class, LoadNewTarget.class, LoadRegExp.class, LoadBuiltin.class, CreateNamedVariable.class, Declare.class, BeginObjectLiteral.class, ObjectLiteralAddProperty.class, ObjectLiteralAddElement.class, ObjectLiteralAddComputedProperty.class, ObjectLiteralCopyProperties.class, ObjectLiteralSetPrototype.class, BeginObjectLiteralMethod.class, EndObjectLiteralMethod.class, BeginObjectLiteralComputedMethod.class, EndObjectLiteralComputedMethod.class, BeginObjectLiteralGetter.class, EndObjectLiteralGetter.class, BeginObjectLiteralComputedGetter.class, EndObjectLiteralComputedGetter.class, BeginObjectLiteralSetter.class, EndObjectLiteralSetter.class, BeginObjectLiteralComputedSetter.class, EndObjectLiteralComputedSetter.class, EndObjectLiteral.class, CreateArray.class, CreateIntArray.class, CreateFloatArray.class, CreateArrayWithSpread.class, CreateTemplateString.class, GetProperty.class, SetProperty.class, UpdateProperty.class, DeleteProperty.class, ConfigureProperty.class, GetElement.class, SetElement.class, UpdateElement.class, DeleteElement.class, ConfigureElement.class, GetComputedProperty.class, SetComputedProperty.class, UpdateComputedProperty.class, DeleteComputedProperty.class, ConfigureComputedProperty.class, TypeOf.class, Void.class, TestInstanceOf.class, TestIn.class, BeginPlainFunction.class, EndPlainFunction.class, BeginArrowFunction.class, EndArrowFunction.class, BeginGeneratorFunction.class, EndGeneratorFunction.class, Directive.class, Return.class, Yield.class, YieldEach.class, CallFunction.class, CallFunctionWithSpread.class, Construct.class, ConstructWithSpread.class, CallMethod.class, CallMethodWithSpread.class, CallComputedMethod.class, CallComputedMethodWithSpread.class, UnaryOperation.class, BinaryOperation.class, TernaryOperation.class, Reassign.class, Update.class, Compare.class, CallSuperMethod.class, GetSuperProperty.class, SetSuperProperty.class, SetComputedSuperProperty.class, GetComputedSuperProperty.class, UpdateSuperProperty.class, BeginIf.class, BeginElse.class, EndIf.class, BeginWhileLoopHeader.class, BeginWhileLoopBody.class, EndWhileLoop.class, BeginDoWhileLoopBody.class, BeginDoWhileLoopHeader.class, EndDoWhileLoop.class, BeginForLoopInitializer.class, BeginForLoopCondition.class, BeginForLoopAfterthought.class, BeginForLoopBody.class, EndForLoop.class, BeginForEachLoop.class, EndForEachLoop.class, BeginRepeatLoop.class, EndRepeatLoop.class, LoopBreak.class, LoopContinue.class, BeginTry.class, BeginCatch.class, BeginFinally.class, EndTryCatchFinally.class, ThrowException.class, BeginBlockStatement.class, EndBlockStatement.class, BlockBreak.class, BeginSwitch.class, BeginSwitchCase.class, BeginSwitchDefaultCase.class, EndSwitchCase.class, EndSwitch.class, SwitchBreak.class, BeginWith.class, EndWith.class, BeginCodeString.class, EndCodeString.class, Destruct.class, DestructAndReassign.class, Probe.class);

    public static final List<Class<? extends Operation>> SUBROUTINE_BEGINS = List.of(
            BeginPlainFunction.class, BeginArrowFunction.class, BeginGeneratorFunction.class,
            BeginObjectLiteralMethod.class, BeginObjectLiteralComputedMethod.class,
            BeginObjectLiteralGetter.class, BeginObjectLiteralComputedGetter.class,
            BeginObjectLiteralSetter.class, BeginObjectLiteralComputedSetter.class);
    public static final List<Class<? extends Operation>> FUNCTION_BEGINS = List.of(
            BeginPlainFunction.class, BeginArrowFunction.class, BeginGeneratorFunction.class);
    public static final List<Class<? extends Operation>> SPREAD_CALLS = List.of(
            CallFunctionWithSpread.class, ConstructWithSpread.class, CallMethodWithSpread.class,
            CallComputedMethodWithSpread.class);

    public static boolean isAny(Operation op, List<Class<? extends Operation>> classes) {
        for (Class<? extends Operation> c : classes) if (c.isInstance(op)) return true;
        return false;
    }
}
