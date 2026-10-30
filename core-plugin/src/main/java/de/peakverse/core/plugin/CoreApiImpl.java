package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.ModuleMessageHandler;
import de.peakverse.core.api.ModuleRegistration;
import de.peakverse.core.api.PeakCoreApi;
import de.peakverse.core.protocol.Direction;
import de.peakverse.core.protocol.FrameCodec;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Umsetzung der oeffentlichen API; verbindet Registry und Kanal mit dem Plugin-Log. */
final class CoreApiImpl implements PeakCoreApi {
    private final String coreVersion;
    private final ModuleRegistry registry;
    private final ChannelService channel;
    private final Logger log;

    CoreApiImpl(String coreVersion, ModuleRegistry registry, ChannelService channel, Logger log) {
        this.coreVersion = coreVersion;
        this.registry = registry;
        this.channel = channel;
        this.log = log;
    }

    @Override
    public String coreVersion() {
        return coreVersion;
    }

    @Override
    public ModuleRegistration registerModule(Plugin owner, ModuleInfo info) {
        if (owner == null) throw new IllegalArgumentException("owner fehlt");
        String ownerName = owner.getName();
        registry.register(ownerName, info);
        log.info("Modul angemeldet: " + info.displayName() + " " + info.version() + " (" + info.id() + ", Plugin " + ownerName + ")");
        return new Registration(info, ownerName);
    }

    @Override
    public List<ModuleInfo> modules() {
        return registry.entries().stream().map(ModuleRegistry.Entry::info).toList();
    }

    @Override
    public Optional<ModuleInfo> module(String id) {
        return registry.find(id).map(ModuleRegistry.Entry::info);
    }

    private final class Registration implements ModuleRegistration {
        private final ModuleInfo info;
        private final String owner;

        Registration(ModuleInfo info, String owner) {
            this.info = info;
            this.owner = owner;
        }

        @Override
        public ModuleInfo info() {
            return info;
        }

        @Override
        public void setMessageHandler(ModuleMessageHandler handler) {
            // nur solange dieses Modul noch seinem Plugin gehoert (nach unregister() ist die Registrierung wirkungslos)
            if (!registry.find(info.id()).map(e -> e.owner().equals(owner)).orElse(false)) return;
            channel.router().setHandler(info.id(), handler == null ? null : handler::onMessage);
        }

        @Override
        public boolean canSend(Player player) {
            return channel.canSend(player);
        }

        @Override
        public boolean send(Player player, byte[] payload) {
            if (!registry.find(info.id()).map(e -> e.owner().equals(owner)).orElse(false)) return false;
            return channel.sendToModule(info.id(), player, payload);
        }

        @Override
        public int maxPayloadToClient() {
            return FrameCodec.maxModulePayload(info.id(), Direction.CLIENTBOUND);
        }

        @Override
        public int maxPayloadFromClient() {
            return FrameCodec.maxModulePayload(info.id(), Direction.SERVERBOUND);
        }

        @Override
        public void unregister() {
            if (registry.unregister(info.id(), owner)) {
                channel.router().removeHandler(info.id());
                log.info("Modul abgemeldet: " + info.displayName() + " (" + info.id() + ")");
            }
        }
    }
}
