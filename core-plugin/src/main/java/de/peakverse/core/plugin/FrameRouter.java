package de.peakverse.core.plugin;

import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.Frame;
import de.peakverse.core.protocol.FrameCodec;
import de.peakverse.core.protocol.MalformedFrameException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Nimmt rohe Pakete von Spielern entgegen, decodiert sie und reicht die Nutzdaten an das richtige Modul weiter.
 * Reine Logik ohne Bukkit ({@code P} ist der Spieler-Typ), damit sie sich testen laesst.
 * Fehlerhafte Pakete, Pakete an unbekannte Module und Ausnahmen in Handlern werden gezaehlt und nie weitergereicht.
 */
final class FrameRouter<P> {
    /** Zaehlerstand fuer die Diagnose. */
    record Stats(long received, long malformed, long unknownModule, long handlerErrors, long delivered) {}

    private final Logger log;
    private final Map<String, BiConsumer<P, byte[]>> handlers = new ConcurrentHashMap<>();
    private volatile BiConsumer<P, byte[]> coreHandler;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();
    private final AtomicLong unknownModule = new AtomicLong();
    private final AtomicLong handlerErrors = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();

    FrameRouter(Logger log) {
        this.log = log;
    }

    void setHandler(String moduleId, BiConsumer<P, byte[]> handler) {
        if (handler == null) handlers.remove(moduleId);
        else handlers.put(moduleId, handler);
    }

    void removeHandler(String moduleId) {
        handlers.remove(moduleId);
    }

    /** Empfaenger der Core-Steuerpakete (Handshake, ab Phase 4). */
    void setCoreHandler(BiConsumer<P, byte[]> handler) {
        this.coreHandler = handler;
    }

    /** Verarbeitet ein Paket vom Client. Wirft nie eine Ausnahme. */
    void receive(P player, byte[] data) {
        received.incrementAndGet();
        Frame frame;
        try {
            frame = FrameCodec.decode(data, Direction.SERVERBOUND);
        } catch (MalformedFrameException e) {
            malformed.incrementAndGet();
            log.fine("Ungueltiges Paket verworfen: " + e.getMessage());
            return;
        }
        switch (frame) {
            case Frame.ModuleFrame m -> {
                BiConsumer<P, byte[]> handler = handlers.get(m.moduleId());
                if (handler == null) {
                    unknownModule.incrementAndGet();
                    return;
                }
                dispatch(handler, player, m.payload(), "Modul " + m.moduleId());
            }
            case Frame.CoreFrame c -> {
                BiConsumer<P, byte[]> handler = coreHandler;
                if (handler == null) {
                    unknownModule.incrementAndGet();
                    return;
                }
                dispatch(handler, player, c.payload(), "Core");
            }
        }
    }

    private void dispatch(BiConsumer<P, byte[]> handler, P player, byte[] payload, String target) {
        try {
            handler.accept(player, payload);
            delivered.incrementAndGet();
        } catch (RuntimeException e) {
            handlerErrors.incrementAndGet();
            log.log(Level.WARNING, "Fehler beim Verarbeiten eines Pakets fuer " + target, e);
        }
    }

    Stats stats() {
        return new Stats(received.get(), malformed.get(), unknownModule.get(), handlerErrors.get(), delivered.get());
    }
}
