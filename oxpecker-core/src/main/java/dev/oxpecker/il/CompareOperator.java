package dev.oxpecker.il;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum CompareOperator implements Repr.Valued {
    EQUAL("equal"),
    STRICT_EQUAL("strict-equal"),
    NOT_EQUAL("not-equal"),
    STRICT_NOT_EQUAL("strict-not-equal"),
    LESS("less"),
    LESS_EQUAL("less-equal"),
    GREATER("greater"),
    GREATER_EQUAL("greater-equal");

    public final String value;

    CompareOperator(String value) { this.value = value; }

    @Override public Object value() { return value; }

    public static final Set<CompareOperator> EQUALITY = Collections.unmodifiableSet(EnumSet.of(EQUAL, STRICT_EQUAL, NOT_EQUAL, STRICT_NOT_EQUAL));
    public static final Set<CompareOperator> RELATIONAL = Collections.unmodifiableSet(EnumSet.of(LESS, LESS_EQUAL, GREATER, GREATER_EQUAL));
}
