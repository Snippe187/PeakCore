package de.peakverse.core.protocol;

/** Ein Paket ist unlesbar oder verletzt die Regeln (falsche Groesse, unbekannter Typ, ungueltige Modul-ID). Solche Pakete werden verworfen. */
public final class MalformedFrameException extends RuntimeException {
    public MalformedFrameException(String message) {
        super(message);
    }
}
