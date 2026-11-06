package dev.oxpecker.il;

/**
 * A property name with a class, so generators can ask for kinds of keys and the lifter decides
 * between dot, index and quoted spellings. The name is data, never code.
 */
public record Key(String name, KeyKind kind) implements Comparable<Key> {
    public Key {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a key needs a name");
        if (kind == KeyKind.INDEX && !(isDigits(name) && (name.equals("0") || name.charAt(0) != '0')))
            throw new IllegalArgumentException("not an index key: " + name);
        if (kind == KeyKind.LENGTH && !name.equals("length"))
            throw new IllegalArgumentException("the LENGTH kind is for 'length'");
        if (kind == KeyKind.PROTO && !name.equals("__proto__"))
            throw new IllegalArgumentException("the PROTO kind is for '__proto__'");
    }

    public Key(String name) { this(name, KeyKind.ORDINARY); }

    public boolean isIdentifier() { return isIdentifier(name); }

    /** Python's str.isidentifier-like test used by types.py: no leading digit, alphanumerics, _ and $. */
    public static boolean isIdentifier(String name) {
        if (name.isEmpty() || Character.isDigit(name.codePointAt(0))) return false;
        return name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '$');
    }

    /** Python's str.isdigit for the ASCII and Unicode digits the keys use. */
    public static boolean isDigits(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(Character::isDigit);
    }

    @Override public int compareTo(Key o) {
        int c = name.compareTo(o.name);
        return c != 0 ? c : kind.compareTo(o.kind);
    }

    @Override public String toString() {
        return "Key(name=" + Repr.str(name) + ", kind=" + Repr.of(kind) + ")";
    }
}
