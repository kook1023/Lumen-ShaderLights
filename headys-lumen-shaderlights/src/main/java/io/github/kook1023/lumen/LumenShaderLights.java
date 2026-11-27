package io.github.kook1023.lumen;

import io.github.kook1023.lumen.command.LumenCommands;
import io.github.kook1023.lumen.config.LumenConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Heady's Lumen: ShaderLights - client entry point. */
public final class LumenShaderLights implements ClientModInitializer {
    public static final String MOD_ID = "headys_lumen";
    public static final Logger LOGGER = LoggerFactory.getLogger("Lumen ShaderLights");

    @Override
    public void onInitializeClient() {
        // Placed lights depend on the config: re-apply them (on the render thread) after every load or save.
        LumenConfig.onChange(() -> net.minecraft.client.Minecraft.getInstance()
                .execute(io.github.kook1023.lumen.iris.PlacedLights::refresh));
        LumenConfig.load();

        // Re-read the JSON whenever you join a world, so hand edits apply after a rejoin.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> LumenConfig.load());

        // The light table texture placed lights use in packs that read it (the PhotonFork)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(
                client -> io.github.kook1023.lumen.iris.LightTable.register());

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> LumenCommands.register(dispatcher));
    }
}
