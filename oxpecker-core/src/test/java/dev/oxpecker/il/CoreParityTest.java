package dev.oxpecker.il;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.oxpecker.il.Ops.Operation;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The IL core against the Python implementation's dump (the Python port's scripts/parity/export_core.py, removed with it; see git history). */
class CoreParityTest {
    static final JsonObject P;
    static {
        try (var in = CoreParityTest.class.getResourceAsStream("/parity/core.json")) {
            P = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String str(JsonElement e) { return e == null || e.isJsonNull() ? null : e.getAsString(); }

    static List<String> strs(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test void operationSpecs() throws Exception {
        JsonObject ops = P.getAsJsonObject("ops");
        assertEquals(new ArrayList<>(ops.keySet()), Ops.ALL.stream().map(Class::getSimpleName).toList());
        for (Class<? extends Operation> c : Ops.ALL) {
            Ops.Spec s = (Ops.Spec) c.getDeclaredField("SPEC").get(null);
            JsonObject o = ops.getAsJsonObject(c.getSimpleName());
            String n = c.getSimpleName();
            assertEquals(o.get("inputs").getAsInt(), s.inputs(), n);
            assertEquals(o.get("outputs").getAsInt(), s.outputs(), n);
            assertEquals(o.get("inner").getAsInt(), s.innerOutputs(), n);
            assertEquals(o.get("firstVariadic").getAsInt(), s.firstVariadic(), n);
            assertEquals(o.get("attrs").getAsInt(), s.attrs(), n);
            assertEquals(o.get("required").getAsInt(), s.required(), n);
            assertEquals(o.get("opened").getAsInt(), s.opened(), n);
        }
    }

    @Test void builtins() {
        JsonObject bs = P.getAsJsonObject("builtins");
        assertEquals(new ArrayList<>(bs.keySet()), new ArrayList<>(Env.BUILTINS.keySet()));
        for (var e : bs.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Builtin b = Env.BUILTINS.get(e.getKey());
            assertEquals(str(o.get("name")), b.name());
            assertEquals(str(o.get("type")), b.type().toString(), b.name());
            assertEquals(str(o.get("constructs")), b.constructs() == null ? null : b.constructs().toString(), b.name());
            assertEquals(str(o.get("calls")), b.calls() == null ? null : b.calls().toString(), b.name());
            assertEquals(str(o.get("group")), b.group(), b.name());
            if (o.get("signature").isJsonNull()) {
                assertEquals(null, b.signature());
            } else {
                List<String> sig = new ArrayList<>();
                for (Param p : b.signature().params()) sig.add(p.type() + ":" + p.mode().value);
                sig.add(b.signature().returns().toString());
                assertEquals(strs(o.getAsJsonArray("signature")), sig);
            }
        }
    }

    @Test void keysAndMethods() {
        JsonObject ks = P.getAsJsonObject("keys");
        assertEquals(new ArrayList<>(ks.keySet()), new ArrayList<>(Env.KEYS.keySet()));
        for (var e : ks.entrySet()) {
            Key k = Env.KEYS.get(e.getKey());
            assertEquals(strs(e.getValue().getAsJsonArray()), List.of(k.name(), k.kind().value));
        }
        JsonObject ms = P.getAsJsonObject("methods");
        assertEquals(new ArrayList<>(ms.keySet()), new ArrayList<>(Env.METHODS.keySet()));
        for (var e : ms.entrySet()) {
            Method m = Env.METHODS.get(e.getKey());
            JsonArray a = e.getValue().getAsJsonArray();
            assertEquals(a.get(0).getAsString(), m.receiver());
            assertEquals(a.get(1).getAsString(), m.name());
            assertEquals(a.get(2).getAsInt(), m.arity());
            assertEquals(strs(a.get(3).getAsJsonArray()), m.params().stream().map(Type::toString).toList(), e.getKey());
            assertEquals(a.get(4).getAsString(), m.returns().toString(), e.getKey());
        }
        JsonObject bg = P.getAsJsonObject("methodsByGroup");
        assertEquals(new ArrayList<>(bg.keySet()), new ArrayList<>(Env.METHODS_BY_GROUP.keySet()));
        for (var e : bg.entrySet())
            assertEquals(strs(e.getValue().getAsJsonArray()),
                    Env.METHODS_BY_GROUP.get(e.getKey()).stream().map(Method::name).toList());
        JsonObject pg = P.getAsJsonObject("propsByGroup");
        assertEquals(pg.keySet(), Env.PROPS_BY_GROUP.keySet());
        for (var e : pg.entrySet())
            assertEquals(strs(e.getValue().getAsJsonArray()),
                    Env.PROPS_BY_GROUP.get(e.getKey()).stream().map(Key::name).sorted().toList());
    }

    @Test void pools() {
        assertEquals(strs(P.getAsJsonArray("syntaxSupported")), List.copyOf(Env.SYNTAX_SUPPORTED));
        assertEquals(strs(P.getAsJsonArray("builtinPool")), Env.BUILTIN_POOL.stream().map(Builtin::name).toList());
        assertEquals(strs(P.getAsJsonArray("typedArrays")), Env.TYPED_ARRAY_CONSTRUCTORS.stream().map(Builtin::name).toList());
        assertEquals(strs(P.getAsJsonArray("errors")), Env.ERROR_CONSTRUCTORS.stream().map(Builtin::name).toList());
        List<List<String>> ik = new ArrayList<>();
        P.getAsJsonArray("interestingKeys").forEach(e -> ik.add(strs(e.getAsJsonArray())));
        assertEquals(ik, Env.INTERESTING_KEYS.stream().map(k -> List.of(k.name(), k.kind().value)).toList());
    }

    @Test void reprs() {
        JsonArray fr = P.getAsJsonArray("floatReprs"), fv = P.getAsJsonArray("floatValues");
        for (int i = 0; i < fr.size(); i++)
            assertEquals(fr.get(i).getAsString(), Repr.floatRepr(fv.get(i).getAsDouble()));
        for (JsonElement e : P.getAsJsonArray("strReprs")) {
            JsonArray a = e.getAsJsonArray();
            assertEquals(a.get(1).getAsString(), Repr.str(a.get(0).getAsString()));
        }
    }

    @Test void flagsPrintLikePython() {
        assertEquals("<RegExpFlag.NONE: 0>", RegExpFlag.NONE.toString());
        assertEquals("<RegExpFlag.IGNORE_CASE|GLOBAL: 3>", RegExpFlag.of(3).toString());
    }
}
