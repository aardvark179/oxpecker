package dev.oxpecker.il;

public record Param(Type type, ParamMode mode) {
    public Param(Type type) { this(type, ParamMode.PLAIN); }
}
