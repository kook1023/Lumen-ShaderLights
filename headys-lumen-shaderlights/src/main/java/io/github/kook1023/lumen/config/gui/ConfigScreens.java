package io.github.kook1023.lumen.config.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

/** Picks the right config screen depending on whether Cloth Config is present. */
public final class ConfigScreens {
    private ConfigScreens() {
    }

    /** @param tab 1 = General, 2 = Light items, 3 = Placed blocks */
    public static Screen create(Screen parent, int tab) {
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return ClothConfigScreen.create(parent, tab);
        }
        return new MissingClothScreen(parent);
    }
}
