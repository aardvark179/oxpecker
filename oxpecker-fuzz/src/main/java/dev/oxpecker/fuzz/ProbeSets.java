package dev.oxpecker.fuzz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Probe sets: JaCoCo probe ids by class, each list sorted. */
public final class ProbeSets {
    private ProbeSets() {}

    public static Map<String, List<Integer>> empty() { return new LinkedHashMap<>(); }

    public static void union(Map<String, List<Integer>> into, Map<String, List<Integer>> more) {
        if (more == null) return;
        for (var e : more.entrySet()) {
            TreeSet<Integer> s = new TreeSet<>(into.getOrDefault(e.getKey(), List.of()));
            s.addAll(e.getValue());
            into.put(e.getKey(), new ArrayList<>(s));
        }
    }

    public static boolean covers(Map<String, List<Integer>> reached, Map<String, List<Integer>> required) {
        for (var e : required.entrySet())
            if (!new TreeSet<>(reached.getOrDefault(e.getKey(), List.of())).containsAll(e.getValue())) return false;
        return true;
    }

    public static int count(Map<String, List<Integer>> s) {
        int n = 0;
        for (List<Integer> v : s.values()) n += v.size();
        return n;
    }
}
