package de.peakverse.core.protocol;

import java.util.List;

/**
 * Steuerpakete des Cores, die als Nutzdaten eines {@link Frame.CoreFrame} laufen. Richtung ist streng getrennt:
 * der Server akzeptiert nur {@link Serverbound}, die Mod nur {@link Clientbound}.
 */
public sealed interface CorePacket {
    sealed interface Serverbound extends CorePacket permits Hello {}

    sealed interface Clientbound extends CorePacket permits HelloAck, AdminState, PackOffer {}

    /** Ein Modul, das die Mod des Spielers mitbringt. */
    record ModuleVersion(String id, String version) {
        public ModuleVersion {
            if (!ModuleIds.isValid(id)) throw new IllegalArgumentException("Ungueltige Modul-ID: " + id);
            if (version == null || version.isEmpty() || version.length() > CoreProtocol.MAX_VERSION_LENGTH) throw new IllegalArgumentException("Ungueltige Version");
        }
    }

    /** Ein Modul, das der Server hat. */
    record ModuleEntry(String id, String displayName, String version) {
        public ModuleEntry {
            if (!ModuleIds.isValid(id)) throw new IllegalArgumentException("Ungueltige Modul-ID: " + id);
            if (displayName == null || displayName.isEmpty() || displayName.length() > CoreProtocol.MAX_NAME_LENGTH) throw new IllegalArgumentException("Ungueltiger Name");
            if (version == null || version.isEmpty() || version.length() > CoreProtocol.MAX_VERSION_LENGTH) throw new IllegalArgumentException("Ungueltige Version");
        }
    }

    /**
     * Erstes Paket der Mod nach dem Beitritt. Bei fremder Protokollversion enthaelt es nur die Version (der Rest wird nicht gelesen),
     * damit der Server sauber "passt nicht zusammen" melden kann, auch wenn sich das Format geaendert hat.
     */
    record Hello(int protocol, String coreVersion, List<ModuleVersion> modules) implements Serverbound {
        public Hello {
            modules = List.copyOf(modules);
        }

        public boolean compatible() {
            return protocol == CoreProtocol.VERSION;
        }
    }

    /** Antwort des Servers auf das Hello. */
    record HelloAck(int protocol, boolean compatible, boolean admin, String coreVersion, int apiVersion, long serverTimeMillis,
                    List<ModuleEntry> modules) implements Clientbound {
        public HelloAck {
            modules = List.copyOf(modules);
        }
    }

    /** Der Admin-Status des Spielers hat sich geaendert (z. B. nach /op oder /deop). */
    record AdminState(boolean admin) implements Clientbound {}

    /**
     * Das Server-Pack zum Herunterladen durch die Mod (statt Annahme-Dialog von Minecraft): Adresse, SHA1 (40 Hex-Zeichen, klein) und Groesse in Bytes.
     * Die Mod prueft beides nach dem Laden und nimmt nichts an, was nicht passt.
     */
    record PackOffer(String url, String sha1, long size) implements Clientbound {
        public PackOffer {
            if (url == null || url.isEmpty() || url.length() > CoreProtocol.MAX_URL_LENGTH) throw new IllegalArgumentException("Ungueltige Adresse");
            if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Ungueltiger SHA1");
            if (size <= 0 || size > CoreProtocol.MAX_PACK_SIZE) throw new IllegalArgumentException("Ungueltige Groesse");
        }
    }
}
