package de.peakverse.core.plugin;

import de.peakverse.core.api.ClientInfo;
import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CorePacketCodec;
import de.peakverse.core.protocol.CoreProtocol;
import de.peakverse.core.protocol.MalformedFrameException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Der Handshake zwischen Server und Mod: prueft die Protokollversion, merkt sich, welche Spieler eine kompatible Mod haben,
 * antwortet mit Version, Admin-Status und Modulliste und haelt den Admin-Status aktuell. Reine Logik ohne Bukkit
 * ({@code P} ist der Spieler-Typ), damit sie sich testen laesst.
 *
 * <p>Wichtig: Die Mod schickt Hello und die Anmeldung des Kanals unmittelbar nacheinander. Kommt das Hello zuerst an, wuerde Paper eine
 * Antwort verwerfen, weil der Client den Kanal noch nicht angemeldet hat. Deshalb wird die Antwort erst gesendet, wenn beides da ist.
 */
final class HandshakeService<P> {
    /** Was der Service von der Umgebung (Bukkit oder ein Test) braucht. */
    interface Env<P> {
        UUID id(P player);

        String name(P player);

        boolean isAdmin(P player);

        /** Hat der Client den Kanal beim Server angemeldet? Erst dann kommen Antworten an. */
        boolean channelRegistered(P player);

        void send(P player, CorePacket.Clientbound packet);

        /** Die Mod hat eine andere Protokollversion: dem Spieler einen Hinweis zeigen (kein Kick). */
        void notifyIncompatible(P player, int clientProtocol);

        /** Handshake erfolgreich (Event ausloesen). */
        void handshakeDone(P player, ClientInfo client);

        long now();

        String coreVersion();

        int apiVersion();

        List<CorePacket.ModuleEntry> serverModules();
    }

    /** Hoechstens so viele Hello-Pakete je Spieler und Sitzung werden beachtet (Schutz vor Dauerbeschuss). */
    static final int MAX_HELLOS = 100;

    private record HelloInfo(CorePacket.Hello hello, long at, boolean compatible) {}

    private final Env<P> env;
    private final Logger log;
    private final Map<UUID, HelloInfo> hellos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> helloCounts = new ConcurrentHashMap<>();
    private final Set<UUID> modded = ConcurrentHashMap.newKeySet();
    private final Set<UUID> recognized = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingAck = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> lastAdmin = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastError = new ConcurrentHashMap<>();

    HandshakeService(Env<P> env, Logger log) {
        this.env = env;
        this.log = log;
    }

    /** Ein Core-Paket vom Client. Wirft nie eine Ausnahme. */
    void onCorePayload(P player, byte[] payload) {
        UUID id = env.id(player);
        CorePacket.Serverbound packet;
        try {
            packet = CorePacketCodec.decodeServerbound(payload);
        } catch (MalformedFrameException e) {
            lastError.put(id, e.getMessage());
            log.fine("Ungueltiges Core-Paket von " + env.name(player) + ": " + e.getMessage());
            return;
        }
        switch (packet) {
            case CorePacket.Hello hello -> onHello(player, id, hello);
            case CorePacket.PackReport report -> {
                java.util.function.BiConsumer<P, String> sink = packReportHandler;
                if (sink != null && modded.contains(id) && packReports.merge(id, 1, Integer::sum) <= MAX_PACK_REPORTS) sink.accept(player, report.message());
            }
            // Tunnel-Pakete nur von Spielern mit abgeschlossenem Handshake
            default -> {
                java.util.function.BiConsumer<P, CorePacket.Serverbound> handler = tunnelHandler;
                if (handler != null && modded.contains(id) && lastAdmin.containsKey(id)) handler.accept(player, packet);
            }
        }
    }

    private volatile java.util.function.BiConsumer<P, CorePacket.Serverbound> tunnelHandler;
    private volatile java.util.function.BiConsumer<P, String> packReportHandler;
    private static final int MAX_PACK_REPORTS = 10;
    private final Map<UUID, Integer> packReports = new ConcurrentHashMap<>();

    /** Empfaenger der Pack-Fehler, die die Mod meldet (Spieler, Text). */
    void setPackReportHandler(java.util.function.BiConsumer<P, String> handler) {
        this.packReportHandler = handler;
    }

    /** Empfaenger der Tunnel-Pakete (Web-Oberflaeche ueber die Mod). */
    void setTunnelHandler(java.util.function.BiConsumer<P, CorePacket.Serverbound> handler) {
        this.tunnelHandler = handler;
    }

    private void onHello(P player, UUID id, CorePacket.Hello hello) {
        if (helloCounts.merge(id, 1, Integer::sum) > MAX_HELLOS) return;
        boolean compatible = hello.compatible();
        hellos.put(id, new HelloInfo(hello, env.now(), compatible));
        if (compatible) {
            modded.add(id);
        } else {
            modded.remove(id);
            lastAdmin.remove(id);
            env.notifyIncompatible(player, hello.protocol());
        }
        log.info(env.name(player) + ": Mod meldet sich (Core-Protokoll " + hello.protocol() + ", Server " + CoreProtocol.VERSION
                + (compatible ? ", Module: " + moduleList(hello) : ", nicht kompatibel") + ")");
        pendingAck.add(id);
        trySendAck(player);
    }

    /** Der Client hat den Kanal angemeldet: eine wartende Antwort kann jetzt raus. */
    void onChannelRegistered(P player) {
        trySendAck(player);
    }

    private void trySendAck(P player) {
        UUID id = env.id(player);
        HelloInfo info = hellos.get(id);
        if (info == null || !env.channelRegistered(player)) return;
        if (!pendingAck.remove(id)) return;
        boolean admin = info.compatible() && env.isAdmin(player);
        List<CorePacket.ModuleEntry> modules = info.compatible() ? limited(env.serverModules()) : List.of();
        env.send(player, new CorePacket.HelloAck(CoreProtocol.VERSION, info.compatible(), admin, env.coreVersion(), env.apiVersion(), env.now(), modules));
        if (info.compatible()) {
            lastAdmin.put(id, admin);
            env.handshakeDone(player, clientInfo(info));
        }
    }

    private List<CorePacket.ModuleEntry> limited(List<CorePacket.ModuleEntry> modules) {
        if (modules.size() <= CoreProtocol.MAX_MODULES) return modules;
        log.warning("Mehr als " + CoreProtocol.MAX_MODULES + " Module angemeldet; nur die ersten " + CoreProtocol.MAX_MODULES + " werden der Mod gemeldet.");
        return modules.subList(0, CoreProtocol.MAX_MODULES);
    }

    /** Gleicht den Admin-Status der Spieler mit Mod ab und meldet Aenderungen (z. B. nach /op oder /deop). */
    void checkAdmins(Iterable<? extends P> onlinePlayers) {
        for (P player : onlinePlayers) {
            UUID id = env.id(player);
            Boolean before = lastAdmin.get(id);
            if (before == null || !modded.contains(id)) continue; // noch kein Ack gesendet oder keine Mod
            boolean now = env.isAdmin(player);
            if (before != now) {
                lastAdmin.put(id, now);
                env.send(player, new CorePacket.AdminState(now));
            }
        }
    }

    boolean isModded(P player) {
        return modded.contains(env.id(player));
    }

    /** Ein Modul meldet: dieser Spieler hat einen passenden Client auf anderem Weg (alte Mod). Nur fuer den Hinweis "keine Mod"; kein Handshake. */
    void markRecognized(P player) {
        recognized.add(env.id(player));
    }

    /** Soll dem Spieler der Hinweis "keine Mod gefunden" gezeigt werden? */
    boolean needsNonModHint(P player) {
        UUID id = env.id(player);
        return !modded.contains(id) && !recognized.contains(id);
    }

    Optional<ClientInfo> client(P player) {
        UUID id = env.id(player);
        if (!modded.contains(id)) return Optional.empty();
        HelloInfo info = hellos.get(id);
        return info == null ? Optional.empty() : Optional.of(clientInfo(info));
    }

    void onQuit(P player) {
        UUID id = env.id(player);
        modded.remove(id);
        recognized.remove(id);
        hellos.remove(id);
        helloCounts.remove(id);
        pendingAck.remove(id);
        lastAdmin.remove(id);
        lastError.remove(id);
    }

    /** Diagnosezeilen ueber diesen Spieler fuer {@code /peakcore players}. */
    String describe(P player) {
        UUID id = env.id(player);
        HelloInfo info = hellos.get(id);
        if (info == null) return "keine Mod gemeldet" + (lastError.containsKey(id) ? " (letzter Paketfehler: " + lastError.get(id) + ")" : "");
        long ago = (env.now() - info.at()) / 1000;
        String base = "Core-Protokoll " + info.hello().protocol() + (info.compatible() ? " (kompatibel)" : " (NICHT kompatibel, Server " + CoreProtocol.VERSION + ")")
                + ", vor " + ago + " s";
        if (!info.compatible()) return base;
        return base + ", Mod " + info.hello().coreVersion() + ", Module: " + moduleList(info.hello()) + (pendingAck.contains(id) ? ", Antwort wartet auf Kanal-Anmeldung" : "");
    }

    private static String moduleList(CorePacket.Hello hello) {
        if (hello.modules().isEmpty()) return "keine";
        List<String> parts = new ArrayList<>();
        for (CorePacket.ModuleVersion m : hello.modules()) parts.add(m.id() + " " + m.version());
        return String.join(", ", parts);
    }

    private static ClientInfo clientInfo(HelloInfo info) {
        List<ClientInfo.ClientModule> modules = info.hello().modules().stream().map(m -> new ClientInfo.ClientModule(m.id(), m.version())).toList();
        return new ClientInfo(info.hello().protocol(), info.hello().coreVersion(), modules, info.at());
    }
}
