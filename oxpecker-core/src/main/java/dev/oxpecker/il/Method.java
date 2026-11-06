package dev.oxpecker.il;

import java.util.List;

/** A method of a receiver group ("Array", "Math", "ArrayCtor", "J"). */
public record Method(String receiver, String name, int arity, List<Type> params, Type returns) {
    public Method {
        params = List.copyOf(params);
    }

    @Override public String toString() { return "Method(" + Repr.str(receiver + "." + name) + ")"; }
}
