package de.peakverse.core.plugin.pack;

import de.peakverse.core.api.pack.PackPaths;
import de.peakverse.core.api.pack.PackSink;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sammelt die Dateien aller Module fuer das Pack. Jeder Pfad gehoert genau einem Beitrag; ein zweiter Beitrag zum selben Pfad wird abgelehnt
 * (das Zusammenfuehren geteilter Dateien wie Item-Definitionen kommt in Phase 11). {@code pack.mcmeta} und {@code pack.png} gehoeren dem Core.
 */
public final class PackAssembler {
    private static final Set<String> RESERVED = Set.of("pack.mcmeta", "pack.png");

    private final Map<String, byte[]> files = new TreeMap<>();
    private final Map<String, String> owners = new TreeMap<>();

    private final java.util.Set<String> optional = new java.util.TreeSet<>();

    /** Der Sink eines Moduls; {@code owner} erscheint in Fehlermeldungen. */
    public PackSink sinkFor(String owner) {
        return new PackSink() {
            @Override
            public void file(String path, byte[] data) {
                add(owner, path, data);
            }

            @Override
            public void optionalFile(String path, byte[] data) {
                add(owner, path, data);
                optional.add(path);
            }
        };
    }

    private void add(String owner, String path, byte[] data) {
        PackPaths.validate(path);
        if (data == null) throw new IllegalArgumentException("Keine Daten fuer " + path);
        if (RESERVED.contains(path)) throw new IllegalArgumentException(path + " gehoert dem Core, Modul " + owner + " darf sie nicht liefern");
        String existing = owners.get(path);
        if (existing != null) {
            throw new IllegalArgumentException("Pfad " + path + " liefern sowohl " + existing + " als auch " + owner);
        }
        owners.put(path, owner);
        files.put(path, data.clone());
    }

    /** Pfade, die als optional geliefert wurden (fehlen in der Variante "lite"). */
    public java.util.Set<String> optionalPaths() {
        return optional;
    }
    /** Wer den Pfad schon geliefert hat, sonst {@code null}. */
    public String ownerOf(String path) {
        return owners.get(path);
    }

    /** Alle gesammelten Dateien, nach Pfad sortiert. */
    public Map<String, byte[]> files() {
        return files;
    }

    /** Anzahl der Dateien je Beitragsgeber. */
    public Map<String, Integer> countsByOwner() {
        Map<String, Integer> counts = new TreeMap<>();
        for (String owner : owners.values()) counts.merge(owner, 1, Integer::sum);
        return counts;
    }
}
