package de.peakverse.core.protocol;

/** Richtung eines Pakets. Sie bestimmt die groesste erlaubte Paketgroesse (Grenzen des Plugin-Messagings von Minecraft). */
public enum Direction {
    /** Client -> Server: knapp 32 KiB je Payload. */
    SERVERBOUND(32 * 1024 - 1),
    /** Server -> Client: knapp 1 MiB je Payload. */
    CLIENTBOUND(1024 * 1024 - 1);

    private final int maxFrameSize;

    Direction(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    /** Groesste erlaubte Gesamtgroesse eines Pakets inklusive Kopf. */
    public int maxFrameSize() {
        return maxFrameSize;
    }
}
