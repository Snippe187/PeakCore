package de.peakverse.core.plugin.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Setzt aus dem Basis-Pack und den Beitraegen der Module ein Pack zusammen.
 * <ul>
 *   <li>Reihenfolge: Basis-Pack, dann die Module nach ID sortiert. Damit ist das Ergebnis unabhaengig von der Lade-Reihenfolge.</li>
 *   <li>Dieselbe Datei mit gleichem Inhalt: kein Konflikt. Zusammenfuehrbare JSON-Dateien (siehe {@link JsonMerge}) werden vereinigt.</li>
 *   <li>Konflikt mit dem <b>Basis-Pack</b>: die Datei des Moduls gewinnt (damit seine Gegenstaende funktionieren), im Problem-Text steht, wie viele Dateien das waren.</li>
 *   <li>Konflikt mit einem <b>anderen Modul</b>: das spaetere Modul entfaellt ganz (alles oder nichts), damit kein Modul halb im Pack steckt.</li>
 * </ul>
 */
public final class PackMerger {
    public static final String BASE = "base";

    /** Dateien eines Moduls; {@code optional}: die davon, die die Variante "lite" weglaesst. */
    public record Contribution(String owner, Map<String, byte[]> files, java.util.Set<String> optional) {
        public Contribution(String owner, Map<String, byte[]> files) {
            this(owner, files, java.util.Set.of());
        }
    }

    /**
     * @param owner   wer das Problem hat
     * @param whole   {@code true}: das ganze Modul fehlt im Pack; {@code false}: nur diese Datei
     */
    public record Problem(String owner, String path, String detail, boolean whole) {
        public String text() {
            return owner + ": " + (whole ? "fehlt ganz im Pack - " : "Datei ausgelassen - ") + path + ": " + detail;
        }
    }

    /** @param filesByOwner je Modul die Zahl der Dateien, die im Pack gelandet sind (zusammengefuehrte zaehlen mit) */
    public record Result(Map<String, byte[]> files, Map<String, Integer> filesByOwner, List<Problem> problems, int mergedFiles) {}

    private PackMerger() {}

    public static Result merge(Map<String, byte[]> base, List<Contribution> contributions) {
        return merge(base, contributions, true);
    }

    /** @param includeOptional {@code false}: optionale Dateien der Module weglassen (Variante "lite") */
    public static Result merge(Map<String, byte[]> base, List<Contribution> contributions, boolean includeOptional) {
        Map<String, byte[]> files = new TreeMap<>(base);
        Map<String, Set<String>> owners = new TreeMap<>();
        for (String path : files.keySet()) owners.put(path, new HashSet<>(Set.of(BASE)));
        Map<String, Integer> counts = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();
        int merged = 0;

        List<Contribution> sorted = contributions.stream().sorted(Comparator.comparing(Contribution::owner)).toList();
        for (Contribution c : sorted) {
            Map<String, byte[]> work = new TreeMap<>(files);
            Map<String, Set<String>> workOwners = new TreeMap<>();
            owners.forEach((k, v) -> workOwners.put(k, new HashSet<>(v)));
            List<Problem> mine = new ArrayList<>();
            int accepted = 0;
            int mergedHere = 0;
            Problem fatal = null;
            List<String> overridden = new ArrayList<>();

            for (Map.Entry<String, byte[]> e : new TreeMap<>(c.files()).entrySet()) {
                String path = e.getKey();
                byte[] data = e.getValue();
                if (!includeOptional && c.optional().contains(path)) continue;
                byte[] existing = work.get(path);
                if (existing == null) {
                    work.put(path, data);
                    workOwners.computeIfAbsent(path, k -> new HashSet<>()).add(c.owner());
                    accepted++;
                    continue;
                }
                Set<String> who = workOwners.get(path);
                if (Arrays.equals(existing, data)) {
                    who.add(c.owner());
                    accepted++;
                    continue;
                }
                JsonMerge.Result r = JsonMerge.mergeable(path) ? JsonMerge.merge(path, existing, data)
                        : new JsonMerge.Result(null, List.of("Pfad ist schon belegt (" + describe(who) + ") und kann nicht zusammengefuehrt werden"));
                if (r.ok()) {
                    work.put(path, r.merged());
                    who.add(c.owner());
                    accepted++;
                    mergedHere++;
                } else if (who.equals(Set.of(BASE))) {
                    // Die Textur des Moduls gewinnt, damit seine Gegenstaende funktionieren; die Datei des Basis-Packs wird fuer diesen Pfad ersetzt
                    work.put(path, data);
                    workOwners.put(path, new HashSet<>(Set.of(c.owner())));
                    accepted++;
                    overridden.add(path);
                } else {
                    fatal = new Problem(c.owner(), path, String.join("; ", r.conflicts()) + " [" + describe(who) + "]", true);
                    break;
                }
            }

            if (fatal != null) {
                problems.add(fatal);
                continue; // work verwerfen: das Modul fehlt ganz
            }
            files = work;
            owners = workOwners;
            if (!overridden.isEmpty()) {
                mine.add(new Problem(c.owner(), overridden.get(0), "ersetzt " + overridden.size() + " Datei(en) aus dem Basis-Pack (Textur des Moduls gewinnt)"
                        + (overridden.size() > 1 ? ", z. B. " + overridden.get(1) : ""), false));
            }
            problems.addAll(mine);
            merged += mergedHere;
            if (accepted > 0) counts.put(c.owner(), accepted);
        }
        return new Result(files, counts, List.copyOf(problems), merged);
    }

    private static String describe(Set<String> who) {
        return String.join(", ", new java.util.TreeSet<>(who));
    }
}
