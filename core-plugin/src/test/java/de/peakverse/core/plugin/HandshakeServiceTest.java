package de.peakverse.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.peakverse.core.api.ClientInfo;
import de.peakverse.core.protocol.CorePacket;
import de.peakverse.core.protocol.CorePacketCodec;
import de.peakverse.core.protocol.CoreProtocol;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HandshakeServiceTest {
    private record Sent(String player, CorePacket.Clientbound packet) {}

    /** Testumgebung: Spieler sind einfach Namen. */
    private static final class FakeEnv implements HandshakeService.Env<String> {
        final Set<String> channelRegistered = new HashSet<>();
        final Set<String> admins = new HashSet<>();
        final List<Sent> sent = new ArrayList<>();
        final List<String> incompatible = new ArrayList<>();
        final List<String> done = new ArrayList<>();
        final List<ClientInfo> doneInfos = new ArrayList<>();
        List<CorePacket.ModuleEntry> modules = List.of(new CorePacket.ModuleEntry("peaksound", "PeakSound", "0.1.0"));
        long now = 1_000_000L;

        @Override public UUID id(String player) { return UUID.nameUUIDFromBytes(player.getBytes(StandardCharsets.UTF_8)); }
        @Override public String name(String player) { return player; }
        @Override public boolean isAdmin(String player) { return admins.contains(player); }
        @Override public boolean channelRegistered(String player) { return channelRegistered.contains(player); }
        @Override public void send(String player, CorePacket.Clientbound packet) { sent.add(new Sent(player, packet)); }
        @Override public void notifyIncompatible(String player, int clientProtocol) { incompatible.add(player + ":" + clientProtocol); }
        @Override public void handshakeDone(String player, ClientInfo client) { done.add(player); doneInfos.add(client); }
        @Override public long now() { return now; }
        @Override public String coreVersion() { return "0.1.0"; }
        @Override public int apiVersion() { return 1; }
        @Override public List<CorePacket.ModuleEntry> serverModules() { return modules; }

        List<CorePacket.HelloAck> acks() {
            return sent.stream().map(Sent::packet).filter(p -> p instanceof CorePacket.HelloAck).map(p -> (CorePacket.HelloAck) p).toList();
        }

        List<CorePacket.AdminState> adminStates() {
            return sent.stream().map(Sent::packet).filter(p -> p instanceof CorePacket.AdminState).map(p -> (CorePacket.AdminState) p).toList();
        }
    }

    private FakeEnv env;
    private HandshakeService<String> service;

    @BeforeEach
    void setUp() {
        env = new FakeEnv();
        Logger log = Logger.getLogger("HandshakeServiceTest");
        log.setLevel(Level.OFF);
        service = new HandshakeService<>(env, log);
    }

    private static byte[] hello(int protocol, CorePacket.ModuleVersion... modules) {
        return CorePacketCodec.encode(new CorePacket.Hello(protocol, "0.1.0", List.of(modules)));
    }

    private static byte[] goodHello() {
        return hello(CoreProtocol.VERSION, new CorePacket.ModuleVersion("peaksound", "0.1.0"));
    }

    @Test
    void answersOnlyOnceTheClientRegisteredTheChannel() {
        service.onCorePayload("Alice", goodHello());
        assertTrue(service.isModded("Alice"), "Handshake gilt ab dem Hello; Pakete an Module duerfen schon durch");
        assertTrue(env.sent.isEmpty(), "Paper wuerde die Antwort vor der Kanal-Anmeldung verwerfen");
        assertTrue(env.done.isEmpty());

        env.channelRegistered.add("Alice");
        service.onChannelRegistered("Alice");
        assertEquals(1, env.acks().size());
        assertEquals(List.of("Alice"), env.done);

        service.onChannelRegistered("Alice"); // eine zweite Anmeldung loest keine zweite Antwort aus
        assertEquals(1, env.acks().size());
        assertEquals(1, env.done.size());
    }

    @Test
    void answersImmediatelyIfTheChannelIsAlreadyRegistered() {
        env.channelRegistered.add("Alice");
        service.onCorePayload("Alice", goodHello());
        CorePacket.HelloAck ack = env.acks().get(0);
        assertTrue(ack.compatible());
        assertEquals(CoreProtocol.VERSION, ack.protocol());
        assertEquals("0.1.0", ack.coreVersion());
        assertEquals(1, ack.apiVersion());
        assertEquals(1_000_000L, ack.serverTimeMillis());
        assertEquals(List.of(new CorePacket.ModuleEntry("peaksound", "PeakSound", "0.1.0")), ack.modules());
        assertFalse(ack.admin());
    }

    @Test
    void ackCarriesTheAdminStatus() {
        env.channelRegistered.addAll(List.of("Op", "Normal"));
        env.admins.add("Op");
        service.onCorePayload("Op", goodHello());
        service.onCorePayload("Normal", goodHello());
        assertTrue(env.acks().get(0).admin());
        assertFalse(env.acks().get(1).admin());
    }

    @Test
    void incompatibleModsGetANoticeAndAreNeverTreatedAsModded() {
        env.channelRegistered.add("Alice");
        service.onCorePayload("Alice", hello(CoreProtocol.VERSION + 1));
        assertFalse(service.isModded("Alice"));
        assertTrue(service.client("Alice").isEmpty());
        assertEquals(List.of("Alice:" + (CoreProtocol.VERSION + 1)), env.incompatible);
        CorePacket.HelloAck ack = env.acks().get(0);
        assertFalse(ack.compatible());
        assertFalse(ack.admin(), "eine inkompatible Mod bekommt keine Admin-Rechte gemeldet");
        assertTrue(ack.modules().isEmpty());
        assertTrue(env.done.isEmpty(), "kein Handshake-Event");
    }

    @Test
    void anUpdatedModBecomesModdedAfterAMismatch() {
        env.channelRegistered.add("Alice");
        service.onCorePayload("Alice", hello(99));
        assertFalse(service.isModded("Alice"));
        service.onCorePayload("Alice", goodHello()); // Spieler hat die Mod gewechselt und ist neu verbunden
        assertTrue(service.isModded("Alice"));
    }

    @Test
    void aRepeatedHelloIsAnsweredAgain() {
        env.channelRegistered.add("Alice");
        service.onCorePayload("Alice", goodHello());
        service.onCorePayload("Alice", goodHello()); // Client neu gestartet, Antwort und Event muessen erneut kommen
        assertEquals(2, env.acks().size());
        assertEquals(2, env.done.size());
    }

    @Test
    void helloFloodingIsCapped() {
        env.channelRegistered.add("Mallory");
        for (int i = 0; i < HandshakeService.MAX_HELLOS + 50; i++) service.onCorePayload("Mallory", goodHello());
        assertEquals(HandshakeService.MAX_HELLOS, env.acks().size());
    }

    @Test
    void adminChangesAreReportedOnceAndOnlyToModdedPlayers() {
        env.channelRegistered.addAll(List.of("Alice", "Bob"));
        service.onCorePayload("Alice", goodHello()); // Bob hat keine Mod
        List<String> online = List.of("Alice", "Bob");

        service.checkAdmins(online);
        assertTrue(env.adminStates().isEmpty(), "keine Aenderung, nichts zu melden");

        env.admins.add("Alice");
        env.admins.add("Bob");
        service.checkAdmins(online);
        assertEquals(List.of(new CorePacket.AdminState(true)), env.adminStates());
        assertEquals("Alice", env.sent.get(env.sent.size() - 1).player());

        service.checkAdmins(online); // unveraendert: nicht noch einmal
        assertEquals(1, env.adminStates().size());

        env.admins.remove("Alice"); // /deop
        service.checkAdmins(online);
        assertEquals(List.of(new CorePacket.AdminState(true), new CorePacket.AdminState(false)), env.adminStates());
    }

    @Test
    void noAdminStateBeforeTheAckWasSent() {
        service.onCorePayload("Alice", goodHello()); // Kanal noch nicht angemeldet: Ack steht aus
        env.admins.add("Alice");
        service.checkAdmins(List.of("Alice"));
        assertTrue(env.adminStates().isEmpty(), "der Admin-Status steht dann gleich im Ack");
        env.channelRegistered.add("Alice");
        service.onChannelRegistered("Alice");
        assertTrue(env.acks().get(0).admin());
    }

    @Test
    void malformedAndWrongDirectionPacketsAreIgnored() {
        env.channelRegistered.add("Mallory");
        service.onCorePayload("Mallory", new byte[0]);
        service.onCorePayload("Mallory", new byte[] {99, 1, 2});
        service.onCorePayload("Mallory", new byte[] {1}); // Hello ohne Inhalt
        service.onCorePayload("Mallory", CorePacketCodec.encode(new CorePacket.AdminState(true))); // Server-Paket vom Client
        service.onCorePayload("Mallory", null);
        assertFalse(service.isModded("Mallory"));
        assertTrue(env.sent.isEmpty());
        assertTrue(service.describe("Mallory").startsWith("keine Mod gemeldet"));
    }

    @Test
    void clientInfoListsTheModsModules() {
        env.channelRegistered.add("Alice");
        service.onCorePayload("Alice", hello(CoreProtocol.VERSION, new CorePacket.ModuleVersion("peaksound", "0.1.0"), new CorePacket.ModuleVersion("texturepeak", "2.0")));
        ClientInfo info = service.client("Alice").orElseThrow();
        assertEquals(CoreProtocol.VERSION, info.coreProtocol());
        assertEquals("0.1.0", info.coreVersion());
        assertTrue(info.hasModule("peaksound"));
        assertTrue(info.hasModule("texturepeak"));
        assertFalse(info.hasModule("other"));
        assertEquals("2.0", info.module("texturepeak").orElseThrow().version());
        assertEquals(1_000_000L, info.helloAtMillis());
        assertEquals(info, env.doneInfos.get(0));
    }

    @Test
    void quittingForgetsEverything() {
        env.channelRegistered.add("Alice");
        service.onCorePayload("Alice", goodHello());
        service.onQuit("Alice");
        assertFalse(service.isModded("Alice"));
        assertTrue(service.client("Alice").isEmpty());
        env.admins.add("Alice");
        service.checkAdmins(List.of("Alice"));
        assertTrue(env.adminStates().isEmpty());
        // nach einem Wiederbeitritt beginnt der Hello-Zaehler von vorn
        for (int i = 0; i < HandshakeService.MAX_HELLOS + 1; i++) service.onCorePayload("Alice", goodHello());
        service.onQuit("Alice");
        service.onCorePayload("Alice", goodHello());
        assertTrue(service.isModded("Alice"));
    }

    @Test
    void tooManyServerModulesAreTruncatedInTheAck() {
        List<CorePacket.ModuleEntry> many = new ArrayList<>();
        for (int i = 0; i < CoreProtocol.MAX_MODULES + 5; i++) many.add(new CorePacket.ModuleEntry("module" + i, "M" + i, "1"));
        env.modules = many;
        env.channelRegistered.add("Alice");
        service.onCorePayload("Alice", goodHello());
        assertEquals(CoreProtocol.MAX_MODULES, env.acks().get(0).modules().size());
    }

    @Test
    void describeShowsState() {
        service.onCorePayload("Alice", goodHello());
        env.now += 5_000;
        String pending = service.describe("Alice");
        assertTrue(pending.contains("kompatibel") && pending.contains("peaksound 0.1.0") && pending.contains("vor 5 s") && pending.contains("wartet"), pending);
        env.channelRegistered.add("Alice");
        service.onChannelRegistered("Alice");
        assertFalse(service.describe("Alice").contains("wartet"));
        service.onCorePayload("Bob", hello(77));
        assertTrue(service.describe("Bob").contains("NICHT kompatibel"), service.describe("Bob"));
        assertTrue(service.describe("Nobody").startsWith("keine Mod gemeldet"));
    }
}
