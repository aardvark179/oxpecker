package dev.oxpecker.gen;

/**
 * A strategy: draws a value from a {@link Draw}. The Java counterpart of a Hypothesis
 * {@code st.composite}: {@code (draw, ...) -> value} becomes {@code d -> value}, and
 * {@code draw(strategy)} becomes {@code d.draw(gen)}.
 *
 * <p>A Gen must take every random decision through the {@code Draw} it is given (never through
 * a captured outer one, which jetCheck reports as {@code WrongDataStructure}), and must be
 * deterministic in its draws: the same choices give the same value.
 */
@FunctionalInterface
public interface Gen<T> {
    T draw(Draw d);
}
