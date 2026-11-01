package de.peakverse.core.plugin.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gekoppelte Geraete (z. B. das Handy): jedes bekommt einen langen Schluessel, mit dem es sich ohne neuen Link anmelden kann.
 * Gespeichert wird nur eine Pruefsumme des Schluessels ({@code devices.json}), nie der Schluessel selbst. Bukkit-frei und mit austauschbarer Uhr testbar.
 */
public final class DeviceStore {
    public static final int MAX_PER_PLAYER = 10;
    private static final long SAVE_EVERY_MS = 10 * 60_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ein gekoppeltes Geraet. {@code hash} ist die SHA-256-Pruefsumme des geheimen Teils. */
    public static final class Device {
        public String id;
        public String player;
        public String name;
        public String hash;
        public String label = "";
        public String lastIp = "";
        public long created;
        public long lastUsed;
    }

    private final Path file;
    private final LongSupplier clock;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();
    private final List<Device> devices = new ArrayList<>();
    private long lastSaved;

    public DeviceStore(Path file, LongSupplier clock, Logger logger) {
        this.file = file;
        this.clock = clock;
        this.logger = logger;
    }

    public synchronized void load() {
        devices.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            List<Device> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Device>>() { }.getType());
            if (list != null) for (Device d : list) if (d != null && d.id != null && d.hash != null && d.player != null) devices.add(d);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "devices.json konnte nicht gelesen werden (Datei bleibt liegen, keine Geraete angemeldet)", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(devices), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            lastSaved = clock.getAsLong();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "devices.json konnte nicht gespeichert werden", e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomPart(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Legt ein Geraet an. @return der Schluessel {@code <id>.<geheimnis>}; nur jetzt sichtbar, gespeichert wird nur die Pruefsumme.
     * Hat der Spieler schon {@link #MAX_PER_PLAYER} Geraete, faellt das aelteste weg.
     */
    public synchronized String issue(UUID player, String name, String ip, String label) {
        String id = randomPart(6);
        String secret = randomPart(32);
        Device d = new Device();
        d.id = id;
        d.player = player.toString();
        d.name = name;
        d.hash = sha256(secret);
        d.label = label == null ? "" : label.length() > 60 ? label.substring(0, 60) : label;
        d.lastIp = ip == null ? "" : ip;
        d.created = d.lastUsed = clock.getAsLong();
        devices.add(d);
        List<Device> mine = devices.stream().filter(x -> x.player.equals(d.player)).sorted((a, b) -> Long.compare(a.lastUsed, b.lastUsed)).toList();
        for (int i = 0; i < mine.size() - MAX_PER_PLAYER; i++) devices.remove(mine.get(i));
        save();
        return id + "." + secret;
    }

    /**
     * Prueft einen Schluessel. @param expireDays nach so vielen Tagen ohne Benutzung ungueltig; 0 = nie
     * @return das Geraet, oder leer bei unbekanntem, falschem oder abgelaufenem Schluessel
     */
    public synchronized Optional<Device> authenticate(String key, String ip, int expireDays) {
        if (key == null) return Optional.empty();
        int dot = key.indexOf('.');
        if (dot <= 0 || dot >= key.length() - 1 || key.length() > 128) return Optional.empty();
        String id = key.substring(0, dot), secret = key.substring(dot + 1);
        Device d = devices.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        if (d == null) return Optional.empty();
        byte[] have = d.hash.getBytes(StandardCharsets.UTF_8), want = sha256(secret).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(have, want)) return Optional.empty();
        long now = clock.getAsLong();
        if (expireDays > 0 && now - d.lastUsed > expireDays * 86_400_000L) {
            devices.remove(d);
            save();
            return Optional.empty();
        }
        d.lastUsed = now;
        if (ip != null) d.lastIp = ip;
        if (now - lastSaved > SAVE_EVERY_MS) save();
        return Optional.of(d);
    }

    public synchronized List<Device> list() {
        return new ArrayList<>(devices);
    }

    public synchronized List<Device> listFor(UUID player) {
        return devices.stream().filter(d -> d.player.equals(player.toString())).toList();
    }

    /** Sperrt ein Geraet. @return {@code true}, wenn es das gab */
    public synchronized boolean revoke(String id) {
        boolean removed = devices.removeIf(d -> d.id.equals(id));
        if (removed) save();
        return removed;
    }

    /** Sperrt alle Geraete eines Spielers (z. B. wenn er kein Operator mehr ist). */
    public synchronized int revokeAll(UUID player) {
        int before = devices.size();
        devices.removeIf(d -> d.player.equals(player.toString()));
        if (devices.size() != before) save();
        return before - devices.size();
    }
}
