package dev.oxpecker.gen;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;

/**
 * The sequence of ints a draw consumed, one per primitive choice in draw order: what a corpus
 * entry or a finding stores, so the value can be replayed exactly, extended and shrunk. The text
 * form is {@code "j1:"} + base64url of zigzag varints.
 */
public record Choices(int[] values) {
    public static final Choices EMPTY = new Choices(new int[0]);
    private static final String PREFIX = "j1:";

    public Choices {
        values = values.clone();
    }

    public static Choices of(int... values) { return new Choices(values); }

    @Override public int[] values() { return values.clone(); }

    public int size() { return values.length; }

    public boolean isEmpty() { return values.length == 0; }

    public int get(int i) { return values[i < 0 ? values.length + i : i]; }

    public Choices prefix(int n) { return new Choices(Arrays.copyOf(values, n)); }

    public Choices suffix(int from) { return new Choices(Arrays.copyOfRange(values, from, values.length)); }

    public Choices concat(Choices other) {
        int[] out = Arrays.copyOf(values, values.length + other.values.length);
        System.arraycopy(other.values, 0, out, values.length, other.values.length);
        return new Choices(out);
    }

    public boolean startsWith(Choices p) {
        return p.values.length <= values.length && Arrays.equals(values, 0, p.values.length, p.values, 0, p.values.length);
    }

    public String encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            int z = (v << 1) ^ (v >> 31);
            while ((z & ~0x7F) != 0) {
                out.write((z & 0x7F) | 0x80);
                z >>>= 7;
            }
            out.write(z);
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** The choices of an {@link #encode()}d text, or null when it is not one (an old Hypothesis encoding, say). */
    public static Choices decode(String text) {
        if (text == null || !text.startsWith(PREFIX)) return null;
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(text.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        int[] buf = new int[bytes.length];
        int n = 0, z = 0, shift = 0;
        for (byte b : bytes) {
            z |= (b & 0x7F) << shift;
            if ((b & 0x80) != 0) {
                shift += 7;
                if (shift > 28) return null;
            } else {
                buf[n++] = (z >>> 1) ^ -(z & 1);
                z = 0;
                shift = 0;
            }
        }
        if (shift != 0) return null;
        return new Choices(Arrays.copyOf(buf, n));
    }

    @Override public boolean equals(Object o) { return o instanceof Choices c && Arrays.equals(values, c.values); }

    @Override public int hashCode() { return Arrays.hashCode(values); }

    @Override public String toString() { return Arrays.toString(values); }
}
