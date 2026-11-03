package de.peakverse.core.mod.client;

import de.peakverse.core.client.ClientSession;
import de.peakverse.core.client.PackReceiver;
import de.peakverse.core.client.WebTunnel;
import de.peakverse.core.mod.PeakCoreMod;
import de.peakverse.core.mod.PeakCoreSession;
import de.peakverse.core.mod.RawPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-Einstieg von PeakCore. Andere Mods (PeakSound, TexturePeak, ...) holen sich die Sitzung mit {@link PeakCoreSession#get()} und melden dort ihr Modul an:
 * <pre>{@code PeakCoreSession.get().registerModule("peaksound", VERSION, payload -> ...);}</pre>
 * Am besten in ihrem eigenen Client-Einstieg, solange die Mods starten; so steht ihre ID schon im Hello, das beim Beitritt geschickt wird.
 */
public final class PeakCoreClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PeakCore");

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

    private static void tell(String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }

    /** Zeigt den Link klickbar im Chat und versucht, den Browser selbst zu oeffnen. */
    private static void showLink(String url) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        java.net.URI uri = java.net.URI.create(url);
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[PeakVerse] Web-Oberflaeche: ").append(Component.literal("[Im Browser oeffnen]")
                    .withStyle(s -> s.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(uri)).withColor(0x55FF55))));
        }
        try {
            net.minecraft.util.Util.getPlatform().openUri(uri);
        } catch (RuntimeException e) {
            LOG.warn("Browser konnte nicht automatisch geoeffnet werden: {}", e.toString());
        }
    }

    @Override
    public void onInitializeClient() {
        ClientSession session = new ClientSession(PeakCoreMod.VERSION, new FabricTransport(), System::currentTimeMillis);
        PeakCoreSession.install(session);
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
        PackReceiver packs = new PackReceiver(new PackClient());
        session.addListener(new ClientSession.Listener() {
            @Override
            public void onPackOffer(de.peakverse.core.protocol.CorePacket.PackOffer offer) {
                packs.onOffer(offer);
            }
        });
        if (Boolean.getBoolean("peakcore.probe")) ProbeModule.register(session);
        ClientPlayNetworking.registerGlobalReceiver(RawPayload.TYPE,
                (payload, context) -> context.client().execute(() -> session.onFrame(payload.data())));
        ModUpdateClient modUpdate = new ModUpdateClient();
        modUpdate.start();
        // Web-Tunnel: die Web-Oberflaeche im Browser, ueber die Minecraft-Verbindung (kein offener Port noetig)
        WebTunnel tunnel = new WebTunnel(session, command -> net.minecraft.client.Minecraft.getInstance().execute(command), System::currentTimeMillis,
                url -> net.minecraft.client.Minecraft.getInstance().execute(() -> showLink(url)),
                message -> net.minecraft.client.Minecraft.getInstance().execute(() -> tell("[PeakVerse] " + message)));
        session.addListener(tunnel);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            session.onJoin();
            packs.onJoin();
            modUpdate.onJoin(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> session.onDisconnect());
        // Admin-Taste (ohne Vorbelegung, im Steuerungs-Menue zuweisbar): fordert den Einmal-Link der Web-Oberflaeche an. Der Server prueft Operator-Recht selbst.
        KeyMapping webKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peakverse.web", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            session.tick();
            packs.tick();
            tunnel.tick();
            while (webKey.consumeClick()) {
                if (client.player != null) tunnel.open();
            }
        });

        // Diagnose: /peakcorestatus zeigt, was die Mod ueber den Server weiss
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("peakcorestatus").executes(context -> {
                    context.getSource().sendFeedback(Component.literal("PeakCore " + PeakCoreMod.VERSION + " (Protokoll "
                            + de.peakverse.core.protocol.CoreProtocol.VERSION + ")\n" + session.debug() + "\n" + packs.debug() + "\n" + modUpdate.debug() + "\n" + tunnel.debug()));
                    return 1;
                })));
        // Web-Oberflaeche im Browser oeffnen (ueber den Tunnel)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("peakcoreweb").executes(context -> {
                    tunnel.open();
                    context.getSource().sendFeedback(Component.literal("[PeakVerse] Web-Oberflaeche wird ueber den Server geoeffnet ..."));
                    return 1;
                })));
        // Mod-Update: laedt neuere Versionen der PeakVerse-Mods aus dem Release-Repo; getauscht wird beim Beenden des Spiels
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("peakcoreupdate").executes(context -> {
                    context.getSource().sendFeedback(Component.literal("[PeakVerse] " + modUpdate.command(net.minecraft.client.Minecraft.getInstance())));
                    return 1;
                })));
    }
}
