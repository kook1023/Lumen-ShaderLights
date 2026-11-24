package io.github.kook1023.lumen.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.kook1023.lumen.config.gui.ConfigScreens;

/** Adds the "Configure" button for Heady's Lumen: ShaderLights in the Mod Menu mod list. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> ConfigScreens.create(parent, false);
    }
}
