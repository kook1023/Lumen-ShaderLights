package dev.shadertorches;

import dev.shadertorches.command.TorchCommands;
import dev.shadertorches.config.TorchConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShaderTorches implements ClientModInitializer {
    public static final String MOD_ID = "shadertorches";
    public static final Logger LOGGER = LoggerFactory.getLogger("Shader Torches");

    @Override
    public void onInitializeClient() {
        TorchConfig.load();

        // Re-read the JSON whenever you join a world, so hand edits apply after a rejoin.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> TorchConfig.load());

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> TorchCommands.register(dispatcher));
    }
}
