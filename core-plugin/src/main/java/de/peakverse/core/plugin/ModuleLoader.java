package de.peakverse.core.plugin;

import de.peakverse.core.api.module.ModuleContext;
import de.peakverse.core.api.module.PeakModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Startet und beendet die eingebauten Module. Ein Modul, das uebersprungen wird oder beim Start scheitert, hindert weder den Core
 * noch die anderen Module am Start. Ohne Bukkit-Abhaengigkeit, damit es sich testen laesst.
 */
final class ModuleLoader {
    enum State { ENABLED, SKIPPED, FAILED }

    /** {@code detail}: bei SKIPPED der Grund, bei FAILED die Fehlermeldung, sonst {@code null}. */
    record Result(PeakModule module, State state, String detail) {}

    private final List<PeakModule> modules;
    private final Function<PeakModule, String> skipReason;
    private final Function<PeakModule, ModuleContext> contexts;
    private final Logger log;
    private final List<Result> results = new ArrayList<>();

    /**
     * @param skipReason liefert den Grund, ein Modul nicht zu starten (z. B. "in der config.yml ausgeschaltet"), oder {@code null}
     */
    ModuleLoader(List<PeakModule> modules, Function<PeakModule, String> skipReason, Function<PeakModule, ModuleContext> contexts, Logger log) {
        this.modules = List.copyOf(modules);
        this.skipReason = skipReason;
        this.contexts = contexts;
        this.log = log;
    }

    /** Startet alle nicht uebersprungenen Module in der gefundenen Reihenfolge. */
    List<Result> enableAll() {
        results.clear();
        Set<String> seen = new HashSet<>();
        for (PeakModule module : modules) {
            String id = module.id();
            if (!seen.add(id)) {
                results.add(fail(module, "Modul-ID " + id + " ist doppelt vorhanden"));
                continue;
            }
            String skip = skipReason.apply(module);
            if (skip != null) {
                log.info("Modul " + module.displayName() + " (" + id + ") wird nicht gestartet: " + skip);
                results.add(new Result(module, State.SKIPPED, skip));
                continue;
            }
            try {
                module.enable(contexts.apply(module));
                log.info("Modul " + module.displayName() + " (" + id + ") gestartet.");
                results.add(new Result(module, State.ENABLED, null));
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Modul " + module.displayName() + " (" + id + ") konnte nicht starten und bleibt aus", t);
                results.add(new Result(module, State.FAILED, String.valueOf(t.getMessage())));
                safeDisable(module); // halb gestartete Teile wieder abbauen
            }
        }
        return List.copyOf(results);
    }

    /** Beendet die gestarteten Module in umgekehrter Reihenfolge. Fehler eines Moduls verhindern nicht das Beenden der uebrigen. */
    void disableAll() {
        for (int i = results.size() - 1; i >= 0; i--) {
            Result r = results.get(i);
            if (r.state() == State.ENABLED) safeDisable(r.module());
        }
        results.clear();
    }

    List<Result> results() {
        return List.copyOf(results);
    }

    private Result fail(PeakModule module, String reason) {
        log.severe("Modul " + module.displayName() + ": " + reason);
        return new Result(module, State.FAILED, reason);
    }

    private void safeDisable(PeakModule module) {
        try {
            module.disable();
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Modul " + module.displayName() + " konnte nicht sauber beendet werden", t);
        }
    }
}
