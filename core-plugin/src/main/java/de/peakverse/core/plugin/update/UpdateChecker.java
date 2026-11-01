package de.peakverse.core.plugin.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Fragt ein GitHub-Release-Repo nach der neuesten Version und laedt das Jar herunter. Bukkit-frei und mit austauschbarer Basis-Adresse testbar.
 * Ein Update wird nur uebernommen, wenn die SHA-256-Pruefsumme aus der Datei {@code <Jar>.sha256} im Release stimmt; Downloads sind nur
 * von GitHub (oder einer lokalen Test-Adresse) erlaubt.
 */
public final class UpdateChecker {
    public static final long MAX_JAR_BYTES = 150L << 20;
    private static final Pattern JAR = Pattern.compile("PeakCore-[0-9][0-9A-Za-z.+_-]*\\.jar");
    private static final Gson GSON = new Gson();

    /** Eine veroeffentlichte Version. */
    public record Release(String version, String name, String notes, String jarName, String jarUrl, String shaUrl, long size, String page) { }

    private final String apiBase;
    private final String repo;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private final boolean allowLocal;

    /**
     * @param apiBase z. B. {@code https://api.github.com}
     * @param repo    {@code besitzer/name}
     */
    public UpdateChecker(String apiBase, String repo) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.repo = repo;
        URI u = URI.create(this.apiBase);
        this.allowLocal = "127.0.0.1".equals(u.getHost()) || "localhost".equals(u.getHost());
        if (!repo.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("Ungueltiges Repo: " + repo);
    }

    /** Neueste Version aus dem Repo. */
    public Release latest() throws IOException, InterruptedException {
        String json = getString(apiBase + "/repos/" + repo + "/releases/latest");
        JsonObject o = GSON.fromJson(json, JsonObject.class);
        if (o == null || !o.has("tag_name")) throw new IOException("Das Repo hat noch keine Version.");
        String tag = o.get("tag_name").getAsString();
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String jarName = null, jarUrl = null, shaUrl = null;
        long size = 0;
        JsonArray assets = o.has("assets") && o.get("assets").isJsonArray() ? o.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            String name = a.get("name").getAsString();
            if (JAR.matcher(name).matches()) {
                jarName = name;
                jarUrl = a.get("browser_download_url").getAsString();
                size = a.has("size") ? a.get("size").getAsLong() : 0;
            }
        }
        if (jarName == null) throw new IOException("Im Release " + tag + " liegt kein PeakCore-<Version>.jar.");
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            if (a.get("name").getAsString().equals(jarName + ".sha256")) shaUrl = a.get("browser_download_url").getAsString();
        }
        return new Release(version, str(o, "name", tag), str(o, "body", ""), jarName, jarUrl, shaUrl, size,
                str(o, "html_url", "https://github.com/" + repo + "/releases"));
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    /** Laedt das Jar nach {@code target}, prueft die Pruefsumme und ersetzt die Zieldatei erst danach. */
    public void download(Release release, Path target) throws IOException, InterruptedException {
        if (release.shaUrl() == null) throw new IOException("Das Release hat keine Pruefsumme (" + release.jarName() + ".sha256). Aus Sicherheitsgruenden wird nichts installiert.");
        String expected = getString(release.shaUrl()).strip().split("\\s+")[0].toLowerCase();
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Die Pruefsummen-Datei ist ungueltig.");
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> res = http.send(request(release.jarUrl(), 300), HttpResponse.BodyHandlers.ofInputStream());
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
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private boolean allowedHost(URI u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (allowLocal && ("127.0.0.1".equals(host) || "localhost".equals(host))) return true;
        return "https".equals(u.getScheme()) && (host.equals("api.github.com") || host.equals("github.com") || host.endsWith(".githubusercontent.com"));
    }

    private HttpRequest request(String url, int seconds) throws IOException {
        URI u = URI.create(url);
        if (!allowedHost(u)) throw new IOException("Unerlaubte Adresse: " + u.getHost());
        return HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(seconds)).header("User-Agent", "PeakVerse-Updater").header("Accept", "application/vnd.github+json").build();
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
