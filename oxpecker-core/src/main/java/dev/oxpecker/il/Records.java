package dev.oxpecker.il;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

/** Python's {@code dataclasses.replace} and {@code hasattr} over the operation records. */
public final class Records {
    private Records() {}

    /** The component named {@code name}, or null when the record has none. */
    public static RecordComponent component(Record r, String name) {
        for (RecordComponent c : r.getClass().getRecordComponents()) if (c.getName().equals(name)) return c;
        return null;
    }

    public static boolean has(Record r, String name) { return component(r, name) != null; }

    public static Object get(Record r, String name) {
        RecordComponent c = component(r, name);
        if (c == null) throw new IllegalArgumentException(r.getClass().getSimpleName() + " has no " + name);
        try {
            return c.getAccessor().invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A copy of {@code r} with component {@code name} set to {@code value}. */
    @SuppressWarnings("unchecked")
    public static <R extends Record> R with(R r, String name, Object value) {
        RecordComponent[] cs = r.getClass().getRecordComponents();
        Object[] args = new Object[cs.length];
        boolean found = false;
        try {
            for (int i = 0; i < cs.length; i++) {
                if (cs[i].getName().equals(name)) {
                    args[i] = value;
                    found = true;
                } else {
                    args[i] = cs[i].getAccessor().invoke(r);
                }
            }
            if (!found) throw new IllegalArgumentException(r.getClass().getSimpleName() + " has no " + name);
            Class<?>[] types = Arrays.stream(cs).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Constructor<?> k = r.getClass().getDeclaredConstructor(types);
            return (R) k.newInstance(args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
