package io.github.kook1023.lumen.config.gui;

import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A read-only row for the config screen that draws a coloured box. The colour is asked for every frame, so the box
 * follows the Hue / Saturation / Intensity sliders live. Modelled on Cloth Config's own TextListEntry.
 */
public final class ColorPreviewEntry extends TooltipListEntry<Object> {
    private final IntSupplier boxColor;
    private final Supplier<Component> caption;

    /**
     * @param boxColor 0xRRGGBB to fill the box with, or -1 for "no colour chosen"
     * @param caption  text drawn inside the box
     */
    public ColorPreviewEntry(Component fieldName, IntSupplier boxColor, Supplier<Component> caption) {
        super(fieldName, null);
        this.boxColor = boxColor;
        this.caption = caption;
    }

    @Override
    public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
                       int mouseX, int mouseY, boolean isHovered, float delta) {
        super.render(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
        Font font = Minecraft.getInstance().font;

        // label on the left, like every other row
        graphics.drawString(font, getFieldName().getVisualOrderText(), x, y + 6, getPreferredTextColor());

        // the box on the right
        int width = Math.min(150, entryWidth / 2);
        int left = x + entryWidth - width;
        int rgb = boxColor.getAsInt();
        graphics.fill(left - 1, y + 1, left + width + 1, y + 23, 0xFFFFFFFF);        // white frame
        graphics.fill(left, y + 2, left + width, y + 22, rgb >= 0 ? 0xFF000000 | rgb : 0xFF303030);

        // caption in black or white, whichever is readable on top of the colour
        int textColor = rgb >= 0 && brightness(rgb) > 140 ? 0xFF000000 : 0xFFFFFFFF;
        graphics.drawCenteredString(font, caption.get(), left + width / 2, y + 8, textColor);
    }

    private static int brightness(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    @Override
    public int getItemHeight() {
        return 24;
    }

    @Override
    public Object getValue() {
        return null;
    }

    @Override
    public Optional<Object> getDefaultValue() {
        return Optional.empty();
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return Collections.emptyList();
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return Collections.emptyList();
    }
}
