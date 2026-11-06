package dev.oxpecker.il;

import java.math.BigDecimal;

/**
 * Python's float repr ({@code repr(1e21) == '1e+21'}, {@code repr(0.1) == '0.1'}): the shortest
 * digits that round-trip, positional for exponents in [-4, 16), scientific otherwise.
 */
public final class PyFloat {
    private PyFloat() {}

    public static String repr(double d) {
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        BigDecimal bd = shortest(Math.abs(d));
        String digits = bd.unscaledValue().toString();
        int exp = digits.length() - 1 - bd.scale();         // decimal exponent of the first digit
        String sign = d < 0 ? "-" : "";
        if (exp >= -4 && exp < 16) {
            String s;
            if (exp >= digits.length() - 1) {
                s = digits + "0".repeat(exp - (digits.length() - 1)) + ".0";
            } else if (exp >= 0) {
                s = digits.substring(0, exp + 1) + "." + digits.substring(exp + 1);
            } else {
                s = "0." + "0".repeat(-exp - 1) + digits;
            }
            return sign + s;
        }
        String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
    }

    /** The shortest correctly rounded decimal that reads back as {@code d} (what Python's repr prints). */
    static BigDecimal shortest(double d) {
        BigDecimal exact = new BigDecimal(d);
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN));
            if (r.doubleValue() == d) return r.stripTrailingZeros();
        }
        return exact.stripTrailingZeros();
    }
}
