package dev.shadertorches.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.shadertorches.config.TorchConfig;
import dev.shadertorches.config.TorchMatcher;
import dev.shadertorches.config.gui.ConfigScreens;
import dev.shadertorches.iris.IrisBridge;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** /shadertorches reload | addheld | status | config */
public final class TorchCommands {
    private TorchCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("shadertorches")
                .then(ClientCommandManager.literal("reload")
                        .executes(ctx -> reload(ctx.getSource())))
                .then(ClientCommandManager.literal("addheld")
                        .executes(ctx -> addHeld(ctx.getSource())))
                .then(ClientCommandManager.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(ClientCommandManager.literal("config")
                        .executes(ctx -> openConfig(ctx.getSource()))));
    }

    private static int reload(FabricClientCommandSource src) {
        TorchConfig.load();
        src.sendFeedback(Component.literal("Shader Torches: config reloaded."));
        return 1;
    }

    private static int addHeld(FabricClientCommandSource src) {
        ItemStack stack = src.getPlayer().getMainHandItem();
        if (stack.isEmpty()) {
            src.sendError(Component.literal("Shader Torches: hold the item you want to add in your main hand."));
            return 0;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (TorchConfig.addItem(id)) {
            src.sendFeedback(Component.literal("Shader Torches: added " + id + " - it works right away."));
            return 1;
        }
        src.sendError(Component.literal("Shader Torches: " + id + " is already in the list."));
        return 0;
    }

    private static int status(FabricClientCommandSource src) {
        TorchConfig cfg = TorchConfig.get();
        src.sendFeedback(Component.literal("Shader Torches: " + (cfg.enabled ? "enabled" : "disabled")));
        src.sendFeedback(Component.literal("Iris hook: " + IrisBridge.describeHook()));
        src.sendFeedback(Component.literal("Pack knows: " + IrisBridge.describePack()));
        LocalPlayer player = src.getPlayer();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            String name = stack.isEmpty() ? "(empty)" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            src.sendFeedback(Component.literal(" " + hand + ": " + name
                    + " -> light level " + TorchMatcher.matchFor(stack).level()));
        }
        return 1;
    }

    private static int openConfig(FabricClientCommandSource src) {
        Minecraft mc = src.getClient();
        // Queue it: the chat screen closes itself right after a command runs and would close ours too.
        mc.tell(() -> mc.setScreen(ConfigScreens.create(null)));
        return 1;
    }
}
