package dev.shadertorches.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.shadertorches.config.gui.ConfigScreens;

/** Adds the "Configure" button for Shader Torches in the Mod Menu mod list. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> ConfigScreens.create(parent, false);
    }
}
