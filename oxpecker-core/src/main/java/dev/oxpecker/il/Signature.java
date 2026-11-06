package dev.oxpecker.il;

import java.util.List;

/** FuzzIL's Signature: parameters (plain, optional, rest; rest last) and a return type. */
public record Signature(List<Param> params, Type returns) {
    public Signature {
        params = List.copyOf(params);
        boolean seenOpt = false;
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            if (p.mode() == ParamMode.REST && i != params.size() - 1)
                throw new IllegalArgumentException("a rest parameter must be last");
            if (p.mode() == ParamMode.OPT) seenOpt = true;
            else if (p.mode() == ParamMode.PLAIN && seenOpt)
                throw new IllegalArgumentException("a plain parameter cannot follow an optional one");
        }
    }

    public int minArguments() {
        return (int) params.stream().filter(p -> p.mode() == ParamMode.PLAIN).count();
    }

    public boolean hasRest() {
        return !params.isEmpty() && params.get(params.size() - 1).mode() == ParamMode.REST;
    }

    public boolean accepts(int n) {
        if (n < minArguments()) return false;
        return hasRest() || n <= params.size();
    }
}
