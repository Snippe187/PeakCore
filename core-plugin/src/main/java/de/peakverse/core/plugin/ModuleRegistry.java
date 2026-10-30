package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.ModuleRegistrationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Verwaltet die angemeldeten Module. Reine Logik ohne Bukkit, damit sie sich testen laesst. Alle Methoden sind threadsicher. */
final class ModuleRegistry {
    /** Ein angemeldetes Modul mit dem Namen des Plugins, das es angemeldet hat. */
    record Entry(ModuleInfo info, String owner) {}

    private final int apiVersion;
    private final Map<String, Entry> byId = new TreeMap<>();

    ModuleRegistry(int apiVersion) {
        this.apiVersion = apiVersion;
    }

    synchronized Entry register(String owner, ModuleInfo info) {
        if (info.requiredApi() > apiVersion) {
            throw new ModuleRegistrationException("Modul " + info.id() + " braucht die Core-API " + info.requiredApi()
                    + ", dieses PeakCore bietet nur " + apiVersion + ". Bitte PeakCore aktualisieren.");
        }
        Entry existing = byId.get(info.id());
        if (existing != null) {
            throw new ModuleRegistrationException("Die Modul-ID " + info.id() + " ist schon von " + existing.owner() + " belegt.");
        }
        Entry entry = new Entry(info, owner);
        byId.put(info.id(), entry);
        return entry;
    }

    /** Meldet ein Modul ab, aber nur, wenn es dem genannten Plugin gehoert. */
    synchronized boolean unregister(String id, String owner) {
        Entry existing = byId.get(id);
        if (existing == null || !existing.owner().equals(owner)) return false;
        byId.remove(id);
        return true;
    }

    /** Meldet alle Module eines Plugins ab (wenn es abgeschaltet wird) und nennt die entfernten. */
    synchronized List<ModuleInfo> unregisterOwner(String owner) {
        List<ModuleInfo> removed = new ArrayList<>();
        byId.values().removeIf(entry -> {
            boolean mine = entry.owner().equals(owner);
            if (mine) removed.add(entry.info());
            return mine;
        });
        return removed;
    }

    synchronized Optional<Entry> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Alle Eintraege, nach ID sortiert. */
    synchronized List<Entry> entries() {
        return List.copyOf(byId.values());
    }
}
