package io.github.kook1023.lumen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.kook1023.lumen.config.LumenConfig;
import io.github.kook1023.lumen.config.LightMatcher;
import io.github.kook1023.lumen.config.gui.ConfigScreens;
import io.github.kook1023.lumen.iris.IrisBridge;
import io.github.kook1023.lumen.iris.RenderedItemBridge;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side commands (Heady's Lumen: ShaderLights):
 * <pre>
 *   /lumen reload        re-read config/headys_lumen.json
 *   /lumen addheld       add the item in your main hand to your list
 *   /lumen removeheld    remove the item in your main hand from your list
 *   /lumen status        what the mod, Iris and the shader pack are doing right now
 *   /lumen config        open the config screen on tab 1 (General)
 *   /lumen config 1-5    open it on tab 1 (General), 2 (Light items), 3 (Handheld), 4 (Placed blocks) or 5 (Flicker)
 * </pre>
 */
public final class LumenCommands {
    private static final String PREFIX = "Lumen: ";
    private static final int MAX_LISTED_ITEMS = 12;

    private LumenCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("lumen")
                .then(ClientCommandManager.literal("reload")
                        .executes(ctx -> reload(ctx.getSource())))
                .then(ClientCommandManager.literal("addheld")
                        .executes(ctx -> addHeld(ctx.getSource())))
                .then(ClientCommandManager.literal("removeheld")
                        .executes(ctx -> removeHeld(ctx.getSource())))
                .then(ClientCommandManager.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(ClientCommandManager.literal("config")
                        .executes(ctx -> openConfig(ctx.getSource(), 1))
                        .then(ClientCommandManager.argument("tab", IntegerArgumentType.integer(1, 5))
                                .executes(ctx -> openConfig(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "tab"))))));
    }

    private static int reload(FabricClientCommandSource src) {
        LumenConfig.load();
        src.sendFeedback(Component.literal(PREFIX + "config reloaded."));
        return 1;
    }

    private static int addHeld(FabricClientCommandSource src) {
        ItemStack stack = src.getPlayer().getMainHandItem();
        if (stack.isEmpty()) {
            src.sendError(Component.literal(PREFIX + "hold the item you want to add in your main hand."));
            return 0;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (LumenConfig.addItem(id)) {
            src.sendFeedback(Component.literal(PREFIX + "added " + id
                    + " - it works right away. Use /lumen config 2 to set its colour."));
            return 1;
        }
        src.sendError(Component.literal(PREFIX + id + " is already in your list."));
        return 0;
    }

    private static int removeHeld(FabricClientCommandSource src) {
        ItemStack stack = src.getPlayer().getMainHandItem();
        if (stack.isEmpty()) {
            src.sendError(Component.literal(PREFIX + "hold the item you want to remove in your main hand."));
            return 0;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (LumenConfig.removeItem(id)) {
            src.sendFeedback(Component.literal(PREFIX + "removed " + id + " from your list."
                    + (LightMatcher.matchFor(stack).level() > 0 ? " It still lights up: "
                    + whyItStillGlows(stack) : "")));
            return 1;
        }
        src.sendError(Component.literal(PREFIX + id + " is not in your list by itself. " + whyItStillGlows(stack)));
        return 0;
    }

    /** Explains what (other than its own entry) lights an item, without changing anything. */
    private static String whyItStillGlows(ItemStack stack) {
        String rule = LightMatcher.listEntryFor(stack);
        if (rule != null) {
            return "It matches the entry \"" + rule + "\" - remove or change that one in /lumen config 2.";
        }
        if (LightMatcher.matchFor(stack).level() > 0) {
            return "Auto-detect lights it; to switch it off, /lumen addheld it and set Intensity to 0.";
        }
        return "Nothing in your list lights it.";
    }

    private static int status(FabricClientCommandSource src) {
        LumenConfig cfg = LumenConfig.get();
        src.sendFeedback(Component.literal(PREFIX + (cfg.enabled ? "enabled" : "disabled")));
        src.sendFeedback(Component.literal("Iris hook: " + IrisBridge.describeHook()));
        src.sendFeedback(Component.literal("Pack mode: " + IrisBridge.describePackMode()));

        // What the SHADER PACK knows by itself (from its item.properties)
        src.sendFeedback(Component.literal("Pack knows (item=id): " + IrisBridge.describePackItems()));

        // What YOU added (from the config) - kept separate on purpose
        src.sendFeedback(Component.literal("Your items (" + cfg.items.size() + "):"
                + (cfg.items.isEmpty() ? " none - add some with /lumen addheld or /lumen config 2" : "")));
        int shown = 0;
        for (LumenConfig.Entry e : cfg.items) {
            if (shown++ == MAX_LISTED_ITEMS) {
                src.sendFeedback(Component.literal("  ...and " + (cfg.items.size() - MAX_LISTED_ITEMS) + " more"));
                break;
            }
            src.sendFeedback(Component.literal("  " + describeEntry(e)));
        }
        src.sendFeedback(Component.literal("Auto-detect: " + (cfg.matchTorchSuffix
                ? "on (modded soul/redstone/copper torches and lanterns as their vanilla light; other *torch level "
                        + cfg.defaultLightLevel + ")"
                : "off")));

        LocalPlayer player = src.getPlayer();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            String name = stack.isEmpty() ? "(empty)" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            LightMatcher.Match hm = LightMatcher.matchFor(stack);
            src.sendFeedback(Component.literal(" " + hand + ": " + name
                    + " -> light level " + hm.level()
                    + (hm.flickers() ? ", flickers (speed " + Math.round(hm.flickerSpeed()) + ", strength "
                            + Math.round(hm.flickerStrength() * 100) + "%)" : "")));
        }
        src.sendFeedback(Component.literal("Flicker: " + switch (cfg.flickerPreset) {
            case ALL -> "all lights";
            case IMMERSION -> "immersion mode";
            case GROUPS -> "groups";
        } + ", default speed " + cfg.defaultFlickerSpeed + ", strength " + cfg.defaultFlickerStrength + "% (/lumen config 5)"));
        src.sendFeedback(Component.literal("Sent to shader: " + IrisBridge.describeSent()));
        src.sendFeedback(Component.literal("Item look: " + RenderedItemBridge.describe()));
        src.sendFeedback(Component.literal("Placed blocks: " + io.github.kook1023.lumen.iris.PlacedLights.describe()));
        for (String pose : io.github.kook1023.lumen.iris.PlacedLights.poses()) {
            src.sendFeedback(Component.literal("  " + pose));
        }
        return 1;
    }

    private static String describeEntry(LumenConfig.Entry e) {
        StringBuilder sb = new StringBuilder(e.id);
        sb.append(e.level == 0 ? "  OFF (no light)" : "  level " + e.level);
        sb.append(e.color.isEmpty() ? ", pack colour" : ", " + e.color);
        if (!e.pretendToBe.isEmpty()) {
            sb.append(", as ").append(e.pretendToBe.replace("minecraft:", ""));
        }
        if (e.level > 0) {
            switch (e.flickerMode()) {
                case ON -> sb.append(", flicker speed ").append(e.flickerSpeed).append(" strength ")
                        .append(e.flickerStrength).append('%');
                case OFF -> sb.append(", no flicker");
                default -> { } // follows the Flicker tab
            }
        }
        return sb.toString();
    }

    /** @param tab 1 = General, 2 = Light items, 3 = Handheld, 4 = Placed blocks, 5 = Flicker */
    private static int openConfig(FabricClientCommandSource src, int tab) {
        Minecraft mc = src.getClient();
        // Queue it: the chat screen closes itself right after a command runs and would close ours too.
        mc.tell(() -> mc.setScreen(ConfigScreens.create(null, tab)));
        return 1;
    }
}
