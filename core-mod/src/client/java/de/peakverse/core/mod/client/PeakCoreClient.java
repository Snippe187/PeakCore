package de.peakverse.core.mod.client;

import de.peakverse.core.client.ClientSession;
import de.peakverse.core.mod.PeakCoreMod;
import de.peakverse.core.mod.RawPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-Einstieg von PeakCore. Andere Mods (PeakSound, TexturePeak, ...) holen sich die {@link #session() Sitzung} und melden dort ihr Modul an:
 * <pre>{@code PeakCoreClient.session().registerModule("peaksound", VERSION, payload -> ...);}</pre>
 * Am besten in ihrem eigenen Client-Einstieg, solange die Mods starten; so steht ihre ID schon im Hello, das beim Beitritt geschickt wird.
 */
public final class PeakCoreClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PeakCore");

    /** Die Sitzung wird beim ersten Zugriff angelegt, egal welche Mod zuerst startet. */
    private static final class Holder {
        static final ClientSession SESSION = new ClientSession(PeakCoreMod.VERSION, new FabricTransport(), System::currentTimeMillis);
    }

    /** Verbindung zum Server ueber das Fabric-Netzwerk. */
    private static final class FabricTransport implements ClientSession.Transport {
        @Override
        public boolean canSend() {
            return ClientPlayNetworking.canSend(RawPayload.TYPE);
        }

        @Override
        public void send(byte[] frame) {
            ClientPlayNetworking.send(new RawPayload(frame));
        }
    }

    /** Die gemeinsame Sitzung dieser Mod mit dem Server (Handshake, Admin-Status, Modul-Routing). */
    public static ClientSession session() {
        return Holder.SESSION;
    }

    @Override
    public void onInitializeClient() {
        ClientSession session = session();
        session.addListener(new ClientSession.Listener() {
            @Override
            public void onStateChanged(ClientSession.State previous, ClientSession.State now) {
                LOG.info("Handshake-Zustand {} -> {}{}", previous, now, now == ClientSession.State.ACTIVE
                        ? " (Server-Core " + session.serverCoreVersion() + ", Admin: " + session.isAdmin() + ", Module: " + session.serverModules() + ")" : "");
            }

            @Override
            public void onAdminChanged(boolean admin) {
                LOG.info("Admin-Status geaendert: {}", admin);
            }
        });
        if (Boolean.getBoolean("peakcore.probe")) ProbeModule.register(session);
        ClientPlayNetworking.registerGlobalReceiver(RawPayload.TYPE,
                (payload, context) -> context.client().execute(() -> session.onFrame(payload.data())));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> session.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> session.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(client -> session.tick());

        // Diagnose: /peakcorestatus zeigt, was die Mod ueber den Server weiss
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("peakcorestatus").executes(context -> {
                    context.getSource().sendFeedback(Component.literal("PeakCore " + PeakCoreMod.VERSION + " (Protokoll "
                            + de.peakverse.core.protocol.CoreProtocol.VERSION + ")\n" + session.debug()));
                    return 1;
                })));
    }
}
