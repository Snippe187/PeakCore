package de.peakverse.core.mod.client;

import de.peakverse.core.client.ClientSession;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-Modul fuer den Dev-Client (nur mit {@code -Dpeakcore.probe=true}): Gegenstueck zum Plugin {@code PeakCoreProbe}.
 * Sendet nach dem Handshake ein "ping" und protokolliert alles, was das Plugin zurueckschickt.
 */
final class ProbeModule {
    private static final Logger LOG = LoggerFactory.getLogger("PeakCore/Probe");

    private ProbeModule() {}

    static void register(ClientSession session) {
        ClientSession.ModuleChannel channel = session.registerModule("probe", "0.1.0",
                payload -> LOG.info("Probe: {} Bytes vom Server empfangen: \"{}\"", payload.length, new String(payload, StandardCharsets.UTF_8)));
        session.addListener(new ClientSession.Listener() {
            @Override
            public void onStateChanged(ClientSession.State previous, ClientSession.State now) {
                if (now == ClientSession.State.ACTIVE && channel.send("ping".getBytes(StandardCharsets.UTF_8))) LOG.info("Probe: ping gesendet");
            }
        });
    }
}
