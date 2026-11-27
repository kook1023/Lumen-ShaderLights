package io.github.kook1023.lumen.config.gui;

import io.github.kook1023.lumen.config.Colors;
import io.github.kook1023.lumen.config.LightPalette;
import io.github.kook1023.lumen.config.LumenConfig;
import io.github.kook1023.lumen.config.LightMatcher;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.AbstractConfigScreen;
import me.shedaniel.clothconfig2.gui.entries.MultiElementListEntry;
import me.shedaniel.clothconfig2.gui.entries.NestedListListEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The in-game editor, built with Cloth Config. Only ever loaded when Cloth Config is installed.
 * Saving writes config/headys_lumen.json and applies the changes instantly (no rejoin needed).
 */
public final class ClothConfigScreen {
    private static final String KEY = "headys_lumen.config.";

    private ClothConfigScreen() {
    }

    /** One row of the item list: the data plus the widgets that edit it. */
    private static final class Row {
        final LumenConfig.Entry data;
        AbstractConfigListEntry<String> id;
        AbstractConfigListEntry<Integer> level;
        AbstractConfigListEntry<Boolean> useColor;
        AbstractConfigListEntry<Integer> hue;
        AbstractConfigListEntry<Integer> saturation;
        ColorPreviewEntry preview;
        AbstractConfigListEntry<String> pretend;
        int originalHue;
        int originalSaturation;

        Row(LumenConfig.Entry data) {
            this.data = data;
        }

        /** The colour as stored (full brightness), from the current slider positions. */
        int storedRgb() {
            return Colors.hsvToRgb(hue.getValue() / 360f, saturation.getValue() / 100f, 1f);
        }

        /** What the preview box shows: hue + saturation + intensity (as brightness). -1 = no custom colour. */
        int previewRgb() {
            if (!useColor.getValue()) {
                return -1;
            }
            float brightness = Math.max(0.15f, level.getValue() / 15f);
            return Colors.hsvToRgb(hue.getValue() / 360f, saturation.getValue() / 100f, brightness);
        }

        Component previewCaption() {
            if (!useColor.getValue()) {
                return Component.translatable(KEY + "item.preview.pack");
            }
            return Component.literal(Colors.toHex(storedRgb()) + "  |  " + level.getValue() + "/15");
        }

        /** Reads the widgets back into a fresh entry. */
        LumenConfig.Entry toEntry() {
            LumenConfig.Entry e = new LumenConfig.Entry();
            e.id = id.getValue() == null ? "" : id.getValue().trim();
            e.level = level.getValue();
            e.pretendToBe = pretend.getValue() == null ? "" : pretend.getValue().trim();
            if (useColor.getValue()) {
                boolean moved = hue.getValue() != originalHue || saturation.getValue() != originalSaturation;
                // Untouched sliders keep the exact hex from the file (the sliders are only whole degrees/percent).
                e.color = (moved || Colors.parseHex(data.color) < 0) ? Colors.toHex(storedRgb()) : data.color;
            } else {
                e.color = "";
            }
            return e;
        }
    }

    /** @param tab the tab to open on: 1 = General, 2 = Light items, 3 = Placed blocks (/lumen config 1|2|3) */
    public static Screen create(Screen parent, int tab) {
        LumenConfig current = LumenConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable(KEY + "title"))
                .setSavingRunnable(LumenConfig::applyAndSave);
        ConfigEntryBuilder eb = builder.entryBuilder();

        // ---------------------------------------------------------------- General tab
        ConfigCategory general = builder.getOrCreateCategory(Component.translatable(KEY + "category.general"));

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "enabled"), current.enabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "enabled.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().enabled = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "match_suffix"), current.matchTorchSuffix)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "match_suffix.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().matchTorchSuffix = v)
                .build());

        general.addEntry(eb.startIntSlider(Component.translatable(KEY + "light_level"), current.defaultLightLevel, 1, 15)
                .setDefaultValue(14)
                .setTooltip(Component.translatable(KEY + "light_level.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().defaultLightLevel = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "spoof_id"), current.spoofTorchItemId)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "spoof_id.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().spoofTorchItemId = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "send_color"), current.sendLightColor)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "send_color.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().sendLightColor = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "nearest_item"), current.pickNearestColorItem)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "nearest_item.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().pickNearestColorItem = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "match_rendered"), current.matchRenderedItem)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "match_rendered.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().matchRenderedItem = v)
                .build());

        general.addEntry(eb.startEnumSelector(Component.translatable(KEY + "rgb_id_mode"),
                        LumenConfig.IdColorMode.class, current.rgbIdMode)
                .setDefaultValue(LumenConfig.IdColorMode.AUTO)
                .setEnumNameProvider(mode -> Component.translatable(KEY + "rgb_id_mode." + mode.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable(KEY + "rgb_id_mode.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().rgbIdMode = v)
                .build());

        // ---------------------------------------------------------------- Items tab
        ConfigCategory items = builder.getOrCreateCategory(Component.translatable(KEY + "category.items"));
        items.addEntry(eb.startTextDescription(Component.translatable(KEY + "items.help")).build());

        List<Row> rows = new ArrayList<>();
        for (LumenConfig.Entry entry : current.items) {
            rows.add(new Row(entry));
        }

        // Builds the editor widgets for one row. elem == null means the "+" button was pressed (a brand-new row).
        BiFunction<Row, NestedListListEntry<Row, MultiElementListEntry<Row>>, MultiElementListEntry<Row>> newCell =
                (elem, list) -> {
                    Row row = elem != null ? elem : new Row(new LumenConfig.Entry());
                    buildWidgets(eb, row);
                    List<AbstractConfigListEntry<?>> inner = new ArrayList<>();
                    inner.add(row.id);
                    inner.add(row.level);
                    inner.add(row.useColor);
                    inner.add(row.hue);
                    inner.add(row.saturation);
                    inner.add(row.preview);
                    inner.add(row.pretend);
                    Component title = row.data.id.isBlank()
                            ? Component.translatable(KEY + "item.new")
                            : Component.literal(row.data.id);
                    return new MultiElementListEntry<>(title, row, inner, elem == null);
                };

        NestedListListEntry<Row, MultiElementListEntry<Row>> itemList = new NestedListListEntry<>(
                Component.translatable(KEY + "items"),
                rows,
                true,
                () -> Optional.of(new Component[] {Component.translatable(KEY + "items.tooltip")}),
                saved -> {
                    List<LumenConfig.Entry> out = new ArrayList<>();
                    for (Row row : saved) {
                        out.add(row.toEntry());
                    }
                    LumenConfig.get().items = out;
                },
                () -> new ArrayList<>(),
                eb.getResetButtonKey(),
                true,   // show the X (remove) button on every row
                true,   // new rows appear at the top, right under the "+" button
                newCell);
        items.addEntry(itemList);

        // ---------------------------------------------------------------- Placed blocks tab
        ConfigCategory placed = builder.getOrCreateCategory(Component.translatable(KEY + "category.placed"));
        placed.addEntry(eb.startTextDescription(Component.translatable(KEY + "placed.help")).build());

        placed.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "placed_lights"), current.placedLights)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "placed_lights.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().placedLights = v)
                .build());

        placed.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "placed_recolor_vanilla"), current.placedRecolorVanilla)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "placed_recolor_vanilla.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().placedRecolorVanilla = v)
                .build());

        Screen screen = builder.build();
        // Pick the starting tab. (Cloth's setFallbackCategory does NOT work here: it compares tab names as plain text,
        // and our tab names are translated, so it never matches. The tab index is a public field, so set it directly.)
        if (tab >= 2 && tab <= 3 && screen instanceof AbstractConfigScreen tabs) {
            tabs.selectedCategoryIndex = tab - 1;
        }
        return screen;
    }

    private static void buildWidgets(ConfigEntryBuilder eb, Row row) {
        LumenConfig.Entry d = row.data;

        int rgb = Colors.parseHex(d.color);
        boolean hasColor = rgb >= 0;
        float[] hsv = hasColor ? Colors.rgbToHsv(rgb) : new float[] {30f / 360f, 1f, 1f};
        row.originalHue = Math.round(hsv[0] * 360f);
        row.originalSaturation = Math.round(hsv[1] * 100f);

        row.id = eb.startStrField(Component.translatable(KEY + "item.id"), d.id)
                .setDefaultValue("")
                .setTooltip(Component.translatable(KEY + "item.id.tooltip"))
                .setErrorSupplier(s -> LightMatcher.validateId(s).<Component>map(Component::translatable))
                .build();

        row.level = eb.startIntSlider(Component.translatable(KEY + "item.intensity"), d.level, 0, 15)
                .setDefaultValue(14)
                .setTooltip(Component.translatable(KEY + "item.intensity.tooltip"))
                .setTextGetter(v -> v == 0
                        ? Component.translatable(KEY + "item.intensity.off")
                        : Component.translatable(KEY + "item.intensity.value", v))
                .build();

        row.useColor = eb.startBooleanToggle(Component.translatable(KEY + "item.use_color"), hasColor)
                .setDefaultValue(false)
                .setTooltip(Component.translatable(KEY + "item.use_color.tooltip"))
                .build();

        // The hue label is drawn in the hue's own colour (a mini preview); the box below shows hue + saturation + intensity.
        row.hue = eb.startIntSlider(Component.translatable(KEY + "item.hue"), row.originalHue, 0, 360)
                .setDefaultValue(30)
                .setTooltip(Component.translatable(KEY + "item.hue.tooltip"))
                .setTextGetter(v -> Component.translatable(KEY + "item.hue.value", v)
                        .withStyle(Style.EMPTY.withColor(Colors.hsvToRgb(v / 360f, 1f, 1f))))
                .build();

        row.saturation = eb.startIntSlider(Component.translatable(KEY + "item.saturation"), row.originalSaturation, 0, 100)
                .setDefaultValue(100)
                .setTooltip(Component.translatable(KEY + "item.saturation.tooltip"))
                .setTextGetter(v -> Component.translatable(KEY + "item.saturation.value", v))
                .build();

        row.preview = new ColorPreviewEntry(Component.translatable(KEY + "item.preview"), row::previewRgb, row::previewCaption);

        row.pretend = eb.startStringDropdownMenu(Component.translatable(KEY + "item.pretend"), d.pretendToBe)
                .setSelections(LightPalette.ids())
                .setDefaultValue("")
                .setSuggestionMode(true)
                .setTooltip(Component.translatable(KEY + "item.pretend.tooltip"))
                .build();
    }
}
