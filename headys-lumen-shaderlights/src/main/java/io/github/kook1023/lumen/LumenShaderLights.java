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
        LumenConfig.load();

        // Re-read the JSON whenever you join a world, so hand edits apply after a rejoin.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> LumenConfig.load());

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> LumenCommands.register(dispatcher));
    }
}
