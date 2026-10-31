package de.peakverse.core.protocol;

/** Konstanten des Core-Protokolls (Handshake und Steuerpakete, die im {@link Frame.CoreFrame} laufen). */
public final class CoreProtocol {
    /**
     * Version des Core-Protokolls. Sie steigt nur, wenn sich das Format der Core-Pakete aendert. Passen Server und Mod nicht zusammen,
     * bekommt der Spieler einen Hinweis und die Mod bleibt fuer diesen Server inaktiv (der Spieler wird nie gekickt).
     */
    public static final int VERSION = 1;

    /** Hoechstens so viele Module in einem Hello oder HelloAck. */
    public static final int MAX_MODULES = 32;
    public static final int MAX_VERSION_LENGTH = 32;
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_URL_LENGTH = 400;
    /** Groesstes Pack, das die Mod laedt (wie das Limit des Basis-Packs auf dem Server). */
    public static final long MAX_PACK_SIZE = 200L * 1024 * 1024;

    private CoreProtocol() {}
}
