package dev.oxpecker.il;

/** A Python Flag enum's value: a bit mask over named members, printed like Python 3.13 does. */
public record Flags(String type, String[] names, int bits) implements Comparable<Flags> {
    public boolean has(int bit) { return (bits & bit) == bit; }

    public boolean isEmpty() { return bits == 0; }

    public Flags with(int bits) { return new Flags(type, names, bits); }

    @Override public boolean equals(Object o) {
        return o instanceof Flags f && f.type.equals(type) && f.bits == bits;
    }

    @Override public int hashCode() { return type.hashCode() * 31 + bits; }

    @Override public int compareTo(Flags o) { return Integer.compare(bits, o.bits); }

    @Override public String toString() {
        if (bits == 0) return "<" + type + "." + names[0] + ": 0>";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < names.length; i++) {
            if ((bits & (1 << (i - 1))) != 0) {
                if (!sb.isEmpty()) sb.append('|');
                sb.append(names[i]);
            }
        }
        return "<" + type + "." + sb + ": " + bits + ">";
    }
}
