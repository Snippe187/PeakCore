package de.peakverse.core.plugin.pack;

/**
 * Bestimmt die Adresse, die Spieler zum Herunterladen des Packs bekommen.
 * <ul>
 *   <li>{@code externalTemplate} (Ausweg, wenn das Pack woanders liegt, z. B. auf einem Webspace): Adresse mit {@code {sha1}} als Platzhalter.</li>
 *   <li>sonst {@code publicBase}: die von aussen erreichbare Adresse des eingebauten Webservers, z. B. {@code http://play.example.com:8123}.</li>
 * </ul>
 */
public final class PackUrls {
    private PackUrls() {}

    /** @return die Adresse oder {@code null}, wenn nichts eingestellt ist */
    public static String urlFor(String sha1, String publicBase, String externalTemplate) {
        if (externalTemplate != null && !externalTemplate.isBlank()) {
            if (!externalTemplate.contains("{sha1}")) throw new IllegalArgumentException("pack.external-url braucht den Platzhalter {sha1}");
            return check(externalTemplate.trim().replace("{sha1}", sha1));
        }
        if (publicBase == null || publicBase.isBlank()) return null;
        String base = publicBase.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return check(base + "/pack/" + sha1 + ".zip");
    }

    private static String check(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalArgumentException("Die Pack-Adresse muss mit http:// oder https:// beginnen: " + url);
        return url;
    }
}
