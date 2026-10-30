package de.peakverse.core.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Texte fuer Spieler, deutsch oder englisch je nach Spielsprache. */
final class CoreMessages {
    private CoreMessages() {}

    private static boolean german(Player player) {
        return player.locale().getLanguage().equalsIgnoreCase("de");
    }

    /** Hinweis fuer Spieler ohne Mod; wird nur einmal kurz nach dem Beitritt gezeigt. */
    static Component nonModHint(Player player) {
        return Component.text(german(player)
                ? "Dieser Server nutzt PeakVerse-Mods fuer zusaetzliche Bloecke, Sounds und Texturen. Ohne die Mod kannst du ganz normal weiterspielen."
                : "This server uses PeakVerse mods for extra blocks, sounds and textures. Without the mod you can keep playing normally.", NamedTextColor.GRAY);
    }

    /** Die Mod passt nicht zum Server; der Spieler wird nie gekickt. */
    static Component versionMismatch(Player player, int serverProtocol, int clientProtocol) {
        return Component.text(german(player)
                ? "Mod und Server passen nicht zusammen (Server-Protokoll " + serverProtocol + ", Mod-Protokoll " + clientProtocol + "). Bitte aktualisiere die Mod."
                : "Mod and server do not match (server protocol " + serverProtocol + ", mod protocol " + clientProtocol + "). Please update the mod.", NamedTextColor.RED);
    }
}
