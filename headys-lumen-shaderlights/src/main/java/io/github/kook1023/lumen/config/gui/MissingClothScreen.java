package io.github.kook1023.lumen.config.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Shown instead of the editor when Cloth Config is not installed. */
public final class MissingClothScreen extends Screen {
    private final Screen parent;

    public MissingClothScreen(Screen parent) {
        super(Component.translatable("headys_lumen.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 100, height - 40, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 30, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("headys_lumen.config.missing_cloth.1"),
                width / 2, height / 2 - 12, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("headys_lumen.config.missing_cloth.2"),
                width / 2, height / 2 + 2, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
