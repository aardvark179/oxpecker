package dev.oxpecker.il;

/** A generator asked the builder for something the IL cannot express here. */
public class BuilderError extends RuntimeException {
    public BuilderError(String message) { super(message); }
}
