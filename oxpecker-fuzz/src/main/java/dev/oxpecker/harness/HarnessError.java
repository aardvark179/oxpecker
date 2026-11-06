package dev.oxpecker.harness;

/** The harness process failed, died, or refused a request. */
public class HarnessError extends RuntimeException {
    public HarnessError(String message) {
        super(message);
    }

    public HarnessError(String message, Throwable cause) {
        super(message, cause);
    }
}
