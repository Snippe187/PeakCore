package de.peakverse.core.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

/** Gemeinsamer Einstiegspunkt: registriert die Netzwerk-Payloads in beide Richtungen. */
public final class PeakCoreMod implements ModInitializer {
    public static final String MOD_ID = "peakcore";

    /** Version dieser Mod (aus der fabric.mod.json), wie sie beim Handshake an den Server geht. */
    public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
    }
}
