// Translated from Fuzzilli (Copyright 2019-2025 Google LLC, Apache License 2.0),
// Sources/Fuzzilli/Lifting/JavaScriptLifter.swift (the per-operation rules, 341-2171, and the
// JavaScriptWriter expression-inlining helper, 2582-3062), Expression.swift, JSExpressions.swift
// and ScriptWriter.swift at commit be2811d00282320381fc8007d64873745772011d, with the
// oxpecker changes of docs/design-generation-layer.md §8 (per-instruction `lift` choice, declared
// variables never inlined, Rhino spellings, probes, provenance comments, escaped code strings).
// Ported from oxpecker/il/lift.py. See THIRD_PARTY_NOTICES.md and docs/licenses/Apache-2.0.txt.
package dev.oxpecker.il;

import dev.oxpecker.il.Ops.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lifter: the only place JavaScript text is produced. {@link #lift(List, LiftOptions)} is a
 * pure function of the instructions (including their {@code lift} choices) and the options.
 */
public final class Lifter {
    private Lifter() {}

    // =========================================================================== expressions
    public enum Assoc { NONE, LEFT, RIGHT }

    public enum Char {
        PURE,       // may be inlined at every use, across blocks
        EFFECTFUL   // single use, same block, nothing effectful in between
    }

    /**
     * An expression class. Compared by identity, as the Python compares them with {@code is}:
     * IDENTIFIER, LITERAL and KEYWORD have equal values but are different classes.
     */
    public static final class ExprType {
        public final int precedence;
        public final Assoc assoc;
        public final Char ch;

        ExprType(int precedence, Assoc assoc, Char ch) {
            this.precedence = precedence;
            this.assoc = assoc;
            this.ch = ch;
        }
    }

    static final ExprType IDENTIFIER = new ExprType(20, Assoc.NONE, Char.PURE);
    static final ExprType LITERAL = new ExprType(20, Assoc.NONE, Char.PURE);
    static final ExprType KEYWORD = new ExprType(20, Assoc.NONE, Char.PURE);
    static final ExprType REGEXP_LITERAL = new ExprType(20, Assoc.NONE, Char.EFFECTFUL);      // an object: never duplicated
    static final ExprType CALL = new ExprType(19, Assoc.LEFT, Char.EFFECTFUL);
    static final ExprType MEMBER = new ExprType(19, Assoc.LEFT, Char.EFFECTFUL);
    static final ExprType NEW = new ExprType(19, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType NUMBER_LITERAL = new ExprType(17, Assoc.NONE, Char.PURE);
    static final ExprType STRING_LITERAL = new ExprType(17, Assoc.NONE, Char.PURE);
    static final ExprType TEMPLATE_LITERAL = new ExprType(17, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType OBJECT_LITERAL = new ExprType(17, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType ARRAY_LITERAL = new ExprType(17, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType POSTFIX = new ExprType(16, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType UNARY = new ExprType(15, Assoc.RIGHT, Char.EFFECTFUL);
    static final ExprType BINARY = new ExprType(14, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType TERNARY = new ExprType(4, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType ASSIGNMENT = new ExprType(3, Assoc.RIGHT, Char.EFFECTFUL);
    static final ExprType YIELD = new ExprType(2, Assoc.RIGHT, Char.EFFECTFUL);
    static final ExprType SPREAD = new ExprType(2, Assoc.NONE, Char.EFFECTFUL);
    static final ExprType COMMA = new ExprType(1, Assoc.LEFT, Char.EFFECTFUL);

    public static final class Expr {
        final ExprType type;
        final String text;
        final int parts;

        Expr(ExprType type, String text) { this(type, text, 0); }

        Expr(ExprType type, String text, int parts) {
            this.type = type;
            this.text = text;
            this.parts = parts;
        }

        boolean effectful() { return type.ch == Char.EFFECTFUL; }

        boolean needsBrackets(Expr outer, boolean isLhs) {                  // Expression.swift:69-80
            if (type.precedence == outer.type.precedence) {
                if (type.assoc != outer.type.assoc) return true;
                if (type.assoc == Assoc.NONE) return true;
                if (type.assoc == Assoc.LEFT) return !isLhs;
                return isLhs;
            }
            return type.precedence < outer.type.precedence;
        }

        Expr plus(String other) { return new Expr(type, text + other, parts); }

        Expr plus(Expr other) {
            if (other.needsBrackets(this, parts == 0)) return new Expr(type, text + "(" + other.text + ")", parts + 1);
            return new Expr(type, text + other.text, parts + 1);
        }

        @Override public String toString() { return text; }
    }

    static Expr ident(String name) { return new Expr(IDENTIFIER, name); }

    // =========================================================================== spellings
    static String unaryToken(UnaryOperator op) {
        return switch (op) {
            case PRE_INC, POST_INC -> "++";
            case PRE_DEC, POST_DEC -> "--";
            case LOGICAL_NOT -> "!";
            case BITWISE_NOT -> "~";
            case PLUS -> "+";
            case MINUS -> "-";
        };
    }

    static String binaryToken(BinaryOperator op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "%";
            case BIT_AND -> "&";
            case BIT_OR -> "|";
            case LOGIC_AND -> "&&";
            case LOGIC_OR -> "||";
            case XOR -> "^";
            case LSHIFT -> "<<";
            case RSHIFT -> ">>";
            case EXP -> "**";
            case U_RSHIFT -> ">>>";
            case NULL_COALESCE -> "??";
        };
    }

    static String comparatorToken(CompareOperator op) {
        return switch (op) {
            case EQUAL -> "==";
            case STRICT_EQUAL -> "===";
            case NOT_EQUAL -> "!=";
            case STRICT_NOT_EQUAL -> "!==";
            case LESS -> "<";
            case LESS_EQUAL -> "<=";
            case GREATER -> ">";
            case GREATER_EQUAL -> ">=";
        };
    }

    /** JsOperations.swift:352-361: fixed order d g i m s u y. */
    private static final int[] REGEXP_FLAG_BITS = {RegExpFlag.GLOBAL, RegExpFlag.IGNORE_CASE, RegExpFlag.MULTILINE,
            RegExpFlag.DOT_ALL, RegExpFlag.UNICODE, RegExpFlag.STICKY};
    private static final String[] REGEXP_FLAG_CHARS = {"g", "i", "m", "s", "u", "y"};

    static String declarationToken(DeclarationKind k) { return k.value; }

    /** LIFT_TOKENS.get(lift, "var"): INLINE and null declare with var. */
    static String liftToken(Lift lift) {
        if (lift == Lift.LET) return "let";
        if (lift == Lift.CONST) return "const";
        return "var";
    }

    public static String spellInteger(long value, Radix radix) {
        return switch (radix) {
            case HEX -> value < 0 ? "-0x" + Long.toHexString(-value) : "0x" + Long.toHexString(value);
            case OCT -> value < 0 ? "-0o" + Long.toOctalString(-value) : "0o" + Long.toOctalString(value);
            case BIN -> value < 0 ? "-0b" + Long.toBinaryString(-value) : "0b" + Long.toBinaryString(value);
            case DEC -> Long.toString(value);
        };
    }

    public static String spellFloat(double value) { return spellFloat(value, FloatStyle.PLAIN); }

    public static String spellFloat(double value, FloatStyle style) {
        if (style == FloatStyle.EXPONENT) {
            // Python's f"{value:.17e}" (18 correctly rounded significant digits), trailing zeros and '.' stripped
            if (Double.isNaN(value)) return "nan";
            if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
            String sign = (value < 0 || (value == 0 && 1 / value < 0)) ? "-" : "";
            if (value == 0) return sign + "0e0";
            BigDecimal bd = new BigDecimal(Math.abs(value)).round(new MathContext(18, RoundingMode.HALF_EVEN));
            String digits = bd.unscaledValue().toString();
            int exp = digits.length() - 1 - bd.scale();
            String rest = digits.substring(1).replaceAll("0+$", "");
            return sign + digits.charAt(0) + (rest.isEmpty() ? "" : "." + rest) + "e" + exp;
        }
        return Repr.floatRepr(value);             // shortest round-trip; '1e+21', '5e-324', '1.0' are all JavaScript
    }

    /**
     * A double-quoted JavaScript string literal with every character the parser could choke on
     * escaped (FuzzIL escapes only the quote, JavaScriptLifter.swift:366).
     */
    public static String spellString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); ) {
            int code = value.codePointAt(i);
            i += Character.charCount(code);
            if (code == '"') out.append("\\\"");
            else if (code == '\\') out.append("\\\\");
            else if (code == '\n') out.append("\\n");
            else if (code == '\r') out.append("\\r");
            else if (code == '\t') out.append("\\t");
            else if (code < 0x20 || (0xD800 <= code && code <= 0xDFFF) || code == 0x2028 || code == 0x2029)
                out.append(String.format("\\u%04x", code));
            else out.appendCodePoint(code);
        }
        return out.append('"').toString();
    }

    public static String spellTemplatePart(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            int ch = value.codePointAt(i);
            i += Character.charCount(ch);
            if (ch == '`' || ch == '\\') out.append('\\').appendCodePoint(ch);
            else if (ch == '$' && value.startsWith("{", i)) out.append("\\$");
            else if (0xD800 <= ch && ch <= 0xDFFF) out.append(String.format("\\u%04x", ch));
            else out.appendCodePoint(ch);
        }
        return out.toString();
    }

    /** A property-definition key: bare identifier, bare index, or a quoted string. */
    public static String spellKey(Key key) {
        if (key.isIdentifier() || key.kind() == KeyKind.INDEX) return key.name();
        return spellString(key.name());
    }

    static Expr member(Expr obj, Key key, boolean optional) {            // liftMemberAccess, JavaScriptLifter.swift:2264-2270
        if (key.isIdentifier()) return new Expr(MEMBER, "").plus(obj).plus(optional ? "?." : ".").plus(key.name());
        String inner = key.kind() == KeyKind.INDEX ? key.name() : spellString(key.name());
        return new Expr(MEMBER, "").plus(obj).plus(optional ? "?.[" : "[").plus(inner).plus("]");
    }

    static Expr element(Expr obj, long index, boolean optional) {
        String idx = Long.toString(index);
        return new Expr(MEMBER, "").plus(obj).plus(optional ? "?.[" : "[").plus(idx).plus("]");
    }

    static Expr computed(Expr obj, Expr key, boolean optional) {
        return new Expr(MEMBER, "").plus(obj).plus(optional ? "?.[" : "[").plus(key.text).plus("]");
    }

    public static String regexpFlags(Flags flags) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < REGEXP_FLAG_BITS.length; k++) if (flags.has(REGEXP_FLAG_BITS[k])) sb.append(REGEXP_FLAG_CHARS[k]);
        return sb.toString();
    }

    // =========================================================================== writer
    static final class ScriptWriter {                                     // ScriptWriter.swift
        final List<String> lines = new ArrayList<>();
        int level;

        ScriptWriter(int indent) { this.level = indent; }

        void emit(String line) { lines.add("    ".repeat(level) + line); }

        void emitLines(List<String> ls) { for (String l : ls) emit(l); }

        void indent() { level++; }

        void dedent() { level--; }

        String text() { return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"); }
    }

    private record Buffer(ScriptWriter writer, List<Integer> pending) {}

    /**
     * JavaScriptWriter (JavaScriptLifter.swift:2582-3062): expression inlining with pending
     * effectful expressions flushed at every statement and block boundary.
     */
    static final class Writer {
        final Analysis an;
        final boolean comments;
        Map<Integer, Expr> exprs = new HashMap<>();
        List<Integer> pending = new ArrayList<>();
        Map<Integer, String> names = new HashMap<>();
        Set<Integer> declaredJs = new HashSet<>();             // variables that exist as JavaScript bindings
        final ScriptWriter out = new ScriptWriter(0);
        final List<Buffer> buffers = new ArrayList<>();
        String gen = "";
        String lastGen = "";
        boolean lastWasProbe = false;

        Writer(Analysis an, boolean comments) {
            this.an = an;
            this.comments = comments;
        }

        // -- output
        ScriptWriter w() { return buffers.isEmpty() ? out : buffers.get(buffers.size() - 1).writer(); }

        void emit(String line) {
            flush();
            commentForStatement();
            w().emit(line);
            lastWasProbe = false;
        }

        /** A line that is not a statement of the program (a brace, a case label): no provenance comment. */
        void emitRaw(String line) {
            flush();
            w().emit(line);
            lastWasProbe = false;
        }

        void commentForStatement() {
            if (comments && buffers.isEmpty() && w().level == 0 && !gen.isEmpty() && !gen.equals(lastGen)) {
                w().emit("// [" + gen + "]");
                lastGen = gen;
            }
        }

        void enterBlock() {
            flush();
            w().indent();
        }

        void leaveBlock() {
            flush();
            w().dedent();
        }

        void pushBuffer(int indent) {
            flush();
            buffers.add(new Buffer(new ScriptWriter(indent), pending));
            pending = new ArrayList<>();
        }

        List<String> popBuffer() {
            flush();
            Buffer b = buffers.remove(buffers.size() - 1);
            if (!pending.isEmpty()) throw new IllegalStateException("pending expressions left in a buffer");
            pending = b.pending();
            return b.writer().lines;
        }

        // -- names
        String nameOf(int v) {
            String n = names.get(v);
            if (n == null) throw new LiftError("no name for v" + v);
            return n;
        }

        String declare(int v) { return declare(v, null); }

        String declare(int v, String name) {
            if (!names.containsKey(v)) names.put(v, name != null && !name.isEmpty() ? name : defaultName(v));
            declaredJs.add(v);
            exprs.put(v, ident(names.get(v)));
            return names.get(v);
        }

        String defaultName(int v) {
            Storage s = an.storage().getOrDefault(v, Storage.TEMP);
            if (s == Storage.PARAM) return "p" + v;
            if (s == Storage.CATCH) return "e" + v;
            if (s == Storage.LOOPVAR || s == Storage.COUNTER || s == Storage.FOREACH) return "i" + v;
            if (s == Storage.FUNCTION) return "f" + v;
            if (s == Storage.LABEL) return "L" + v;
            Type t = an.types().get(v);
            String prefix = "v";
            if (t != null && !t.isNothing()) {
                int k = t.possible();
                if (k == Kind.FUNCTION.bit) prefix = "f";
                else if (k == Kind.STRING.bit) prefix = "s";
                else if (k == Kind.BOOLEAN.bit) prefix = "b";
                else if (k == Kind.BIGINT.bit) prefix = "big";
                else if ((k & ~(Kind.INTEGER.bit | Kind.FLOAT.bit)) == 0) prefix = "n";
                else if (k == Kind.OBJECT.bit) prefix = "Array".equals(t.group()) ? "arr" : "o";
            }
            return prefix + v;
        }

        // -- expressions
        boolean shouldInline(Expr e, int v) {                              // shouldTryInlining, 3039-3061
            if (an.numAssignments(v) > 1) return false;
            if (!e.effectful()) return true;
            return an.numUses(v) <= 1;
        }

        /** Bind the value of a value-producing instruction (2649-2676, plus the {@code lift} choice). */
        void assign(Expr e, int v, Lift lift, boolean allowInline) {
            if (lift == null) lift = Lift.INLINE;
            Expr cur = exprs.get(v);
            if (cur != null && cur.type == IDENTIFIER && declaredJs.contains(v)) {
                emit(cur + " = " + e + ";");
                return;
            }
            if (allowInline && lift == Lift.INLINE && shouldInline(e, v)) {
                exprs.put(v, e);
                if (e.effectful()) pending.add(v);
                return;
            }
            if (lift == Lift.INLINE && an.numUses(v) == 0 && e.type != OBJECT_LITERAL) {
                emit(e + ";");
                return;
            }
            String keyword = liftToken(lift);
            String name = declare(v);
            emit(keyword + " " + name + " = " + e + ";");
        }

        /** A declared variable (Declare, named variables): always a statement, never inlined. */
        void declareVar(int v, Expr e, String keyword) {
            String name = declare(v);
            emit(keyword + " " + name + " = " + e + ";");
        }

        List<Expr> retrieve(int[] vs) {                                    // 2719-2794
            List<Integer> queried = new ArrayList<>();
            for (int v : new LinkedHashSet<>(Typer.ints(vs))) if (pending.contains(v)) queried.add(v);
            int match = 0;
            while (match < queried.size() && match < pending.size()
                    && pending.get(pending.size() - 1 - match).equals(queried.get(queried.size() - 1 - match)))
                match++;
            if (match == queried.size()) {
                // the queried pending expressions are exactly the newest ones, in order: inline them,
                // older pending expressions stay pending
                pending = new ArrayList<>(pending.subList(0, pending.size() - match));
            } else {
                // out of order: everything pending becomes a statement first
                for (int v : pending) emitPending(v);
                pending = new ArrayList<>();
            }
            List<Expr> out = new ArrayList<>();
            for (int v : vs) {
                Expr e = exprs.get(v);
                if (e == null) throw new LiftError("no expression for v" + v);
                out.add(e);
                if (e.effectful() && e.type != IDENTIFIER) exprs.remove(v);      // an effectful expression is used exactly once
            }
            return out;
        }

        void flush() {                                                     // emitPendingExpressions, 2933
            List<Integer> p = pending;
            pending = new ArrayList<>();
            for (int v : p) emitPending(v);
        }

        void emitPending(int v) {                                          // emitPendingExpression, 2989-3018
            Expr e = exprs.remove(v);
            if (e == null) throw new LiftError("no pending expression for v" + v);
            commentForStatement();
            if (an.numUses(v) > 0) {
                String name = declare(v);
                w().emit("var " + name + " = " + e + ";");
            } else if (e.type == OBJECT_LITERAL) {
                String name = declare(v);
                w().emit("var " + name + " = " + e + ";");
            } else {
                w().emit(e + ";");
            }
            lastWasProbe = false;
        }

        Expr asIdentifier(Expr e, int v) {                                 // ensureIsIdentifier, 2799-2819
            if (e.type == IDENTIFIER || e.type == KEYWORD) return e;
            String name = declare(v, "t" + v);
            emit("var " + name + " = " + e + ";");
            return ident(name);
        }
    }

    // =========================================================================== the lifter
    public static String lift(List<Instruction> code) { return lift(code, LiftOptions.DEFAULT); }

    public static String lift(List<Instruction> code, LiftOptions options) {
        code = List.copyOf(code);
        Analysis an = Analysis.analyse(code, options.strict());
        Writer w = new Writer(an, options.comments());
        if (options.strict()) w.out.emit("\"use strict\";");
        liftRange(code, 0, code.size(), an, w, options);
        w.flush();
        return w.out.text();
    }

    public static String lift(Program program, LiftOptions options) { return lift(program.code(), options); }

    /** {@code lift} with the shell's defaults: no probes, no comments, sloppy. */
    public static String js(List<Instruction> code) { return lift(code, LiftOptions.PLAIN); }

    public static String js(List<Instruction> code, boolean probes, boolean comments, boolean strict, int probeWidth) {
        return lift(code, new LiftOptions(probes, comments, strict, probeWidth));
    }

    /** The code a builder holds so far (unfinished blocks are an error, as {@code check} says). */
    public static String js(Builder b) { return js(b.code); }

    private static boolean labelUsed(Analysis an, int label) { return an.numUses(label) > 0; }

    /** Parameter list: names, destructuring patterns, defaults and the rest parameter. */
    private static String paramsText(Writer w, Parameters params, List<Integer> inner, List<Expr> defaults) {
        List<String> pieces = new ArrayList<>();
        inner = new ArrayList<>(inner);
        Map<Integer, Patterns.Pattern> patterns = new LinkedHashMap<>();
        for (var e : params.destructuring()) patterns.put(e.getKey(), e.getValue());
        defaults = new ArrayList<>(defaults);
        for (int k = 0; k < params.count(); k++) {
            String text;
            if (patterns.containsKey(k)) {
                text = patternText(w, patterns.get(k), inner, new ArrayList<>(), true);
            } else {
                int v = inner.remove(0);
                text = w.declare(v);
            }
            if (params.defaultIndices().contains(k)) text += " = " + defaults.remove(0).text;
            if (params.hasRest() && k == params.count() - 1) text = "..." + text;
            pieces.add(text);
        }
        return String.join(", ", pieces);
    }

    /**
     * Destructuring pattern text; consumes bindings (fresh declarations or existing targets) and
     * extra inputs (computed keys, defaults, member-target objects) in DFS order.
     */
    private static String patternText(Writer w, Patterns.Pattern pattern, List<Integer> bindings, List<Expr> extras, boolean declare) {
        if (pattern instanceof Patterns.ObjectPattern o) {
            List<String> props = new ArrayList<>();
            for (Patterns.ObjectProperty p : o.properties()) {
                String key = p.key() != null ? spellKey(p.key()) : "[" + extras.remove(0).text + "]";
                String tgt = targetText(w, p.target(), bindings, extras, declare);
                if (p.hasDefault()) tgt += " = " + extras.remove(0).text;
                props.add(key + ": " + tgt);
            }
            if (o.hasRest()) props.add("..." + targetText(w, new Patterns.Binding(), bindings, extras, declare));
            return "{" + String.join(", ", props) + "}";
        }
        Patterns.ArrayPattern a = (Patterns.ArrayPattern) pattern;
        List<String> elems = new ArrayList<>();
        for (Patterns.ArrayElement e : a.elements()) {
            if (e.target() == null) {
                elems.add("");
                continue;
            }
            String tgt = targetText(w, e.target(), bindings, extras, declare);
            if (e.hasDefault()) tgt += " = " + extras.remove(0).text;
            elems.add(tgt);
        }
        if (a.rest() != null) elems.add("..." + targetText(w, a.rest(), bindings, extras, declare));
        return "[" + String.join(", ", elems) + (!elems.isEmpty() && elems.get(elems.size() - 1).isEmpty() ? "," : "") + "]";
    }

    private static String targetText(Writer w, Patterns.Target t, List<Integer> bindings, List<Expr> extras, boolean declare) {
        if (t instanceof Patterns.Binding) {
            int v = bindings.remove(0);
            return declare ? w.declare(v) : w.nameOf(v);
        }
        if (t instanceof Patterns.PropertyTarget pt) return member(extras.remove(0), pt.key(), false).text;
        if (t instanceof Patterns.ElementTarget et) return element(extras.remove(0), et.index(), false).text;
        if (t instanceof Patterns.ComputedTarget) {
            Expr obj = extras.remove(0);
            Expr key = extras.remove(0);
            return computed(obj, key, false).text;
        }
        return patternText(w, (Patterns.Pattern) t, bindings, extras, declare);
    }

    /**
     * Close a temporary buffer that should have produced one expression (loop headers,
     * JavaScriptLifter.swift:2206-2252): comma-join the pending expressions, or wrap statements in
     * an immediately invoked arrow function.
     */
    private static String singleExpression(Writer w, Expr result) {
        List<Integer> pending = w.pending;
        w.pending = new ArrayList<>();
        List<Expr> exprs = new ArrayList<>();
        for (int v : pending) if (w.exprs.containsKey(v)) exprs.add(w.exprs.remove(v));
        List<String> lines = w.popBuffer();
        if (lines.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Expr e : exprs) parts.add(e.text);
            if (result != null) parts.add(result.text);
            if (parts.size() <= 1) return parts.isEmpty() ? "" : parts.get(0);
            return String.join(", ", parts);
        }
        List<String> body = new ArrayList<>(lines);
        for (Expr e : exprs) body.add("    " + e.text + ";");
        if (result != null) body.add("    return " + result.text + ";");
        return "(() => {\n" + String.join("\n", body) + "\n})()";
    }

    private static final class LiteralState {
        final List<String> fields = new ArrayList<>();
        final List<Map.Entry<String, List<String>>> methods = new ArrayList<>();
        final List<String> open = new ArrayList<>();

        void openMethod(String head) { open.add(head); }

        void closeMethod(List<String> body) { methods.add(Map.entry(open.remove(open.size() - 1), body)); }
    }

    private static final class ForHeader {
        final String init;
        final List<String> names;
        String condition = "";

        ForHeader(String init, List<String> names) {
            this.init = init;
            this.names = names;
        }
    }

    /** The inputs of one instruction, fetched in order (the Python asserts it). */
    private static final class Args {
        final List<Expr> ins;
        int fetched = 0;

        Args(List<Expr> ins) { this.ins = ins; }

        Expr arg(int k) {
            if (k != fetched) throw new IllegalStateException("inputs must be fetched in order");
            fetched++;
            return ins.get(k);
        }

        List<Expr> range(int from, int to) {
            List<Expr> out = new ArrayList<>();
            for (int k = from; k < to; k++) out.add(arg(k));
            return out;
        }
    }

    private static boolean isObjectLiteralSubroutineBegin(Operation op) {
        return op instanceof BeginObjectLiteralMethod || op instanceof BeginObjectLiteralComputedMethod
                || op instanceof BeginObjectLiteralGetter || op instanceof BeginObjectLiteralComputedGetter
                || op instanceof BeginObjectLiteralSetter || op instanceof BeginObjectLiteralComputedSetter;
    }

    private static boolean isObjectLiteralSubroutineEnd(Operation op) {
        return op instanceof EndObjectLiteralMethod || op instanceof EndObjectLiteralComputedMethod
                || op instanceof EndObjectLiteralGetter || op instanceof EndObjectLiteralComputedGetter
                || op instanceof EndObjectLiteralSetter || op instanceof EndObjectLiteralComputedSetter;
    }

    /**
     * Lift code[start:end] into the writer. Called for the program and, recursively, for the
     * body of a code string.
     */
    private static void liftRange(List<Instruction> code, int start, int end, Analysis an, Writer w, LiftOptions options) {
        List<Integer> frames = new ArrayList<>(List.of(0));        // block depth within the current function frame
        List<Boolean> functionForms = new ArrayList<>();          // per open function: true = declaration statement, false = `var f = function`
        List<LiteralState> objectLiterals = new ArrayList<>();
        List<ForHeader> forHeaders = new ArrayList<>();
        int i = start;
        while (i < end) {
            Instruction instr = code.get(i);
            Operation op = instr.op();
            w.gen = instr.gen();

            // code strings: lift the nested program separately and emit it as a string literal
            if (op instanceof BeginCodeString) {
                int close = Program.blockEnd(code, i);
                if (!(code.get(close).op() instanceof EndCodeString)) throw new IllegalStateException("code string end");
                w.flush();
                String name = w.declare(instr.output());         // visible inside the string too (it is the string)
                Writer inner = new Writer(an, false);
                inner.names = w.names;
                inner.declaredJs = new HashSet<>(w.declaredJs);
                inner.exprs = new HashMap<>(w.exprs);            // after the flush: identifiers and pure expressions only
                liftRange(code, i + 1, close, an, inner, options);
                inner.flush();
                w.emit(liftToken(instr.lift()) + " " + name + " = " + spellString(inner.out.text()) + ";");
                i = close + 1;
                continue;
            }

            List<Expr> ins = w.retrieve(instr.inputs());
            Args a = new Args(ins);
            int[] inputs = instr.inputs();

            if (op instanceof LoadInteger o) {
                out(w, instr, new Expr(NUMBER_LITERAL, spellInteger(o.value(), o.radix())));
            } else if (op instanceof LoadBigInt o) {
                out(w, instr, new Expr(NUMBER_LITERAL, o.value() + "n"));
            } else if (op instanceof LoadFloat o) {
                out(w, instr, new Expr(NUMBER_LITERAL, spellFloat(o.value(), o.style())));
            } else if (op instanceof LoadString o) {
                out(w, instr, new Expr(STRING_LITERAL, spellString(o.value())));
            } else if (op instanceof LoadBoolean o) {
                out(w, instr, new Expr(LITERAL, o.value() ? "true" : "false"));
            } else if (op instanceof LoadUndefined) {
                out(w, instr, ident("undefined"));
            } else if (op instanceof LoadNull) {
                out(w, instr, new Expr(LITERAL, "null"));
            } else if (op instanceof LoadThis) {
                out(w, instr, new Expr(KEYWORD, "this"));
            } else if (op instanceof LoadArguments) {
                out(w, instr, ident("arguments"));
            } else if (op instanceof LoadNewTarget) {
                out(w, instr, new Expr(KEYWORD, "new.target"));
            } else if (op instanceof LoadRegExp o) {
                out(w, instr, new Expr(REGEXP_LITERAL, "/" + o.pattern() + "/" + regexpFlags(o.flags())));
            } else if (op instanceof LoadBuiltin o) {
                out(w, instr, ident(o.builtin().name()));
            } else if (op instanceof CreateNamedVariable o) {
                Expr value = a.arg(0);
                String name = o.identifier().name();
                w.names.put(instr.output(), name);
                if (o.mode() == NamedVariableMode.GLOBAL) w.emit(name + " = " + value + ";");
                else w.emit(o.mode().value + " " + name + " = " + value + ";");
                w.declare(instr.output(), name);
            } else if (op instanceof Declare o) {
                w.declareVar(instr.output(), a.arg(0), declarationToken(o.kind()));

            // ---------------------------------------------------------------- object literals
            } else if (op instanceof BeginObjectLiteral) {
                w.flush();
                objectLiterals.add(new LiteralState());
            } else if (op instanceof ObjectLiteralAddProperty o) {
                last(objectLiterals).fields.add(spellKey(o.key()) + ": " + a.arg(0));
            } else if (op instanceof ObjectLiteralAddElement o) {
                String idx = o.index() >= 0 ? Long.toString(o.index()) : spellString(Long.toString(o.index()));
                last(objectLiterals).fields.add(idx + ": " + a.arg(0));
            } else if (op instanceof ObjectLiteralAddComputedProperty) {
                Expr key = a.arg(0);
                last(objectLiterals).fields.add("[" + key + "]: " + a.arg(1));
            } else if (op instanceof ObjectLiteralCopyProperties) {
                last(objectLiterals).fields.add("..." + a.arg(0));
            } else if (op instanceof ObjectLiteralSetPrototype) {
                last(objectLiterals).fields.add("__proto__: " + a.arg(0));
            } else if (isObjectLiteralSubroutineBegin(op)) {
                List<Integer> inner = Typer.ints(instr.innerOutputs());
                int self = inner.remove(0);
                w.exprs.put(self, new Expr(KEYWORD, "this"));
                w.names.put(self, "this");
                String keyText;
                List<Expr> defaults;
                if (op instanceof BeginObjectLiteralComputedMethod || op instanceof BeginObjectLiteralComputedGetter
                        || op instanceof BeginObjectLiteralComputedSetter) {
                    keyText = "[" + a.arg(0).text + "]";
                    defaults = a.range(1, ins.size());
                } else {
                    Key key = op instanceof BeginObjectLiteralMethod m ? m.key()
                            : op instanceof BeginObjectLiteralGetter g ? g.key() : ((BeginObjectLiteralSetter) op).key();
                    keyText = spellKey(key);
                    defaults = a.range(0, ins.size());
                }
                String head;
                if (op instanceof BeginObjectLiteralMethod m) {
                    head = (m.generator() ? "*" : "") + keyText + "(" + paramsText(w, m.parameters(), inner, defaults) + ") {";
                } else if (op instanceof BeginObjectLiteralComputedMethod m) {
                    head = (m.generator() ? "*" : "") + keyText + "(" + paramsText(w, m.parameters(), inner, defaults) + ") {";
                } else if (op instanceof BeginObjectLiteralGetter || op instanceof BeginObjectLiteralComputedGetter) {
                    head = "get " + keyText + "() {";
                } else {
                    head = "set " + keyText + "(" + w.declare(inner.get(0)) + ") {";
                }
                last(objectLiterals).openMethod(head);
                w.pushBuffer(1);
                frames.add(0);
            } else if (isObjectLiteralSubroutineEnd(op)) {
                frames.remove(frames.size() - 1);
                List<String> body = w.popBuffer();
                last(objectLiterals).closeMethod(body);
            } else if (op instanceof EndObjectLiteral) {
                LiteralState state = objectLiterals.remove(objectLiterals.size() - 1);
                if (state.fields.isEmpty() && state.methods.isEmpty()) {
                    out(w, instr, new Expr(OBJECT_LITERAL, "{}"));
                } else if (state.methods.isEmpty() && state.fields.size() <= 4) {
                    out(w, instr, new Expr(OBJECT_LITERAL, "{ " + String.join(", ", state.fields) + " }"));
                } else {
                    String name = w.declare(instr.output());
                    String keyword = liftToken(instr.lift());
                    w.emit(keyword + " " + name + " = {");
                    w.w().indent();
                    for (String f : state.fields) w.w().emit(f + ",");
                    for (var m : state.methods) {
                        w.w().emit(m.getKey());
                        w.w().emitLines(m.getValue());
                        w.w().emit("},");
                    }
                    w.w().dedent();
                    w.w().emit("};");
                }

            // ---------------------------------------------------------------- arrays, strings
            } else if (op instanceof CreateArray) {
                List<String> items = new ArrayList<>();
                for (Expr e : a.range(0, ins.size())) items.add(e.text);
                out(w, instr, new Expr(ARRAY_LITERAL, "[" + String.join(", ", items) + "]"));
            } else if (op instanceof CreateIntArray o) {
                List<String> items = new ArrayList<>();
                for (long v : o.values()) items.add(Long.toString(v));
                out(w, instr, new Expr(ARRAY_LITERAL, "[" + String.join(", ", items) + "]"));
            } else if (op instanceof CreateFloatArray o) {
                List<String> items = new ArrayList<>();
                for (double v : o.values()) items.add(signedFloat(v));
                out(w, instr, new Expr(ARRAY_LITERAL, "[" + String.join(", ", items) + "]"));
            } else if (op instanceof CreateArrayWithSpread o) {
                List<String> items = new ArrayList<>();
                for (int k = 0; k < o.spreads().size(); k++) items.add((o.spreads().get(k) ? "..." : "") + a.arg(k).text);
                out(w, instr, new Expr(ARRAY_LITERAL, "[" + String.join(", ", items) + "]"));
            } else if (op instanceof CreateTemplateString o) {
                StringBuilder text = new StringBuilder("`").append(spellTemplatePart(o.parts().get(0)));
                for (int k = 0; k < o.parts().size() - 1; k++)
                    text.append("${").append(a.arg(k).text).append("}").append(spellTemplatePart(o.parts().get(k + 1)));
                out(w, instr, new Expr(TEMPLATE_LITERAL, text + "`"));

            // ---------------------------------------------------------------- properties
            } else if (op instanceof GetProperty o) {
                out(w, instr, member(a.arg(0), o.key(), o.receiverOptional()));
            } else if (op instanceof SetProperty o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                w.emit(member(obj, o.key(), false) + " = " + a.arg(1) + ";");
            } else if (op instanceof UpdateProperty o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                w.emit(member(obj, o.key(), false) + " " + binaryToken(o.op()) + "= " + a.arg(1) + ";");
            } else if (op instanceof DeleteProperty o) {
                out(w, instr, new Expr(UNARY, "delete ").plus(member(w.asIdentifier(a.arg(0), inputs[0]), o.key(), o.receiverOptional())));
            } else if (op instanceof ConfigureProperty o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                List<Expr> values = a.range(1, ins.size());
                w.emit("Object.defineProperty(" + obj + ", " + spellString(o.key().name()) + ", " + descriptor(o.flags(), o.type(), values) + ");");
            } else if (op instanceof GetElement o) {
                out(w, instr, element(a.arg(0), o.index(), o.receiverOptional()));
            } else if (op instanceof SetElement o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                w.emit(element(obj, o.index(), false) + " = " + a.arg(1) + ";");
            } else if (op instanceof UpdateElement o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                w.emit(element(obj, o.index(), false) + " " + binaryToken(o.op()) + "= " + a.arg(1) + ";");
            } else if (op instanceof DeleteElement o) {
                out(w, instr, new Expr(UNARY, "delete ").plus(element(w.asIdentifier(a.arg(0), inputs[0]), o.index(), o.receiverOptional())));
            } else if (op instanceof ConfigureElement o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                List<Expr> values = a.range(1, ins.size());
                w.emit("Object.defineProperty(" + obj + ", " + o.index() + ", " + descriptor(o.flags(), o.type(), values) + ");");
            } else if (op instanceof GetComputedProperty o) {
                Expr obj = a.arg(0);
                out(w, instr, computed(obj, a.arg(1), o.receiverOptional()));
            } else if (op instanceof SetComputedProperty) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                Expr key = a.arg(1);
                w.emit(computed(obj, key, false) + " = " + a.arg(2) + ";");
            } else if (op instanceof UpdateComputedProperty o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                Expr key = a.arg(1);
                w.emit(computed(obj, key, false) + " " + binaryToken(o.op()) + "= " + a.arg(2) + ";");
            } else if (op instanceof DeleteComputedProperty o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                out(w, instr, new Expr(UNARY, "delete ").plus(computed(obj, a.arg(1), o.receiverOptional())));
            } else if (op instanceof ConfigureComputedProperty o) {
                Expr obj = w.asIdentifier(a.arg(0), inputs[0]);
                Expr key = a.arg(1);
                List<Expr> values = a.range(2, ins.size());
                w.emit("Object.defineProperty(" + obj + ", " + key + ", " + descriptor(o.flags(), o.type(), values) + ");");
            } else if (op instanceof TypeOf) {
                out(w, instr, new Expr(UNARY, "typeof ").plus(a.arg(0)));
            } else if (op instanceof Ops.Void) {
                out(w, instr, new Expr(UNARY, "void ").plus(a.arg(0)));
            } else if (op instanceof TestInstanceOf) {
                Expr lhs = a.arg(0);
                out(w, instr, new Expr(BINARY, "").plus(lhs).plus(" instanceof ").plus(a.arg(1)));
            } else if (op instanceof TestIn) {
                Expr lhs = a.arg(0);
                out(w, instr, new Expr(BINARY, "").plus(lhs).plus(" in ").plus(a.arg(1)));

            // ---------------------------------------------------------------- functions
            } else if (Ops.isAny(op, Ops.FUNCTION_BEGINS)) {
                List<Expr> defaults = a.range(0, ins.size());
                String fname = w.declare(instr.output());
                String params = paramsText(w, Typer.parametersOf(op), Typer.ints(instr.innerOutputs()), defaults);
                boolean named = (op instanceof BeginPlainFunction p && p.named()) || (op instanceof BeginGeneratorFunction g && g.named());
                boolean statement = named && last(frames) == 0 && w.buffers.isEmpty();
                if (op instanceof BeginArrowFunction) {
                    w.emit("var " + fname + " = (" + params + ") => {");
                } else if (op instanceof BeginGeneratorFunction) {
                    w.emit(statement ? "function* " + fname + "(" + params + ") {" : "var " + fname + " = function* (" + params + ") {");
                } else {
                    w.emit(statement ? "function " + fname + "(" + params + ") {" : "var " + fname + " = function (" + params + ") {");
                }
                w.enterBlock();
                if (Typer.strictOf(op)) w.emit("\"use strict\";");
                frames.add(0);
                functionForms.add(statement);
            } else if (op instanceof EndPlainFunction || op instanceof EndArrowFunction || op instanceof EndGeneratorFunction) {
                frames.remove(frames.size() - 1);
                w.leaveBlock();
                w.emitRaw(functionForms.remove(functionForms.size() - 1) ? "}" : "};");
            } else if (op instanceof Directive) {
                w.emit("\"use strict\";");
            } else if (op instanceof Return) {
                w.emit(!ins.isEmpty() ? "return " + a.arg(0) + ";" : "return;");
            } else if (op instanceof Yield) {
                Expr e = !ins.isEmpty() ? new Expr(YIELD, "yield " + a.arg(0).text) : new Expr(YIELD, "yield");
                if (an.numUses(instr.output()) == 0) w.emit(e + ";");
                else out(w, instr, e);
            } else if (op instanceof YieldEach) {
                w.emit("yield* " + a.arg(0) + ";");

            // ---------------------------------------------------------------- calls
            } else if (op instanceof CallFunction || op instanceof CallFunctionWithSpread) {
                Expr f = w.asIdentifier(a.arg(0), inputs[0]);
                List<Boolean> spreads = op instanceof CallFunctionWithSpread s ? s.spreads() : List.of();
                boolean optional = op instanceof CallFunction c ? c.callOptional() : ((CallFunctionWithSpread) op).callOptional();
                String args = callArgs(a.range(1, ins.size()), spreads);
                out(w, instr, new Expr(CALL, "").plus(f).plus(optional ? "?.(" : "(").plus(args).plus(")"));
            } else if (op instanceof Construct || op instanceof ConstructWithSpread) {
                Expr c = w.asIdentifier(a.arg(0), inputs[0]);
                List<Boolean> spreads = op instanceof ConstructWithSpread s ? s.spreads() : List.of();
                String args = callArgs(a.range(1, ins.size()), spreads);
                w.assign(new Expr(NEW, "new ").plus(c).plus("(").plus(args).plus(")"), instr.output(), instr.lift(), false);
            } else if (op instanceof CallMethod || op instanceof CallMethodWithSpread) {
                Expr obj = a.arg(0);
                List<Boolean> spreads = op instanceof CallMethodWithSpread s ? s.spreads() : List.of();
                Method m = op instanceof CallMethod c ? c.method() : ((CallMethodWithSpread) op).method();
                boolean ro = op instanceof CallMethod c ? c.receiverOptional() : ((CallMethodWithSpread) op).receiverOptional();
                boolean co = op instanceof CallMethod c ? c.callOptional() : ((CallMethodWithSpread) op).callOptional();
                String args = callArgs(a.range(1, ins.size()), spreads);
                Expr callee = member(obj, new Key(m.name()), ro);
                out(w, instr, new Expr(CALL, "").plus(callee).plus(co ? "?.(" : "(").plus(args).plus(")"));
            } else if (op instanceof CallComputedMethod || op instanceof CallComputedMethodWithSpread) {
                Expr obj = a.arg(0);
                Expr key = a.arg(1);
                List<Boolean> spreads = op instanceof CallComputedMethodWithSpread s ? s.spreads() : List.of();
                boolean ro = op instanceof CallComputedMethod c ? c.receiverOptional() : ((CallComputedMethodWithSpread) op).receiverOptional();
                boolean co = op instanceof CallComputedMethod c ? c.callOptional() : ((CallComputedMethodWithSpread) op).callOptional();
                String args = callArgs(a.range(2, ins.size()), spreads);
                out(w, instr, new Expr(CALL, "").plus(computed(obj, key, ro)).plus(co ? "?.(" : "(").plus(args).plus(")"));
            } else if (op instanceof CallSuperMethod o) {
                String args = callArgs(a.range(0, ins.size()), List.of());
                out(w, instr, o.key().isIdentifier() ? new Expr(CALL, "super." + o.key().name() + "(" + args + ")")
                        : new Expr(CALL, "super[" + spellString(o.key().name()) + "](" + args + ")"));
            } else if (op instanceof GetSuperProperty o) {
                out(w, instr, new Expr(MEMBER, "super").plus(o.key().isIdentifier() ? "." + o.key().name()
                        : "[" + spellString(o.key().name()) + "]"));
            } else if (op instanceof SetSuperProperty o) {
                w.emit(member(new Expr(KEYWORD, "super"), o.key(), false) + " = " + a.arg(0) + ";");
            } else if (op instanceof UpdateSuperProperty o) {
                w.emit(member(new Expr(KEYWORD, "super"), o.key(), false) + " " + binaryToken(o.op()) + "= " + a.arg(0) + ";");
            } else if (op instanceof GetComputedSuperProperty) {
                out(w, instr, new Expr(MEMBER, "super[" + a.arg(0).text + "]"));
            } else if (op instanceof SetComputedSuperProperty) {
                Expr key = a.arg(0);
                w.emit("super[" + key + "] = " + a.arg(1) + ";");

            // ---------------------------------------------------------------- operators
            } else if (op instanceof UnaryOperation o) {
                String tok = unaryToken(o.op());
                if (o.op().isPostfix()) out(w, instr, new Expr(POSTFIX, "").plus(a.arg(0)).plus(tok));
                else out(w, instr, new Expr(UNARY, tok).plus(a.arg(0)));
            } else if (op instanceof BinaryOperation o) {
                Expr lhs = a.arg(0);
                Expr rhs = a.arg(1);
                if (o.op() == BinaryOperator.EXP && lhs.type == UNARY) lhs = new Expr(LITERAL, "(" + lhs.text + ")");
                out(w, instr, new Expr(BINARY, "").plus(lhs).plus(" " + binaryToken(o.op()) + " ").plus(rhs));
            } else if (op instanceof Compare o) {
                Expr lhs = a.arg(0);
                out(w, instr, new Expr(BINARY, "").plus(lhs).plus(" " + comparatorToken(o.op()) + " ").plus(a.arg(1)));
            } else if (op instanceof TernaryOperation) {
                Expr c = a.arg(0);
                Expr x = a.arg(1);
                out(w, instr, new Expr(TERNARY, "").plus(c).plus(" ? ").plus(x).plus(" : ").plus(a.arg(2)));
            } else if (op instanceof Reassign) {
                Expr target = a.arg(0);
                w.emit(target + " = " + a.arg(1) + ";");
            } else if (op instanceof Update o) {
                Expr target = a.arg(0);
                w.emit(target + " " + binaryToken(o.op()) + "= " + a.arg(1) + ";");

            // ---------------------------------------------------------------- destructuring
            } else if (op instanceof Destruct o) {
                Expr source = a.arg(0);
                List<Expr> extras = a.range(1, ins.size());
                String text = patternText(w, o.pattern(), Typer.ints(instr.outputs()), extras, true);
                w.emit(declarationToken(o.kind()) + " " + text + " = " + source + ";");
            } else if (op instanceof DestructAndReassign o) {
                Expr source = a.arg(0);
                int nb = Patterns.numBindings(o.pattern());
                List<Expr> targets = a.range(1, 1 + nb);
                for (Expr e : targets)
                    if (e.type != IDENTIFIER) throw new LiftError("destructuring assignment targets must be identifiers");
                List<Expr> extras = a.range(1 + nb, ins.size());
                List<Integer> bindings = new ArrayList<>();
                for (int k = 1; k < 1 + nb; k++) bindings.add(inputs[k]);
                String text = patternText(w, o.pattern(), bindings, extras, false);
                if (o.pattern() instanceof Patterns.ObjectPattern) w.emit("(" + text + " = " + source + ");");
                else w.emit(text + " = " + source + ";");

            // ---------------------------------------------------------------- control flow
            } else if (op instanceof BeginIf o) {
                Expr cond = a.arg(0);
                if (o.inverted()) cond = new Expr(UNARY, "!").plus(cond);
                w.emit(labelPrefix(an, w, instr.innerOutput()) + "if (" + cond + ") {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof BeginElse) {
                w.leaveBlock();
                w.emitRaw("} else {");
                w.enterBlock();
            } else if (op instanceof EndIf) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof BeginWhileLoopHeader) {
                w.flush();
                w.pushBuffer(w.w().level + 1);
            } else if (op instanceof BeginWhileLoopBody) {
                String cond = singleExpression(w, a.arg(0));
                w.emit(labelPrefix(an, w, instr.innerOutput()) + "while (" + cond + ") {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof EndWhileLoop) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof BeginDoWhileLoopBody) {
                w.emit(labelPrefix(an, w, instr.innerOutput()) + "do {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof BeginDoWhileLoopHeader) {
                w.leaveBlock();
                w.pushBuffer(w.w().level + 1);
            } else if (op instanceof EndDoWhileLoop) {
                String cond = singleExpression(w, a.arg(0));
                w.emitRaw("} while (" + cond + ");");
                bump(frames, -1);
            } else if (op instanceof BeginForLoopInitializer) {
                w.flush();
                w.pushBuffer(w.w().level + 1);
            } else if (op instanceof BeginForLoopCondition o) {
                List<String> names = new ArrayList<>();
                for (int v : instr.innerOutputs()) names.add(w.declare(v));
                List<String> initial = new ArrayList<>();
                for (Expr e : a.range(0, ins.size())) initial.add(e.text);
                List<String> lines = w.popBuffer();
                String init;
                if (o.numLoopVariables() == 0) {
                    init = "";
                } else if (lines.isEmpty()) {
                    List<String> parts = new ArrayList<>();
                    for (int k = 0; k < Math.min(names.size(), initial.size()); k++) parts.add(names.get(k) + " = " + initial.get(k));
                    init = declarationToken(o.declaration()) + " " + String.join(", ", parts);
                } else {
                    List<String> body = new ArrayList<>(lines);
                    body.add("    return [" + String.join(", ", initial) + "];");
                    init = declarationToken(o.declaration()) + " [" + String.join(", ", names) + "] = (() => {\n"
                            + String.join("\n", body) + "\n})()";
                }
                forHeaders.add(new ForHeader(init, names));
                w.pushBuffer(w.w().level + 1);
            } else if (op instanceof BeginForLoopAfterthought) {
                String cond = singleExpression(w, a.arg(0));
                ForHeader h = last(forHeaders);
                h.condition = cond.equals("true") ? "" : cond;
                int[] inner = instr.innerOutputs();
                for (int k = 0; k < Math.min(inner.length, h.names.size()); k++) aliasName(w, inner[k], h.names.get(k));
                w.pushBuffer(w.w().level + 1);
            } else if (op instanceof BeginForLoopBody) {
                String after = singleExpression(w, null);
                ForHeader header = forHeaders.remove(forHeaders.size() - 1);
                List<Integer> inner = Typer.ints(instr.innerOutputs());
                int label = inner.remove(inner.size() - 1);
                for (int k = 0; k < Math.min(inner.size(), header.names.size()); k++) aliasName(w, inner.get(k), header.names.get(k));
                String cond = !header.condition.isEmpty() ? " " + header.condition : "";
                after = !after.isEmpty() ? " " + after : "";
                w.emit(labelPrefix(an, w, label) + "for (" + header.init + ";" + cond + ";" + after + ") {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof EndForLoop) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof BeginForEachLoop o) {
                Expr iterable = a.arg(0);
                List<Expr> extras = a.range(1, ins.size());
                List<Integer> inner = Typer.ints(instr.innerOutputs());
                int label = inner.remove(inner.size() - 1);
                String target = o.pattern() == null ? w.declare(inner.get(0)) : patternText(w, o.pattern(), inner, extras, true);
                w.emit(labelPrefix(an, w, label) + "for (" + declarationToken(o.declaration()) + " " + target + " " + o.kind().value
                        + " " + iterable + ") {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof EndForEachLoop) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof BeginRepeatLoop o) {
                List<Integer> inner = Typer.ints(instr.innerOutputs());
                int label = inner.remove(inner.size() - 1);
                String counter = o.exposesCounter() ? w.declare(inner.get(0)) : "i" + instr.index();
                String kw = declarationToken(o.declaration());
                w.emit(labelPrefix(an, w, label) + "for (" + kw + " " + counter + " = 0; " + counter + " < " + o.iterations() + "; "
                        + counter + "++) {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof EndRepeatLoop) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof LoopBreak || op instanceof BlockBreak) {
                w.emit(!ins.isEmpty() ? "break " + w.nameOf(instr.input(0)) + ";" : "break;");
            } else if (op instanceof LoopContinue) {
                w.emit(!ins.isEmpty() ? "continue " + w.nameOf(instr.input(0)) + ";" : "continue;");
            } else if (op instanceof SwitchBreak) {
                w.emit("break;");
            } else if (op instanceof BeginTry) {
                w.emit("try {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof BeginCatch) {
                w.leaveBlock();
                w.emitRaw("} catch (" + w.declare(instr.innerOutput()) + ") {");
                w.enterBlock();
            } else if (op instanceof BeginFinally) {
                w.leaveBlock();
                w.emitRaw("} finally {");
                w.enterBlock();
            } else if (op instanceof EndTryCatchFinally) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof ThrowException) {
                w.emit("throw " + a.arg(0) + ";");
            } else if (op instanceof BeginBlockStatement) {
                w.emit(labelPrefix(an, w, instr.innerOutput()) + "{");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof EndBlockStatement) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof BeginSwitch) {
                String prefix = labelPrefix(an, w, instr.innerOutput());
                w.emit(prefix + "switch (" + a.arg(0) + ") {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof BeginSwitchCase) {
                w.emitRaw("case " + a.arg(0) + ":");
                w.enterBlock();
            } else if (op instanceof BeginSwitchDefaultCase) {
                w.emitRaw("default:");
                w.enterBlock();
            } else if (op instanceof EndSwitchCase o) {
                if (!o.fallsThrough()) w.emit("break;");
                w.leaveBlock();
            } else if (op instanceof EndSwitch) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);
            } else if (op instanceof BeginWith) {
                w.emit("with (" + a.arg(0) + ") {");
                w.enterBlock();
                bump(frames, 1);
            } else if (op instanceof EndWith) {
                w.leaveBlock();
                w.emitRaw("}");
                bump(frames, -1);

            // ---------------------------------------------------------------- oxpecker
            } else if (op instanceof Probe o) {
                if (options.probes() && !w.lastWasProbe) {
                    List<String> observed = new ArrayList<>();
                    for (int v : an.visibleAt().get(i)) {
                        Storage s = an.storage().get(v);
                        if (w.declaredJs.contains(v) && w.names.containsKey(v) && s != Storage.LABEL && s != Storage.THIS)
                            observed.add(w.nameOf(v));
                    }
                    int width = options.probeWidth();         // Python's [:width], negative widths included
                    int keep = width >= 0 ? Math.min(width, observed.size()) : Math.max(0, observed.size() + width);
                    observed = observed.subList(0, keep);
                    String label = o.number() == 0 ? o.label().value : o.label().value + o.number();
                    StringBuilder sb = new StringBuilder("__probe(").append(spellString(label));
                    for (String n : observed) sb.append(", ").append(n);
                    w.emit(sb.append(");").toString());
                    w.lastWasProbe = true;
                }
            } else {
                throw new LiftError("no lifting rule for " + op.name());
            }
            i++;
        }
    }

    private static void out(Writer w, Instruction instr, Expr e) { w.assign(e, instr.output(), instr.lift(), true); }

    private static void aliasName(Writer w, int v, String name) {
        w.names.put(v, name);
        w.exprs.put(v, ident(name));
        w.declaredJs.add(v);
    }

    private static String labelPrefix(Analysis an, Writer w, int label) {
        if (labelUsed(an, label)) return w.declare(label) + ": ";
        return "";
    }

    private static void bump(List<Integer> frames, int d) { frames.set(frames.size() - 1, frames.get(frames.size() - 1) + d); }

    private static <T> T last(List<T> xs) { return xs.get(xs.size() - 1); }

    private static String callArgs(List<Expr> args, List<Boolean> spreads) {
        List<String> parts = new ArrayList<>();
        if (!spreads.isEmpty()) {
            for (int k = 0; k < Math.min(args.size(), spreads.size()); k++) parts.add((spreads.get(k) ? "..." : "") + args.get(k).text);
        } else {
            for (Expr a : args) parts.add(a.text);
        }
        return String.join(", ", parts);
    }

    private static String descriptor(Flags flags, PropertyType kind, List<Expr> values) {
        List<String> parts = new ArrayList<>();
        if (flags.has(PropertyFlag.WRITABLE) && kind == PropertyType.VALUE) parts.add("writable: true");
        if (flags.has(PropertyFlag.CONFIGURABLE)) parts.add("configurable: true");
        if (flags.has(PropertyFlag.ENUMERABLE)) parts.add("enumerable: true");
        switch (kind) {
            case VALUE -> parts.add("value: " + values.get(0));
            case GETTER -> parts.add("get: " + values.get(0));
            case SETTER -> parts.add("set: " + values.get(0));
            default -> {
                parts.add("get: " + values.get(0));
                parts.add("set: " + values.get(1));
            }
        }
        return "{ " + String.join(", ", parts) + " }";
    }

    static String signedFloat(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (v == Double.POSITIVE_INFINITY) return "Infinity";
        if (v == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (v < 0 || (v == 0 && 1 / v < 0)) return "-" + spellFloat(-v);
        return spellFloat(v);
    }
}
