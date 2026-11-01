package de.peakverse.core.client.update;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Update der PeakVerse-Mods (Fabric) aus einem GitHub-Release-Repo. Minecraft-frei und mit austauschbarer Basis-Adresse testbar.
 * <p>
 * Ablauf: {@link #check} findet im neuesten Release die Dateien {@code <Name>-Mod-<Version>.jar} (Mod-ID = Name in Kleinbuchstaben), {@link #stage} laedt
 * eine davon in einen Zwischenordner und prueft die SHA-256-Datei {@code <Jar>.sha256}. Eine laufende Mod kann nicht ersetzt werden (das Jar ist
 * gesperrt); darum schreibt {@link #swapScript} ein kleines Skript, das nach dem Beenden des Spiels das alte Jar gegen das neue tauscht.
 * Downloads sind nur von GitHub (oder einer lokalen Test-Adresse) erlaubt.
 */
public final class ModUpdater {
    public static final long MAX_JAR_BYTES = 150L << 20;
    private static final Pattern ASSET = Pattern.compile("([A-Za-z][A-Za-z0-9]{1,30})-Mod-([0-9][0-9A-Za-z.+_-]*)\\.jar");
    private static final Gson GSON = new Gson();

    /** Eine Mod-Datei in einem Release. */
    public record Asset(String modId, String version, String fileName, String url, String shaUrl) { }

    /** Ein verfuegbares Update fuer eine installierte Mod. */
    public record Available(String modId, String installed, Asset asset, String page) { }

    private final String apiBase;
    private final String repo;
    private final boolean allowLocal;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    /**
     * @param apiBase z. B. {@code https://api.github.com}
     * @param repo    {@code besitzer/name}
     */
    public ModUpdater(String apiBase, String repo) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.repo = repo;
        String host = URI.create(this.apiBase).getHost();
        this.allowLocal = "127.0.0.1".equals(host) || "localhost".equals(host);
        if (!repo.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("Ungueltiges Repo: " + repo);
    }

    /**
     * Prueft das neueste Release gegen die installierten Mods.
     *
     * @param installed Mod-ID -> installierte Version (nur diese Mods werden beachtet)
     * @return fuer jede Mod mit neuerer Datei im Release ein Eintrag; leer, wenn alles aktuell ist
     */
    public List<Available> check(Map<String, String> installed) throws IOException, InterruptedException {
        JsonObject o = GSON.fromJson(getString(apiBase + "/repos/" + repo + "/releases/latest"), JsonObject.class);
        if (o == null || !o.has("tag_name")) throw new IOException("Das Repo hat noch keine Version.");
        String page = o.has("html_url") && !o.get("html_url").isJsonNull() ? o.get("html_url").getAsString() : "https://github.com/" + repo + "/releases";
        Map<String, String> urls = new java.util.HashMap<>();
        List<Asset> found = new ArrayList<>();
        if (o.has("assets") && o.get("assets").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("assets")) {
                JsonObject a = e.getAsJsonObject();
                urls.put(a.get("name").getAsString(), a.get("browser_download_url").getAsString());
            }
            for (Map.Entry<String, String> e : urls.entrySet()) {
                Matcher m = ASSET.matcher(e.getKey());
                if (m.matches()) found.add(new Asset(m.group(1).toLowerCase(), m.group(2), e.getKey(), e.getValue(), urls.get(e.getKey() + ".sha256")));
            }
        }
        List<Available> result = new ArrayList<>();
        for (Asset a : found) {
            String have = installed.get(a.modId());
            if (have != null && compare(a.version(), have) > 0) result.add(new Available(a.modId(), have, a, page));
        }
        result.sort(java.util.Comparator.comparing(Available::modId));
        return result;
    }

    /** Laedt die Datei nach {@code stageDir}, prueft die Pruefsumme und legt sie erst danach unter ihrem Namen ab. */
    public Path stage(Asset asset, Path stageDir) throws IOException, InterruptedException {
        if (asset.shaUrl() == null) throw new IOException("Das Release hat keine Pruefsumme (" + asset.fileName() + ".sha256). Aus Sicherheitsgruenden wird nichts installiert.");
        if (!ASSET.matcher(asset.fileName()).matches()) throw new IOException("Ungueltiger Dateiname.");
        String expected = getString(asset.shaUrl()).strip().split("\\s+")[0].toLowerCase();
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Die Pruefsummen-Datei ist ungueltig.");
        Files.createDirectories(stageDir);
        Path target = stageDir.resolve(asset.fileName());
        Path tmp = stageDir.resolve(asset.fileName() + ".part");
        try {
            HttpResponse<InputStream> res = http.send(request(asset.url(), 300), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("Download: HTTP " + res.statusCode());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = res.body(); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_JAR_BYTES) throw new IOException("Die Datei ist unerwartet gross.");
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IOException("Die Pruefsumme stimmt nicht: die Datei wird nicht installiert.");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Ein Tausch: das neue Jar (im Zwischenordner) ersetzt das alte (im mods-Ordner). */
    public record Swap(Path oldJar, Path newJar) { }

    /**
     * Skript, das wartet, bis der Prozess {@code pid} beendet ist, und dann jedes alte Jar loescht und das neue in denselben Ordner verschiebt.
     * Windows: PowerShell-Skript ({@code .ps1}), sonst Shell-Skript. Es fasst nur die uebergebenen Pfade an.
     */
    public static String swapScript(long pid, List<Swap> swaps, boolean windows) {
        StringBuilder s = new StringBuilder();
        if (windows) {
            s.append("$ErrorActionPreference = 'SilentlyContinue'\r\n");
            s.append("Wait-Process -Id ").append(pid).append(" -Timeout 180\r\n");
            for (Swap w : swaps) {
                s.append("Remove-Item -LiteralPath ").append(psq(w.oldJar())).append(" -Force\r\n");
                s.append("Move-Item -LiteralPath ").append(psq(w.newJar())).append(" -Destination ").append(psq(w.oldJar().resolveSibling(w.newJar().getFileName())))
                        .append(" -Force\r\n");
            }
        } else {
            s.append("#!/bin/sh\n");
            s.append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n");
            for (Swap w : swaps) {
                s.append("rm -f ").append(shq(w.oldJar())).append("\n");
                s.append("mv -f ").append(shq(w.newJar())).append(" ").append(shq(w.oldJar().resolveSibling(w.newJar().getFileName()))).append("\n");
            }
        }
        return s.toString();
    }

    private static String psq(Path p) {
        return "'" + p.toString().replace("'", "''") + "'";
    }

    private static String shq(Path p) {
        return "'" + p.toString().replace("'", "'\\''") + "'";
    }

    /** Schreibt das Tausch-Skript in {@code dir} und gibt seinen Pfad zurueck (ausfuehren muss es der Aufrufer). */
    public static Path writeSwapScript(Path dir, long pid, List<Swap> swaps, boolean windows) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(windows ? "swap.ps1" : "swap.sh");
        Files.writeString(f, swapScript(pid, swaps, windows), StandardCharsets.UTF_8);
        return f;
    }

    private boolean allowedHost(URI u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (allowLocal && ("127.0.0.1".equals(host) || "localhost".equals(host))) return true;
        return "https".equals(u.getScheme()) && (host.equals("api.github.com") || host.equals("github.com") || host.endsWith(".githubusercontent.com"));
    }

    private HttpRequest request(String url, int seconds) throws IOException {
        URI u = URI.create(url);
        if (!allowedHost(u)) throw new IOException("Unerlaubte Adresse: " + u.getHost());
        return HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(seconds)).header("User-Agent", "PeakVerse-ModUpdater").header("Accept", "application/vnd.github+json").build();
    }

    private String getString(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(request(url, 30), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) throw new IOException("Nicht gefunden (Repo '" + repo + "' oeffentlich und mit Release?).");
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        return res.body();
    }

    /** Vergleicht Versionen wie {@code 0.1.0} und {@code 0.1.10}; Zusaetze wie {@code -beta} zaehlen als aelter als dieselbe Zahl ohne Zusatz. */
    public static int compare(String a, String b) {
        String[] pa = a.split("-", 2), pb = b.split("-", 2);
        String[] na = pa[0].split("\\."), nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            long x = i < na.length ? num(na[i]) : 0, y = i < nb.length ? num(nb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        if (pa.length == pb.length) return pa.length == 1 ? 0 : pa[1].compareTo(pb[1]);
        return pa.length == 1 ? 1 : -1;
    }

    private static long num(String s) {
        String d = s.replaceAll("[^0-9].*$", "");
        return d.isEmpty() ? 0 : Long.parseLong(d.length() > 9 ? d.substring(0, 9) : d);
    }
}
