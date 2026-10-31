package de.peakverse.core.plugin.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fuehrt Dateien zusammen, die mehrere Packs gemeinsam haben duerfen: Sprachdateien, Blockstates, Item-Definitionen, {@code sounds.json}
 * und Atlanten. Zwei Faelle sind <b>kein</b> Konflikt: derselbe Eintrag mit demselben Inhalt, und verschiedene Eintraege.
 * Derselbe Eintrag mit verschiedenem Inhalt ist ein Konflikt; dann wird nichts zusammengefuehrt.
 *
 * <p>Die Reihenfolge der Eintraege ergibt sich aus der Reihenfolge der Aufrufe (Basis-Pack zuerst, dann die Module nach ID), die Ausgabe ist
 * kompaktes JSON. Damit bleibt das Pack deterministisch.
 */
public final class JsonMerge {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern LANG = Pattern.compile("assets/[^/]+/lang/[^/]+\\.json");
    private static final Pattern SOUNDS = Pattern.compile("assets/[^/]+/sounds\\.json");
    private static final Pattern BLOCKSTATE = Pattern.compile("assets/[^/]+/blockstates/.+\\.json");
    private static final Pattern ITEM = Pattern.compile("assets/[^/]+/items/.+\\.json");
    private static final Pattern ATLAS = Pattern.compile("assets/[^/]+/atlases/.+\\.json");

    private JsonMerge() {}

    /** Ergebnis: entweder {@code merged} oder {@code conflicts} (nie beides). */
    public record Result(byte[] merged, List<String> conflicts) {
        public boolean ok() {
            return merged != null;
        }
    }

    public static boolean mergeable(String path) {
        return LANG.matcher(path).matches() || SOUNDS.matcher(path).matches() || BLOCKSTATE.matcher(path).matches()
                || ITEM.matcher(path).matches() || ATLAS.matcher(path).matches();
    }

    /** Fuehrt {@code incoming} in {@code existing} ein. Ungueltiges JSON oder falsche Form ist ein Konflikt, kein Absturz. */
    public static Result merge(String path, byte[] existing, byte[] incoming) {
        List<String> conflicts = new ArrayList<>();
        JsonObject a;
        JsonObject b;
        try {
            a = parseObject(existing);
            b = parseObject(incoming);
        } catch (RuntimeException e) {
            return new Result(null, List.of("kein gueltiges JSON-Objekt: " + e.getMessage()));
        }
        if (LANG.matcher(path).matches() || SOUNDS.matcher(path).matches()) mergeKeys(a, b, "", conflicts);
        else if (BLOCKSTATE.matcher(path).matches()) mergeBlockstate(a, b, conflicts);
        else if (ATLAS.matcher(path).matches()) mergeAtlas(a, b, conflicts);
        else if (ITEM.matcher(path).matches()) mergeItem(a, b, conflicts);
        else conflicts.add("dieser Dateityp kann nicht zusammengefuehrt werden");
        if (!conflicts.isEmpty()) return new Result(null, List.copyOf(conflicts));
        return new Result(GSON.toJson(a).getBytes(StandardCharsets.UTF_8), List.of());
    }

    private static JsonObject parseObject(byte[] data) {
        JsonElement e = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
        if (!e.isJsonObject()) throw new IllegalArgumentException("Wurzel ist kein Objekt");
        return e.getAsJsonObject();
    }

    /** Eintraege (Schluessel) verschiedener Objekte vereinigen; gleicher Schluessel mit anderem Inhalt ist ein Konflikt. */
    private static void mergeKeys(JsonObject into, JsonObject from, String what, List<String> conflicts) {
        for (Map.Entry<String, JsonElement> e : from.entrySet()) {
            JsonElement mine = into.get(e.getKey());
            if (mine == null) into.add(e.getKey(), e.getValue());
            else if (!mine.equals(e.getValue())) conflicts.add(what + "Eintrag '" + e.getKey() + "' hat in beiden Packs verschiedenen Inhalt");
        }
    }

    private static void mergeBlockstate(JsonObject a, JsonObject b, List<String> conflicts) {
        boolean av = a.has("variants");
        boolean bv = b.has("variants");
        boolean am = a.has("multipart");
        boolean bm = b.has("multipart");
        if (av && bv && !am && !bm) {
            if (!a.get("variants").isJsonObject() || !b.get("variants").isJsonObject()) {
                conflicts.add("'variants' ist kein Objekt");
                return;
            }
            mergeKeys(a.getAsJsonObject("variants"), b.getAsJsonObject("variants"), "Block-Zustand ", conflicts);
        } else if (am && bm && !av && !bv) {
            if (!a.get("multipart").isJsonArray() || !b.get("multipart").isJsonArray()) {
                conflicts.add("'multipart' ist keine Liste");
                return;
            }
            appendMissing(a.getAsJsonArray("multipart"), b.getAsJsonArray("multipart"));
        } else if (!a.equals(b)) {
            conflicts.add("ein Pack nutzt 'variants', das andere 'multipart' (oder andere Felder): nicht zusammenfuehrbar");
        }
    }

    private static void mergeAtlas(JsonObject a, JsonObject b, List<String> conflicts) {
        if (!a.has("sources") || !b.has("sources") || !a.get("sources").isJsonArray() || !b.get("sources").isJsonArray()) {
            if (!a.equals(b)) conflicts.add("Atlas ohne 'sources'-Liste: nicht zusammenfuehrbar");
            return;
        }
        for (String key : b.keySet()) {
            if (!key.equals("sources") && !java.util.Objects.equals(a.get(key), b.get(key))) conflicts.add("Feld '" + key + "' ist verschieden");
        }
        if (conflicts.isEmpty()) appendMissing(a.getAsJsonArray("sources"), b.getAsJsonArray("sources"));
    }

    private static void appendMissing(JsonArray into, JsonArray from) {
        for (JsonElement e : from) if (!into.contains(e)) into.add(e);
    }

    // ---- Item-Definitionen (assets/<ns>/items/*.json) ----

    private static void mergeItem(JsonObject a, JsonObject b, List<String> conflicts) {
        if (a.equals(b)) return;
        for (String key : b.keySet()) {
            if (!key.equals("model") && !java.util.Objects.equals(a.get(key), b.get(key))) conflicts.add("Feld '" + key + "' ist verschieden");
        }
        for (String key : a.keySet()) {
            if (!key.equals("model") && !b.has(key)) conflicts.add("Feld '" + key + "' fehlt im anderen Pack");
        }
        if (!conflicts.isEmpty()) return;
        if (!a.has("model") || !b.has("model") || !a.get("model").isJsonObject() || !b.get("model").isJsonObject()) {
            conflicts.add("Item-Definition ohne 'model'-Objekt: nicht zusammenfuehrbar");
            return;
        }
        JsonObject ma = a.getAsJsonObject("model");
        JsonObject mb = b.getAsJsonObject("model");
        String ta = type(ma);
        String tb = type(mb);
        if (!ta.equals(tb)) {
            conflicts.add("verschiedene Modell-Typen (" + ta + " und " + tb + "): ein Item kann nur ein Modell haben");
            return;
        }
        switch (ta) {
            case "select" -> mergeDispatch(ma, mb, "cases", "when", conflicts);
            case "range_dispatch" -> mergeDispatch(ma, mb, "entries", "threshold", conflicts);
            default -> conflicts.add("Modell-Typ '" + ta + "' ist in beiden Packs verschieden belegt und nicht zusammenfuehrbar");
        }
    }

    private static String type(JsonObject model) {
        String t = model.has("type") && model.get("type").isJsonPrimitive() ? model.get("type").getAsString() : "";
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
    }

    /** select (cases/when) und range_dispatch (entries/threshold): gleiche Eigenschaft, Faelle vereinigen, fallback nur einmal. */
    private static void mergeDispatch(JsonObject a, JsonObject b, String listKey, String idKey, List<String> conflicts) {
        for (String key : b.keySet()) {
            if (!key.equals(listKey) && !key.equals("fallback") && !java.util.Objects.equals(a.get(key), b.get(key))) {
                conflicts.add("Eigenschaft '" + key + "' ist verschieden");
            }
        }
        for (String key : a.keySet()) {
            if (!key.equals(listKey) && !key.equals("fallback") && !b.has(key)) conflicts.add("Eigenschaft '" + key + "' fehlt im anderen Pack");
        }
        if (!conflicts.isEmpty()) return;

        JsonArray mine = a.has(listKey) && a.get(listKey).isJsonArray() ? a.getAsJsonArray(listKey) : new JsonArray();
        JsonArray theirs = b.has(listKey) && b.get(listKey).isJsonArray() ? b.getAsJsonArray(listKey) : new JsonArray();
        for (JsonElement el : theirs) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has(idKey)) {
                conflicts.add("Eintrag ohne '" + idKey + "'");
                continue;
            }
            JsonObject entry = el.getAsJsonObject();
            JsonObject same = null;
            for (JsonElement m : mine) {
                if (m.isJsonObject() && sameId(m.getAsJsonObject().get(idKey), entry.get(idKey))) {
                    same = m.getAsJsonObject();
                    break;
                }
            }
            if (same == null) mine.add(entry);
            else if (!same.equals(entry)) conflicts.add("Fall " + entry.get(idKey) + " ist in beiden Packs mit verschiedenem Modell belegt");
        }
        if (b.has("fallback")) {
            if (!a.has("fallback")) a.add("fallback", b.get("fallback"));
            else if (!a.get("fallback").equals(b.get("fallback"))) conflicts.add("beide Packs haben verschiedene 'fallback'-Modelle");
        }
        if (conflicts.isEmpty()) a.add(listKey, mine);
    }

    private static boolean sameId(JsonElement x, JsonElement y) {
        if (x == null || y == null) return false;
        if (x.isJsonPrimitive() && y.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber() && y.getAsJsonPrimitive().isNumber()) {
            return x.getAsDouble() == y.getAsDouble();
        }
        return x.equals(y);
    }
}
