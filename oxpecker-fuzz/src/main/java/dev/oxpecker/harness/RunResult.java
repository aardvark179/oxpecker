package dev.oxpecker.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcomes of one case.
 *
 * @param results  by mode (HarnessClient) or by harness name (Ensemble), in the order they ran
 * @param guide    the key of the guide's outcome, when run through an Ensemble
 */
public record RunResult(String id, Map<String, Outcome> results, boolean restarted, String guide) {

    public RunResult(String id, Map<String, Outcome> results) {
        this(id, results, false, null);
    }

    public RunResult(String id, Map<String, Outcome> results, boolean restarted) {
        this(id, results, restarted, null);
    }

    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("id", id);
        JsonObject rs = new JsonObject();
        results.forEach((m, o) -> rs.add(m, o.toJson()));
        j.add("results", rs);
        j.addProperty("restarted", restarted);
        j.addProperty("guide", guide);
        return j;
    }

    /** The inverse of {@link #toJson} (for stored runs and fixtures). */
    public static RunResult fromJson(JsonObject j) {
        Map<String, Outcome> rs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("results").entrySet()) {
            rs.put(e.getKey(), Outcome.fromJson(e.getValue().getAsJsonObject()));
        }
        JsonElement r = j.get("restarted"), g = j.get("guide");
        return new RunResult(j.get("id").getAsString(), rs, r != null && !r.isJsonNull() && r.getAsBoolean(),
                g == null || g.isJsonNull() ? null : g.getAsString());
    }
}
