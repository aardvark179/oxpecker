package dev.oxpecker.il;

public enum ParamMode implements Repr.Valued {
    PLAIN("plain"), OPT("opt"), REST("rest");
    public final String value;
    ParamMode(String value) { this.value = value; }

    @Override public Object value() { return value; }
}
