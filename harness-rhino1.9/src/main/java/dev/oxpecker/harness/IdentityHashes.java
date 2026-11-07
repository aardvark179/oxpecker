package dev.oxpecker.harness;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.lc.type.TypeInfo;

/**
 * Java identity hashes in printed values. A Java object's default {@code toString}
 * ({@code [I@6e6b878}, {@code java.lang.Object@1a2b}) carries its identity hash, which differs
 * between any two runs, so the harness masks it before reporting: the client compares outcomes
 * as they arrive.
 *
 * <p>Such a string can only appear once the script holds a Java object whose class prints its
 * hash. The {@link Factory} notes every Java object handed to JavaScript; outcomes of runs that
 * never received one (most of them) are reported untouched, without scanning.
 */
final class IdentityHashes {
    private IdentityHashes() {}

    /** A dotted class name ({@code java.lang.Object}) or a JVM array descriptor ({@code [I},
     * {@code [[D}, {@code [Ljava.lang.String;}) followed by {@code @} and up to eight hex digits. No
     * word boundary after the hash: the script may concatenate text straight onto it
     * ({@code J.intArray(0) + "x"}), and the hash's length varies. canon.js's own {@code @N} ids
     * never follow an identifier character, so they are untouched. */
    private static final Pattern IDENTITY_HASH = Pattern.compile(
            "((?:(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*|\\[+(?:[ZBCSIJFD]|L[\\w.$]+;))@)[0-9a-f]{1,8}",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);

    /** {@code dev.oxpecker.harness.JavaProbe@3bde59e5} -> {@code ...JavaProbe@}, {@code [I@6e6b878}
     * -> {@code [I@}. */
    static String mask(String s) {
        return s == null || s.indexOf('@') < 0 ? s : IDENTITY_HASH.matcher(s).replaceAll("$1");
    }

    static List<String> mask(List<String> l) {
        List<String> out = new ArrayList<>(l.size());
        for (String t : l) out.add(mask(t));
        return out;
    }

    /** Masks the outcome's printed fields: completion value, probe trace, print output and the J.* trace. */
    static void mask(Outcome o) {
        o.value = mask(o.value);
        o.trace = mask(o.trace);
        o.output = mask(o.output);
        o.javaTrace = mask(o.javaTrace);
    }

    /** Classes whose {@code toString} never prints an identity hash (JavaProbe's is fixed; the
     * list and map it returns print their elements, and any element that could print a hash
     * reached the script through this factory first). */
    private static final Set<Class<?>> SAFE = Set.of(String.class, Boolean.class, Character.class,
            Integer.class, Long.class, Short.class, Byte.class, Double.class, Float.class,
            BigInteger.class, BigDecimal.class, JavaProbe.class, ArrayList.class, LinkedHashMap.class);

    /** The default wrap factory, noting whether any Java object outside {@link #SAFE} (an array,
     * a Class reached through getClass(), an exception, ...) was handed to the script. */
    static final class Factory extends WrapFactory {
        private boolean seen;

        /** Whether the outcome may contain an identity hash. */
        boolean seen() {
            return seen;
        }

        private void note(Object o) {
            if (!seen && o != null && !(o instanceof Scriptable) && !SAFE.contains(o.getClass())) seen = true;
        }

        @Override
        public Object wrap(Context cx, Scriptable scope, Object obj, TypeInfo staticType) {
            note(obj);
            return super.wrap(cx, scope, obj, staticType);
        }

        @Override
        public Scriptable wrapNewObject(Context cx, Scriptable scope, Object obj) {
            note(obj);
            return super.wrapNewObject(cx, scope, obj);
        }

        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, TypeInfo staticType) {
            note(javaObject);
            return super.wrapAsJavaObject(cx, scope, javaObject, staticType);
        }

        @Override
        public Scriptable wrapJavaClass(Context cx, Scriptable scope, Class<?> javaClass) {
            note(javaClass);
            return super.wrapJavaClass(cx, scope, javaClass);
        }
    }
}
