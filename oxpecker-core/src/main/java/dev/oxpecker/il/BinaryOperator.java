package dev.oxpecker.il;

import java.util.List;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum BinaryOperator implements Repr.Valued {
    ADD("add"),
    SUB("sub"),
    MUL("mul"),
    DIV("div"),
    MOD("mod"),
    BIT_AND("bit-and"),
    BIT_OR("bit-or"),
    LOGIC_AND("logic-and"),
    LOGIC_OR("logic-or"),
    XOR("xor"),
    LSHIFT("lshift"),
    RSHIFT("rshift"),
    EXP("exp"),
    U_RSHIFT("u-rshift"),
    NULL_COALESCE("null-coalesce");

    public final String value;

    BinaryOperator(String value) { this.value = value; }

    @Override public Object value() { return value; }

    public static final Set<BinaryOperator> ARITHMETIC = Collections.unmodifiableSet(EnumSet.of(ADD, SUB, MUL, DIV, MOD, EXP));
    public static final Set<BinaryOperator> BITWISE = Collections.unmodifiableSet(EnumSet.of(BIT_AND, BIT_OR, XOR, LSHIFT, RSHIFT, U_RSHIFT));
    public static final Set<BinaryOperator> LOGICAL = Collections.unmodifiableSet(EnumSet.of(LOGIC_AND, LOGIC_OR, NULL_COALESCE));
    public static final List<Set<BinaryOperator>> OPERATOR_GROUPS = List.of(ARITHMETIC, BITWISE, LOGICAL);
}
