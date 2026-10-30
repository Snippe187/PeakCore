package de.peakverse.core.plugin;

import de.peakverse.core.api.ModuleInfo;
import de.peakverse.core.api.ModuleRegistration;
import de.peakverse.core.api.PeakCoreApi;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/** Umsetzung der oeffentlichen API; verbindet die Registry mit dem Plugin-Log. */
final class CoreApiImpl implements PeakCoreApi {
    private final String coreVersion;
    private final ModuleRegistry registry;
    private final Logger log;

    CoreApiImpl(String coreVersion, ModuleRegistry registry, Logger log) {
        this.coreVersion = coreVersion;
        this.registry = registry;
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
        return new ModuleRegistration() {
            @Override
            public ModuleInfo info() {
                return info;
            }

            @Override
            public void unregister() {
                if (registry.unregister(info.id(), ownerName)) log.info("Modul abgemeldet: " + info.displayName() + " (" + info.id() + ")");
            }
        };
    }

    @Override
    public List<ModuleInfo> modules() {
        return registry.entries().stream().map(ModuleRegistry.Entry::info).toList();
    }

    @Override
    public Optional<ModuleInfo> module(String id) {
        return registry.find(id).map(ModuleRegistry.Entry::info);
    }
}
