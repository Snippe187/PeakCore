package de.peakverse.core.plugin;

import de.peakverse.core.plugin.pack.PackService;
import de.peakverse.core.plugin.pack.PackState;
import de.peakverse.core.plugin.pack.PackWebServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /peakcore pack ...}: Bau, Versand, Versionen, Testmodus und Varianten. Nur fuer Administratoren (das prueft der Aufrufer). */
final class PackCommand {
    private static final List<String> ACTIONS = List.of("status", "rebuild", "resend", "versions", "rollback", "publish", "test", "variant");

    private final PackService packs;
    private final PackSender sender;
    private final PackWebServer web;

    PackCommand(PackService packs, PackSender sender, PackWebServer web) {
        this.packs = packs;
        this.sender = sender;
        this.web = web;
    }

    /** @param args die Argumente nach "pack" */
    void run(CommandSender who, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        try {
            switch (action) {
                case "status" -> status(who);
                case "rebuild" -> rebuild(who);
                case "resend" -> resend(who);
                case "versions" -> versions(who, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : PackState.STANDARD);
                case "rollback" -> rollback(who, args);
                case "publish" -> publish(who);
                case "test" -> test(who, args);
                case "variant" -> variant(who, args);
                default -> usage(who);
            }
        } catch (IllegalArgumentException e) {
            msg(who, e.getMessage(), NamedTextColor.RED);
        } catch (java.io.IOException e) {
            msg(who, "Fehler beim Speichern oder Bauen: " + e, NamedTextColor.RED);
        }
    }

    private static void msg(CommandSender who, String text, NamedTextColor color) {
        who.sendMessage(Component.text(text, color));
    }

    private void usage(CommandSender who) {
        msg(who, "Benutzung: /peakcore pack <status|rebuild|resend|versions [variante]|rollback <variante> <nr>|publish|test <on|off|add|remove|list>|variant <spieler> <standard|lite|default>>", NamedTextColor.RED);
    }

    // ---- Anzeigen ----

    private void status(CommandSender who) {
        PackService.Status s = packs.status();
        if (s == null) msg(who, "Das Pack wurde noch nicht gebaut: /peakcore pack rebuild", NamedTextColor.GRAY);
        else describe(who, s);
        String url;
        try {
            url = sender.currentUrl();
        } catch (IllegalArgumentException e) {
            url = "FEHLER: " + e.getMessage();
        }
        msg(who, "Webserver: " + (web.running() ? "laeuft auf Port " + web.port() : "aus") + ", Senden: " + (sender.enabled() ? "an" : "aus")
                + ", Adresse: " + (url == null ? "nicht eingestellt" : url), NamedTextColor.GRAY);
        msg(who, "Testmodus: " + (packs.testMode() ? "AN, Tester: " + packs.testers().size() : "aus") + ", Live: " + short_(packs.liveSha(PackState.STANDARD)) + " (standard), "
                + short_(packs.liveSha(PackState.LITE)) + " (lite)" + (packs.testMode() ? ", Test: " + short_(packs.testSha(PackState.STANDARD)) + " / " + short_(packs.testSha(PackState.LITE)) : ""),
                NamedTextColor.GRAY);
    }

    private static String short_(String sha) {
        return sha == null ? "-" : sha.substring(0, 8);
    }

    private static void describe(CommandSender who, PackService.Status s) {
        for (PackService.VariantBuild v : s.variants().values()) {
            msg(who, "Pack " + v.variant() + " " + v.sha1() + ": " + v.fileCount() + " Dateien (mit pack.mcmeta), " + v.size() + " Bytes", NamedTextColor.GOLD);
        }
        msg(who, "  davon " + s.baseFiles() + " aus dem Basis-Pack, " + s.mergedFiles() + " zusammengefuehrt, PNG-Optimierung spart " + s.pngSavedBytes() + " Bytes, Beitraege "
                + (s.filesByModule().isEmpty() ? "keine" : s.filesByModule().toString()), NamedTextColor.GRAY);
        for (String problem : s.problems()) msg(who, "  Ausgelassen: " + problem, NamedTextColor.RED);
    }

    private void versions(CommandSender who, String variant) {
        List<PackState.Entry> list = packs.versions(variant);
        if (list.isEmpty()) {
            msg(who, "Keine Versionen fuer '" + variant + "'.", NamedTextColor.GRAY);
            return;
        }
        String live = packs.liveSha(variant);
        String test = packs.testSha(variant);
        msg(who, "Versionen von '" + variant + "' (neueste zuerst):", NamedTextColor.GOLD);
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            PackState.Entry e = list.get(i);
            String mark = (e.sha1.equals(live) ? " [LIVE]" : "") + (e.sha1.equals(test) ? " [TEST]" : "");
            msg(who, " #" + e.n + "  " + e.sha1.substring(0, 12) + "  " + e.size + " Bytes  " + e.builtAt + mark, e.sha1.equals(live) ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
        }
    }

    // ---- Bauen und Senden ----

    private void rebuild(CommandSender who) throws java.io.IOException {
        PackService.Status s = packs.rebuild();
        msg(who, s.written() ? "Pack neu gebaut." : "Pack unveraendert (gleicher Inhalt, nichts neu geschrieben).", NamedTextColor.GREEN);
        describe(who, s);
        if (s.pointersChanged() && sender.enabled()) {
            msg(who, "Neues Pack an " + sender.sendAll(Bukkit.getOnlinePlayers()) + " Spieler geschickt.", NamedTextColor.GREEN);
        }
        if (packs.testMode() && packs.testSha(PackState.STANDARD) != null) {
            msg(who, "Testmodus: das neue Pack bekommen nur die Tester. Fuer alle: /peakcore pack publish", NamedTextColor.YELLOW);
        }
    }

    private void resend(CommandSender who) {
        if (packs.status() == null) throw new IllegalArgumentException("Das Pack wurde noch nicht gebaut: /peakcore pack rebuild");
        if (sender.currentUrl() == null) throw new IllegalArgumentException("Keine Adresse: pack.public-url oder pack.external-url in der config.yml eintragen.");
        msg(who, "Pack an " + sender.sendAll(Bukkit.getOnlinePlayers()) + " Spieler geschickt.", NamedTextColor.GREEN);
    }

    private void resendIfOn(CommandSender who) {
        if (sender.enabled() && sender.currentUrl() != null) msg(who, "Pack an " + sender.sendAll(Bukkit.getOnlinePlayers()) + " Spieler geschickt.", NamedTextColor.GREEN);
    }

    // ---- Versionen ----

    private void rollback(CommandSender who, String[] args) throws java.io.IOException {
        if (args.length < 3) throw new IllegalArgumentException("Benutzung: /peakcore pack rollback <standard|lite> <nummer>  (Nummern: /peakcore pack versions)");
        int n;
        try {
            n = Integer.parseInt(args[2].replace("#", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Keine Versionsnummer: " + args[2]);
        }
        PackState.Entry e = packs.rollback(args[1].toLowerCase(Locale.ROOT), n);
        msg(who, "Live-Pack von '" + e.variant + "' ist jetzt Version #" + e.n + " (" + e.sha1.substring(0, 12) + "). Ein neuer Bau mit anderem Inhalt ersetzt es wieder.", NamedTextColor.GREEN);
        resendIfOn(who);
    }

    private void publish(CommandSender who) throws java.io.IOException {
        int n = packs.publish();
        msg(who, n == 0 ? "Es gab kein Test-Pack zum Veroeffentlichen." : "Test-Pack veroeffentlicht (" + n + " Variante(n)).", n == 0 ? NamedTextColor.GRAY : NamedTextColor.GREEN);
        if (n > 0) resendIfOn(who);
    }

    // ---- Testmodus ----

    private void test(CommandSender who, String[] args) throws java.io.IOException {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "on" -> {
                packs.setTestMode(true);
                msg(who, "Testmodus AN: neue Packs bekommen nur die Tester (/peakcore pack test add <spieler>), bis du /peakcore pack publish ausfuehrst.", NamedTextColor.GREEN);
            }
            case "off" -> {
                packs.setTestMode(false);
                msg(who, "Testmodus aus, Test-Packs verworfen: alle bekommen das Live-Pack.", NamedTextColor.GREEN);
                resendIfOn(who);
            }
            case "add", "remove" -> {
                if (args.length < 3) throw new IllegalArgumentException("Benutzung: /peakcore pack test " + sub + " <spieler>");
                OfflinePlayer p = player(args[2]);
                boolean changed = sub.equals("add") ? packs.addTester(p.getUniqueId()) : packs.removeTester(p.getUniqueId());
                msg(who, p.getName() + (sub.equals("add") ? (changed ? " ist jetzt Tester." : " war schon Tester.") : (changed ? " ist kein Tester mehr." : " war kein Tester.")), NamedTextColor.GREEN);
                if (p.isOnline() && sender.enabled()) sender.send(p.getPlayer());
            }
            case "list" -> {
                List<String> names = new ArrayList<>();
                for (String id : packs.testers()) {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(UUID.fromString(id));
                    names.add(p.getName() != null ? p.getName() : id);
                }
                msg(who, "Testmodus: " + (packs.testMode() ? "AN" : "aus") + ", Tester (" + names.size() + "): " + (names.isEmpty() ? "keine" : String.join(", ", names)), NamedTextColor.GOLD);
            }
            default -> throw new IllegalArgumentException("Benutzung: /peakcore pack test <on|off|add <spieler>|remove <spieler>|list>");
        }
    }

    // ---- Varianten ----

    private void variant(CommandSender who, String[] args) throws java.io.IOException {
        if (args.length < 3) throw new IllegalArgumentException("Benutzung: /peakcore pack variant <spieler> <standard|lite|default>");
        OfflinePlayer p = player(args[1]);
        String v = args[2].toLowerCase(Locale.ROOT);
        packs.setPlayerVariant(p.getUniqueId(), v.equals("default") ? null : v);
        msg(who, p.getName() + " bekommt jetzt die Variante '" + packs.variantOf(p.getUniqueId()) + "'.", NamedTextColor.GREEN);
        if (p.isOnline() && sender.enabled()) sender.send(p.getPlayer());
    }

    /** Ein Spieler, der online ist oder dem Server schon bekannt. */
    private static OfflinePlayer player(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) throw new IllegalArgumentException("Spieler '" + name + "' ist dem Server nicht bekannt");
        return cached;
    }

    // ---- Tab-Vervollstaendigung ----

    /** @param args die Argumente nach "pack" (das letzte ist das angefangene Wort) */
    List<String> complete(String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = List.of();
        if (args.length == 1) options = ACTIONS;
        else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "versions", "rollback" -> options = PackState.VARIANTS;
                case "test" -> options = List.of("on", "off", "add", "remove", "list");
                case "variant" -> options = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                default -> { }
            }
        } else if (args.length == 3) {
            String a = args[0].toLowerCase(Locale.ROOT);
            if (a.equals("test") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) options = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            else if (a.equals("variant")) options = List.of("standard", "lite", "default");
        }
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
