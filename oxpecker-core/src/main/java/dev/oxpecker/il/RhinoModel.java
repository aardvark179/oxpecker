package dev.oxpecker.il;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Rhino's compiled mode is predicted to do (§5.3). {@code activation}: function begin index
 * to its reasons, in function order (an empty list: the optimizer runs); {@code exprType}: every
 * value-producing instruction's Rhino type. The sets are sorted.
 */
public record RhinoModel(Map<Integer, List<ActivationReason>> activation, Set<Integer> numberVar,
                         Set<Integer> directCallTarget, Set<Integer> directCallSite, Map<Integer, RhinoType> exprType) {
    public boolean optimizerRuns(int fnIndex) {
        List<ActivationReason> r = activation.get(fnIndex);
        return r == null || r.isEmpty();
    }
}
