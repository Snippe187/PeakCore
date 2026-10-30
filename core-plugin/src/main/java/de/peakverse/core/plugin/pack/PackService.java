package de.peakverse.core.plugin.pack;

import de.peakverse.core.api.module.PeakModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Sammelt die Pack-Beitraege der laufenden Module, baut daraus das Pack und legt es unter {@code <Ordner>/pack-<sha1>.zip} ab.
 * Aendert sich nichts, kommt derselbe Hash heraus und es wird nichts neu geschrieben. Ein Modul, dessen Beitrag fehlschlaegt oder mit einem
 * anderen kollidiert, wird fuer diesen Bau ausgelassen (ganz, nicht halb); die uebrigen Module bleiben im Pack.
 */
public final class PackService {
    /** Wie viele aeltere Packs im Ordner bleiben (fuers Zurueckrollen, siehe Phase 12). */
    static final int KEEP = 5;

    /** Ergebnis eines Baus. {@code problems}: ausgelassene Module mit Grund. */
    public record Status(String sha1, int size, int fileCount, Instant builtAt, boolean written, Map<String, Integer> filesByModule, List<String> problems) {}

    private final Supplier<List<PeakModule>> runningModules;
    private final Path folder;
    private final Supplier<PackMeta> meta;
    private final Logger log;
    private volatile Status last;
    private volatile PackZip.Built lastBuilt;

    /**
     * @param runningModules die Module, die gerade laufen (nur deren Beitraege zaehlen)
     * @param meta           aktuelle Pack-Beschreibung und Format (aus der Config, bei jedem Bau neu gelesen)
     */
    public PackService(Supplier<List<PeakModule>> runningModules, Path folder, Supplier<PackMeta> meta, Logger log) {
        this.runningModules = runningModules;
        this.folder = folder;
        this.meta = meta;
        this.log = log;
    }

    /** Baut das Pack neu. @throws IOException wenn die Datei nicht geschrieben werden kann */
    public synchronized Status rebuild() throws IOException {
        PackAssembler all = new PackAssembler();
        List<String> problems = new ArrayList<>();
        for (PeakModule module : runningModules.get()) {
            PackAssembler mine = new PackAssembler();
            try {
                module.contributePack(mine.sinkFor(module.id()));
            } catch (Throwable t) {
                problems.add(module.id() + ": " + t.getMessage());
                log.log(Level.WARNING, "Pack-Beitrag von " + module.displayName() + " abgelehnt, das Modul fehlt im Pack", t);
                continue;
            }
            String clash = null;
            for (String path : mine.files().keySet()) {
                String other = all.ownerOf(path);
                if (other != null) {
                    clash = "Pfad " + path + " ist schon von " + other + " belegt";
                    break;
                }
            }
            if (clash != null) {
                problems.add(module.id() + ": " + clash);
                log.warning("Pack-Beitrag von " + module.displayName() + " ausgelassen: " + clash);
                continue;
            }
            var sink = all.sinkFor(module.id());
            mine.files().forEach(sink::file);
        }

        PackZip.Built built = PackZip.build(all.files(), meta.get(), null);
        Path file = folder.resolve("pack-" + built.sha1() + ".zip");
        boolean write = !Files.exists(file) || Files.size(file) != built.zip().length;
        if (write) {
            Files.createDirectories(folder);
            Path tmp = folder.resolve("pack-" + built.sha1() + ".tmp");
            Files.write(tmp, built.zip());
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        else file.toFile().setLastModified(System.currentTimeMillis()); // wieder gebrauchtes Pack zaehlt als neu, damit prune es nicht zuerst loescht
        prune(file);
        lastBuilt = built;
        last = new Status(built.sha1(), built.zip().length, built.fileCount(), Instant.now(), write, all.countsByOwner(), List.copyOf(problems));
        return last;
    }

    /** Das Ergebnis des letzten Baus oder {@code null}. */
    public Status status() {
        return last;
    }

    /** Die Bytes des zuletzt gebauten Packs oder {@code null}. */
    public PackZip.Built built() {
        return lastBuilt;
    }

    /** Loescht die aeltesten Packs, sodass hoechstens {@link #KEEP} im Ordner bleiben; das aktuelle bleibt immer. */
    private void prune(Path keep) throws IOException {
        List<Path> packs;
        try (Stream<Path> s = Files.list(folder)) {
            packs = s.filter(p -> p.getFileName().toString().matches("pack-[0-9a-f]{40}\\.zip")).toList();
        }
        List<Path> old = packs.stream().filter(p -> !p.equals(keep))
                .sorted(java.util.Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
        for (int i = KEEP - 1; i < old.size(); i++) Files.deleteIfExists(old.get(i));
    }
}
