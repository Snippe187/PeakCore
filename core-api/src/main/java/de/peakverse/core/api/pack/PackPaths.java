package de.peakverse.core.api.pack;

import java.util.regex.Pattern;

/**
 * Regeln fuer Pfade im Resource-Pack. Sie sind absichtlich streng: Minecraft erlaubt nur Kleinbuchstaben, Ziffern und {@code _ - . /},
 * und ein Pfad wie {@code ../x} oder ein absoluter Pfad duerfte nie in ein ZIP gelangen.
 */
public final class PackPaths {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_.\\-]+(/[a-z0-9_.\\-]+)*");

    private PackPaths() {}

    /**
     * Prueft einen Pack-Pfad (Schraegstriche als Trenner, relativ zur Pack-Wurzel).
     *
     * @return der Pfad unveraendert
     * @throws IllegalArgumentException bei leerem Pfad, Grossbuchstaben, Leer- oder Sonderzeichen, {@code ..}, fuehrendem {@code /} oder {@code \}
     */
    public static String validate(String path) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Pack-Pfad ist leer");
        if (path.length() > 250) throw new IllegalArgumentException("Pack-Pfad ist zu lang: " + path.length() + " Zeichen");
        if (!VALID.matcher(path).matches()) throw new IllegalArgumentException("Ungueltiger Pack-Pfad (erlaubt: a-z 0-9 _ - . und /): " + path);
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Pack-Pfad darf kein '.' oder '..' enthalten: " + path);
        }
        return path;
    }

    /** Der Namensraum eines {@code assets/<namespace>/...}-Pfads, sonst {@code null}. */
    public static String namespaceOf(String path) {
        if (!path.startsWith("assets/")) return null;
        int end = path.indexOf('/', 7);
        return end < 0 ? null : path.substring(7, end);
    }
}
