package dev.shadertorches.config.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

/** Picks the right config screen depending on whether Cloth Config is present. */
public final class ConfigScreens {
    private ConfigScreens() {
    }

    public static Screen create(Screen parent) {
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return ClothConfigScreen.create(parent);
        }
        return new MissingClothScreen(parent);
    }
}
