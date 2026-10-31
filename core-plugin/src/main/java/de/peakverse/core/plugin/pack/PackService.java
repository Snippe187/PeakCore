package de.peakverse.core.plugin.pack;

import de.peakverse.core.api.module.PeakModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Sammelt die Pack-Beitraege der laufenden Module, baut daraus die Varianten "standard" und "lite" und legt sie unter
 * {@code <Ordner>/pack-<sha1>.zip} ab. Dazu gehoert die Buchfuehrung: welches Pack gerade <b>live</b> ist, welches im <b>Testmodus</b> nur die
 * Tester bekommen, der Verlauf der Versionen zum <b>Zurueckrollen</b> und die Variante je Spieler (siehe {@link PackState}).
 *
 * <p>Regel fuer die Zeiger: Sie bewegen sich nur, wenn ein Bau anderen Inhalt ergibt als der vorige Bau der Variante (oder es noch kein Live-Pack
 * gibt). Ein Neustart oder ein erneutes {@code rebuild} ohne Aenderung macht ein Zurueckrollen also nicht rueckgaengig.
 * Ein Modul, dessen Beitrag fehlschlaegt oder mit einem anderen kollidiert, fehlt ganz im Pack, die uebrigen bleiben.
 */
public final class PackService {
    /** Wie viele Pack-Dateien (ausser den von Zeigern benutzten) im Ordner bleiben. */
    static final int KEEP = 5;
    private static final int PNG_CACHE_MAX = 5000;

    /** Ein gebautes Pack einer Variante. */
    public record VariantBuild(String variant, String sha1, int size, int fileCount, boolean written) {}

    /**
     * Ergebnis eines Baus. {@code problems}: ausgelassene Module oder Dateien mit Grund; {@code baseFiles}: Dateien aus dem Basis-Pack;
     * {@code mergedFiles}: aus mehreren Quellen zusammengefuehrte Dateien; {@code pngSavedBytes}: durch PNG-Optimierung gespart;
     * {@code pointersChanged}: ein Live- oder Test-Pack hat sich geaendert (dann muessen Spieler neu beliefert werden).
     */
    public record Status(Instant builtAt, Map<String, VariantBuild> variants, Map<String, Integer> filesByModule, List<String> problems, int baseFiles,
            int mergedFiles, long pngSavedBytes, boolean pointersChanged) {
        private VariantBuild standard() {
            return variants.get(PackState.STANDARD);
        }

        public String sha1() {
            return standard().sha1();
        }

        public int size() {
            return standard().size();
        }

        public int fileCount() {
            return standard().fileCount();
        }

        public boolean written() {
            return variants.values().stream().anyMatch(VariantBuild::written);
        }
    }

    private final Supplier<List<PeakModule>> runningModules;
    private final Path folder;
    private final Supplier<PackMeta> meta;
    private final Supplier<Path> baseFile;
    private final Logger log;
    private Supplier<String> defaultVariant = () -> PackState.STANDARD;
    private Supplier<Boolean> optimizePng = () -> true;
    private final Map<String, byte[]> pngCache = new ConcurrentHashMap<>();
    private PackState state;
    private volatile Status last;
    private volatile PackZip.Built lastBuilt;

    /**
     * @param runningModules die Module, die gerade laufen (nur deren Beitraege zaehlen)
     * @param meta           aktuelle Pack-Beschreibung und Format (aus der Config, bei jedem Bau neu gelesen)
     * @param baseFile       das Basis-Pack (ZIP) oder {@code null}, wenn es keins gibt; die Datei wird bei jedem Bau neu gelesen
     */
    public PackService(Supplier<List<PeakModule>> runningModules, Path folder, Supplier<PackMeta> meta, Supplier<Path> baseFile, Logger log) {
        this.runningModules = runningModules;
        this.folder = folder;
        this.meta = meta;
        this.baseFile = baseFile;
        this.log = log;
    }

    public PackService(Supplier<List<PeakModule>> runningModules, Path folder, Supplier<PackMeta> meta, Logger log) {
        this(runningModules, folder, meta, () -> null, log);
    }

    /** Variante, die Spieler ohne eigene Einstellung bekommen (aus der Config). */
    public void setDefaultVariant(Supplier<String> defaultVariant) {
        this.defaultVariant = defaultVariant;
    }

    /** Ob PNG-Dateien verlustfrei verkleinert werden (aus der Config). */
    public void setOptimizePng(Supplier<Boolean> optimizePng) {
        this.optimizePng = optimizePng;
    }

    private Path stateFile() {
        return folder.resolve("state.json");
    }

    private PackState state() {
        if (state == null) state = PackState.load(stateFile());
        return state;
    }

    private void save() throws IOException {
        state().save(stateFile());
    }

    // ---- Bauen ----

    /** Baut beide Varianten neu und bewegt die Zeiger (siehe Klassenbeschreibung). @throws IOException wenn das Basis-Pack nicht lesbar ist oder Dateien nicht geschrieben werden koennen */
    public synchronized Status rebuild() throws IOException {
        Map<String, byte[]> base = Map.of();
        byte[] icon = null;
        List<String> problems = new ArrayList<>();
        Path baseZip = baseFile.get();
        if (baseZip != null && Files.isRegularFile(baseZip)) {
            PackBase.Loaded loaded = PackBase.load(baseZip);
            base = loaded.files();
            icon = loaded.icon();
            for (String s : loaded.skipped()) problems.add("Basis-Pack: " + s + " nicht uebernommen");
        }

        List<PackMerger.Contribution> contributions = new ArrayList<>();
        for (PeakModule module : runningModules.get()) {
            PackAssembler mine = new PackAssembler();
            try {
                module.contributePack(mine.sinkFor(module.id()));
            } catch (Throwable t) {
                problems.add(module.id() + ": " + t.getMessage());
                log.log(Level.WARNING, "Pack-Beitrag von " + module.displayName() + " abgelehnt, das Modul fehlt im Pack", t);
                continue;
            }
            contributions.add(new PackMerger.Contribution(module.id(), mine.files(), mine.optionalPaths()));
        }

        PackState st = state();
        Map<String, VariantBuild> variants = new LinkedHashMap<>();
        PackMerger.Result standardResult = null;
        long[] saved = {0};
        boolean changed = false;
        String now = Instant.now().toString();
        for (String variant : PackState.VARIANTS) {
            PackMerger.Result merged = PackMerger.merge(base, contributions, variant.equals(PackState.STANDARD));
            if (variant.equals(PackState.STANDARD)) standardResult = merged;
            Map<String, byte[]> files = optimizePng.get() ? optimized(merged.files(), saved, variant.equals(PackState.STANDARD)) : merged.files();
            PackZip.Built built = PackZip.build(files, meta.get(), icon);
            boolean wrote = store(built);
            if (variant.equals(PackState.STANDARD)) lastBuilt = built;
            variants.put(variant, new VariantBuild(variant, built.sha1(), built.zip().length, built.fileCount(), wrote));

            PackState.Entry latest = st.latest(variant);
            boolean isNew = latest == null || !latest.sha1.equals(built.sha1());
            if (isNew) st.add(variant, built.sha1(), now, built.zip().length);
            String live = st.live.get(variant);
            if (live == null) {
                st.live.put(variant, built.sha1());
                st.test.remove(variant);
                changed = true;
            } else if (isNew) {
                if (st.testMode) {
                    changed |= !built.sha1().equals(st.test.put(variant, built.sha1()));
                } else {
                    st.live.put(variant, built.sha1());
                    st.test.remove(variant);
                    changed = true;
                }
            }
        }
        for (PackMerger.Problem p : standardResult.problems()) {
            problems.add(p.text());
            log.warning("Pack: " + p.text());
        }
        save();
        prune();
        last = new Status(Instant.now(), variants, standardResult.filesByOwner(), List.copyOf(problems), base.size(), standardResult.mergedFiles(), saved[0], changed);
        return last;
    }

    /** Verkleinert die PNG-Dateien (mit Zwischenspeicher: gleiche Datei wird nur einmal bearbeitet). Die Ersparnis zaehlt nur einmal je Bau. */
    private Map<String, byte[]> optimized(Map<String, byte[]> files, long[] saved, boolean count) {
        Map<String, byte[]> out = new TreeMap<>(files);
        for (Map.Entry<String, byte[]> e : out.entrySet()) {
            if (!e.getKey().endsWith(".png")) continue;
            String key = PackZip.sha1(e.getValue());
            byte[] better = pngCache.get(key);
            if (better == null) {
                better = PngOptimizer.optimize(e.getValue());
                if (pngCache.size() >= PNG_CACHE_MAX) pngCache.clear();
                pngCache.put(key, better);
            }
            if (count) saved[0] += e.getValue().length - better.length;
            e.setValue(better);
        }
        return out;
    }

    /** Schreibt das Pack, falls es noch nicht (vollstaendig) da ist. @return ob geschrieben wurde */
    private boolean store(PackZip.Built built) throws IOException {
        Path file = folder.resolve("pack-" + built.sha1() + ".zip");
        boolean write = !Files.exists(file) || Files.size(file) != built.zip().length;
        if (write) {
            Files.createDirectories(folder);
            Path tmp = folder.resolve("pack-" + built.sha1() + ".tmp");
            Files.write(tmp, built.zip());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } else {
            file.toFile().setLastModified(System.currentTimeMillis()); // wieder gebrauchtes Pack zaehlt als neu, damit prune es nicht zuerst loescht
        }
        return write;
    }

    /** Loescht alte Packs: bleiben die von Zeigern benutzten und die {@link #KEEP} juengsten (die Zeiger-Packs zaehlen mit). */
    private void prune() throws IOException {
        PackState st = state();
        Set<String> pinned = new HashSet<>(st.live.values());
        pinned.addAll(st.test.values());
        List<Path> packs;
        try (Stream<Path> s = Files.list(folder)) {
            packs = s.filter(p -> p.getFileName().toString().matches("pack-[0-9a-f]{40}\\.zip")).toList();
        }
        List<Path> others = packs.stream().filter(p -> !pinned.contains(shaOf(p)))
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
        int room = Math.max(0, KEEP - pinned.size());
        for (int i = room; i < others.size(); i++) Files.deleteIfExists(others.get(i));
    }

    private static String shaOf(Path p) {
        String n = p.getFileName().toString();
        return n.substring(5, n.length() - 4);
    }

    // ---- Abfragen ----

    /** Das Ergebnis des letzten Baus oder {@code null}. */
    public Status status() {
        return last;
    }

    /** Die Bytes des zuletzt gebauten Standard-Packs oder {@code null}. */
    public PackZip.Built built() {
        return lastBuilt;
    }

    public synchronized boolean testMode() {
        return state().testMode;
    }

    public synchronized String liveSha(String variant) {
        return state().live.get(variant);
    }

    public synchronized String testSha(String variant) {
        return state().test.get(variant);
    }

    /** Die Variante eines Spielers: seine eigene Einstellung, sonst die Standard-Variante aus der Config. */
    public synchronized String variantOf(UUID player) {
        String own = state().playerVariant.get(player.toString());
        if (own != null && PackState.VARIANTS.contains(own)) return own;
        String def = defaultVariant.get();
        return PackState.VARIANTS.contains(def) ? def : PackState.STANDARD;
    }

    /** Welches Pack ein Spieler bekommen soll: Tester im Testmodus das Test-Pack (falls es eins gibt), alle anderen das Live-Pack. {@code null}: noch nichts gebaut. */
    public synchronized String shaFor(UUID player) {
        PackState st = state();
        String variant = variantOf(player);
        if (st.testMode && st.testers.contains(player.toString())) {
            String t = st.test.get(variant);
            if (t != null) return t;
        }
        return st.live.get(variant);
    }

    /** Die Versionen einer Variante, neueste zuerst. */
    public synchronized List<PackState.Entry> versions(String variant) {
        List<PackState.Entry> list = new ArrayList<>();
        for (PackState.Entry e : state().history) if (e.variant.equals(variant)) list.add(e);
        java.util.Collections.reverse(list);
        return list;
    }

    // ---- Verwalten ----

    /** Setzt das Live-Pack einer Variante auf eine fruehere Version. @throws IllegalArgumentException wenn es die Nummer nicht gibt oder die Datei fehlt */
    public synchronized PackState.Entry rollback(String variant, int number) throws IOException {
        PackState st = state();
        PackState.Entry e = st.byNumber(number);
        if (e == null || !e.variant.equals(variant)) throw new IllegalArgumentException("Version " + number + " gibt es fuer '" + variant + "' nicht (siehe /peakcore pack versions)");
        if (!Files.isRegularFile(folder.resolve("pack-" + e.sha1 + ".zip"))) throw new IllegalArgumentException("Die Datei dieser Version ist nicht mehr da (alte Packs werden aufgeraeumt)");
        st.live.put(variant, e.sha1);
        st.test.remove(variant);
        save();
        return e;
    }

    /** Testmodus ein- oder ausschalten. Aus: die Test-Packs werden verworfen, alle bekommen wieder das Live-Pack. */
    public synchronized void setTestMode(boolean on) throws IOException {
        PackState st = state();
        st.testMode = on;
        if (!on) st.test.clear();
        save();
    }

    /** Macht die Test-Packs zu Live-Packs. @return die Zahl der Varianten, die sich geaendert haben */
    public synchronized int publish() throws IOException {
        PackState st = state();
        int n = 0;
        for (Map.Entry<String, String> e : new TreeMap<>(st.test).entrySet()) {
            if (!e.getValue().equals(st.live.put(e.getKey(), e.getValue()))) n++;
        }
        st.test.clear();
        save();
        return n;
    }

    public synchronized boolean addTester(UUID player) throws IOException {
        boolean added = state().testers.add(player.toString());
        save();
        return added;
    }

    public synchronized boolean removeTester(UUID player) throws IOException {
        boolean removed = state().testers.remove(player.toString());
        save();
        return removed;
    }

    public synchronized Set<String> testers() {
        return Set.copyOf(state().testers);
    }

    /** Variante eines Spielers festlegen; {@code null} = zurueck zur Standard-Einstellung der Config. */
    public synchronized void setPlayerVariant(UUID player, String variant) throws IOException {
        if (variant != null && !PackState.VARIANTS.contains(variant)) throw new IllegalArgumentException("Unbekannte Variante '" + variant + "' (erlaubt: " + PackState.VARIANTS + ")");
        if (variant == null) state().playerVariant.remove(player.toString());
        else state().playerVariant.put(player.toString(), variant);
        save();
    }
}
