package dev.oxpecker.il;

/** How a variable is stored (analysis.py Storage): decides assignability, naming and the Rhino model. */
public enum Storage {
    TEMP,
    TOP_VAR,
    TOP_LET,
    TOP_CONST,
    LOCAL_VAR,
    LOCAL_LET,
    LOCAL_CONST,
    BLOCK_LET,
    BLOCK_CONST,
    PARAM,
    CATCH,
    LOOPVAR,        // a four-part for header variable
    FOREACH,        // a for-in / for-of variable or binding
    FUNCTION,       // a function's own name
    NAMED,          // CreateNamedVariable
    LABEL,
    THIS,
    COUNTER,        // a repeat loop's counter: never assigned
    CODE            // the string of a code-string block
}
