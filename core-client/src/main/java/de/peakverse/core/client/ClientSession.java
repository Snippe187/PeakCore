package de.peakverse.core.client;

import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CorePacketCodec;
import de.peakverse.core.protocol.CoreProtocol;
import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.Frame;
import de.peakverse.core.protocol.FrameCodec;
import de.peakverse.core.protocol.MalformedFrameException;
import de.peakverse.core.protocol.ModuleIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Die Seite der Mod im Handshake mit PeakCore: meldet sich beim Beitritt mit {@code Hello}, wiederholt es bis zur Antwort,
 * merkt sich Admin-Status und Module des Servers und verteilt Pakete an die Module der Mod. Reine Logik ohne Minecraft;
 * die Fabric-Mod stellt nur den {@link Transport} bereit. Alle Aufrufe gehoeren auf den Client-Haupt-Thread.
 *
 * <p>Module (z. B. die PeakSound-Mod) melden sich mit {@link #registerModule} an, solange die Mods starten; so steht ihre ID
 * schon im Hello, das erst beim Beitritt zu einem Server geschickt wird.
 */
public final class ClientSession {
    /** Wie weit ist der Handshake mit dem Server? */
    public enum State {
        /** Nicht mit einem Server verbunden. */
        OFFLINE,
        /** Der Server kennt den Kanal nicht (kein PeakCore): die Mod bleibt still. */
        NO_SERVER_SUPPORT,
        /** Hello gesendet, Antwort steht aus. */
        WAITING,
        /** Handshake geschafft: Pakete an und von Modulen werden ausgetauscht. */
        ACTIVE,
        /** Der Server hat eine andere Protokollversion: die Mod bleibt fuer diesen Server inaktiv. */
        INCOMPATIBLE
    }

    /** Verbindung zum Server; die Fabric-Mod setzt sie mit {@code ClientPlayNetworking} um. */
    public interface Transport {
        /** Kennt der Server den Kanal {@value Frame#CHANNEL}? */
        boolean canSend();

        void send(byte[] frame);
    }

    /** Empfaenger der Nutzdaten, die der Server an ein Modul schickt. */
    @FunctionalInterface
    public interface ModuleHandler {
        void onMessage(byte[] payload);
    }

    /** Wird bei Aenderungen benachrichtigt (Haupt-Thread). */
    public interface Listener {
        default void onStateChanged(State previous, State now) {}

        default void onAdminChanged(boolean admin) {}
    }

    /** Ein Modul auf Seiten der Mod. */
    public final class ModuleChannel {
        private final String id;
        private final String version;
        private final ModuleHandler handler;

        private ModuleChannel(String id, String version, ModuleHandler handler) {
            this.id = id;
            this.version = version;
            this.handler = handler;
        }

        public String id() {
            return id;
        }

        /** Groesste Nutzdatenmenge je Paket zum Server. */
        public int maxPayload() {
            return FrameCodec.maxModulePayload(id, Direction.SERVERBOUND);
        }

        /** Sendet Nutzdaten an das Modul auf dem Server. {@code false}, wenn der Handshake nicht abgeschlossen ist. */
        public boolean send(byte[] payload) {
            if (modules.get(id) != this || state != State.ACTIVE) return false;
            transport.send(FrameCodec.encode(new Frame.ModuleFrame(id, payload), Direction.SERVERBOUND));
            sentToModules++;
            return true;
        }

        public void unregister() {
            if (modules.remove(id, this)) resendHelloIfRunning();
        }
    }

    /** Wartezeit bis zur Wiederholung des Hello und Zahl der Versuche (wie bei PeakSound bisher). */
    static final long RETRY_INTERVAL_MS = 2000;
    static final int MAX_ATTEMPTS = 10;
    /** So lange wartet die Mod nach dem Beitritt darauf, dass der Server den Kanal meldet (das kommt kurz nach dem Beitritt), bevor sie aufgibt. */
    static final long CHANNEL_WAIT_MS = 10_000;

    private final String coreVersion;
    private final Transport transport;
    private final LongSupplier clock;
    private final Map<String, ModuleChannel> modules = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private State state = State.OFFLINE;
    private int attempts;
    private long helloSentAt;
    private long joinAt;
    private boolean helloEverSent;
    private boolean admin;
    private String serverCoreVersion = "";
    private int serverApiVersion;
    private long clockOffsetMs;
    private List<CorePacket.ModuleEntry> serverModules = List.of();
    private long lastAckAt;

    // Zaehler fuer die Diagnose
    private int malformed, droppedBeforeActive, unknownModule, handlerErrors, helloSent, acks, sentToModules, deliveredToModules;

    public ClientSession(String coreVersion, Transport transport, LongSupplier clock) {
        this.coreVersion = coreVersion;
        this.transport = transport;
        this.clock = clock;
    }

    // ---------- Module ----------

    /**
     * Meldet ein Modul der Mod an. Besser waehrend des Starts aufrufen; geschieht es spaeter, waehrend eine Verbindung laeuft, wird das Hello erneut gesendet.
     *
     * @throws IllegalArgumentException bei ungueltiger oder doppelter ID oder zu vielen Modulen
     */
    public ModuleChannel registerModule(String id, String version, ModuleHandler handler) {
        if (!ModuleIds.isValid(id)) throw new IllegalArgumentException("Ungueltige Modul-ID: " + id);
        if (version == null || version.isEmpty() || version.length() > CoreProtocol.MAX_VERSION_LENGTH) throw new IllegalArgumentException("Ungueltige Version");
        if (handler == null) throw new IllegalArgumentException("handler fehlt");
        if (modules.containsKey(id)) throw new IllegalArgumentException("Modul schon angemeldet: " + id);
        if (modules.size() >= CoreProtocol.MAX_MODULES) throw new IllegalArgumentException("Zu viele Module");
        ModuleChannel channel = new ModuleChannel(id, version, handler);
        modules.put(id, channel);
        resendHelloIfRunning();
        return channel;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    // ---------- Verbindung ----------

    /** Beitritt zu einem Server: Zustand zuruecksetzen und das Hello senden (sofern der Server den Kanal kennt). */
    public void onJoin() {
        resetConnection();
        joinAt = clock.getAsLong();
        setState(State.WAITING);
        // Der Server meldet den Kanal erst kurz nach dem Beitritt: ist er schon bekannt, geht das Hello sofort raus, sonst im naechsten Tick
        if (transport.canSend()) sendHello();
    }

    /** Trennung vom Server. */
    public void onDisconnect() {
        resetConnection();
        setState(State.OFFLINE);
    }

    /** Einmal je Client-Tick aufrufen: sendet das Hello, sobald der Server den Kanal kennt, und wiederholt es, solange keine Antwort da ist. */
    public void tick() {
        if (state != State.WAITING) return;
        long now = clock.getAsLong();
        if (!helloEverSent) {
            if (transport.canSend()) sendHello();
            else if (now - joinAt >= CHANNEL_WAIT_MS) setState(State.NO_SERVER_SUPPORT); // kein PeakCore auf dem Server
        } else if (attempts < MAX_ATTEMPTS && now - helloSentAt >= RETRY_INTERVAL_MS) {
            if (transport.canSend()) sendHello();
        }
    }

    private void resendHelloIfRunning() {
        if (helloEverSent && (state == State.WAITING || state == State.ACTIVE)) {
            if (state == State.ACTIVE) setState(State.WAITING); // das neue Hello wird erneut beantwortet
            attempts = 0;
            if (transport.canSend()) sendHello();
        }
    }

    private void sendHello() {
        List<CorePacket.ModuleVersion> list = new ArrayList<>();
        for (ModuleChannel m : modules.values()) list.add(new CorePacket.ModuleVersion(m.id, m.version));
        byte[] payload = CorePacketCodec.encode(new CorePacket.Hello(CoreProtocol.VERSION, coreVersion, list));
        transport.send(FrameCodec.encode(new Frame.CoreFrame(payload), Direction.SERVERBOUND));
        attempts++;
        helloEverSent = true;
        helloSent++;
        helloSentAt = clock.getAsLong();
    }

    private void resetConnection() {
        attempts = 0;
        helloSentAt = 0;
        joinAt = 0;
        helloEverSent = false;
        admin = false;
        serverCoreVersion = "";
        serverApiVersion = 0;
        clockOffsetMs = 0;
        serverModules = List.of();
        lastAckAt = 0;
    }

    // ---------- Empfang ----------

    /** Ein Paket vom Server. Wirft nie eine Ausnahme. */
    public void onFrame(byte[] data) {
        Frame frame;
        try {
            frame = FrameCodec.decode(data, Direction.CLIENTBOUND);
        } catch (MalformedFrameException e) {
            malformed++;
            return;
        }
        switch (frame) {
            case Frame.CoreFrame c -> onCore(c.payload());
            case Frame.ModuleFrame m -> onModule(m);
        }
    }

    private void onCore(byte[] payload) {
        CorePacket.Clientbound packet;
        try {
            packet = CorePacketCodec.decodeClientbound(payload);
        } catch (MalformedFrameException e) {
            malformed++;
            return;
        }
        switch (packet) {
            case CorePacket.HelloAck ack -> onAck(ack);
            case CorePacket.AdminState s -> {
                if (state != State.ACTIVE) return;
                if (s.admin() != admin) {
                    admin = s.admin();
                    for (Listener l : listeners) l.onAdminChanged(admin);
                }
            }
        }
    }

    private void onAck(CorePacket.HelloAck ack) {
        if (state != State.WAITING && state != State.ACTIVE) return; // unerwartete Antwort
        acks++;
        lastAckAt = clock.getAsLong();
        if (!ack.compatible() || ack.protocol() != CoreProtocol.VERSION) {
            admin = false;
            serverModules = List.of();
            setState(State.INCOMPATIBLE);
            return;
        }
        boolean adminBefore = admin;
        admin = ack.admin();
        serverCoreVersion = ack.coreVersion();
        serverApiVersion = ack.apiVersion();
        serverModules = ack.modules();
        // Uhr des Servers: Serverzeit minus Mitte zwischen Senden und Empfang
        clockOffsetMs = ack.serverTimeMillis() - (helloSentAt + lastAckAt) / 2;
        State previous = state;
        setState(State.ACTIVE);
        if (previous == State.ACTIVE && adminBefore != admin) for (Listener l : listeners) l.onAdminChanged(admin);
    }

    private void onModule(Frame.ModuleFrame frame) {
        if (state != State.ACTIVE) {
            droppedBeforeActive++;
            return;
        }
        ModuleChannel channel = modules.get(frame.moduleId());
        if (channel == null) {
            unknownModule++;
            return;
        }
        try {
            channel.handler.onMessage(frame.payload());
            deliveredToModules++;
        } catch (RuntimeException e) {
            handlerErrors++;
        }
    }

    private void setState(State next) {
        State previous = state;
        if (previous == next) return;
        state = next;
        for (Listener l : listeners) l.onStateChanged(previous, next);
    }

    // ---------- Abfragen ----------

    public State state() {
        return state;
    }

    /** Handshake geschafft? */
    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** Ist der Spieler laut Server Administrator? Nur zur Anzeige; der Server prueft jede Aktion selbst. */
    public boolean isAdmin() {
        return state == State.ACTIVE && admin;
    }

    public String serverCoreVersion() {
        return serverCoreVersion;
    }

    public int serverApiVersion() {
        return serverApiVersion;
    }

    public List<CorePacket.ModuleEntry> serverModules() {
        return serverModules;
    }

    /** Hat der Server dieses Modul? (Nur sinnvoll, wenn {@link #isActive()}.) */
    public boolean serverHasModule(String moduleId) {
        return serverModules.stream().anyMatch(m -> m.id().equals(moduleId));
    }

    /** Geschaetzte aktuelle Serverzeit in Millisekunden (z. B. fuer synchrone Wiedergabe). */
    public long serverNow() {
        return clock.getAsLong() + clockOffsetMs;
    }

    public List<String> moduleIds() {
        return List.copyOf(modules.keySet());
    }

    /** Diagnose in mehreren Zeilen. */
    public String debug() {
        long now = clock.getAsLong();
        return "Zustand " + state + ", Admin " + admin + ", Server-Core " + (serverCoreVersion.isEmpty() ? "-" : serverCoreVersion) + " (API " + serverApiVersion
                + "), Server-Module " + (serverModules.isEmpty() ? "keine" : serverModules.stream().map(m -> m.id() + " " + m.version()).toList())
                + "\nMeine Module " + moduleIds() + ", Hello " + helloSent + "x gesendet (Versuch " + attempts + "/" + MAX_ATTEMPTS + "), Antworten " + acks
                + (lastAckAt == 0 ? "" : ", letzte vor " + (now - lastAckAt) / 1000 + " s") + ", Uhrversatz " + clockOffsetMs + " ms"
                + "\nPakete: " + deliveredToModules + " an Module zugestellt, " + sentToModules + " gesendet, " + malformed + " ungueltig, "
                + droppedBeforeActive + " vor dem Handshake verworfen, " + unknownModule + " an unbekannte Module, " + handlerErrors + " Handler-Fehler";
    }
}
