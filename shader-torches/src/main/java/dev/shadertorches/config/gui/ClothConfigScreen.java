package dev.shadertorches.config.gui;

import dev.shadertorches.config.Colors;
import dev.shadertorches.config.LightPalette;
import dev.shadertorches.config.TorchConfig;
import dev.shadertorches.config.TorchMatcher;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
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
 * Saving writes config/shadertorches.json and applies the changes instantly (no rejoin needed).
 */
public final class ClothConfigScreen {
    private static final String KEY = "shadertorches.config.";

    private ClothConfigScreen() {
    }

    /** One row of the item list: the data plus the widgets that edit it. */
    private static final class Row {
        final TorchConfig.Entry data;
        AbstractConfigListEntry<String> id;
        AbstractConfigListEntry<Integer> level;
        AbstractConfigListEntry<Boolean> useColor;
        AbstractConfigListEntry<Integer> hue;
        AbstractConfigListEntry<Integer> saturation;
        ColorPreviewEntry preview;
        AbstractConfigListEntry<String> pretend;
        int originalHue;
        int originalSaturation;

        Row(TorchConfig.Entry data) {
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
        TorchConfig.Entry toEntry() {
            TorchConfig.Entry e = new TorchConfig.Entry();
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

    /** @param openItemsTab true = start on the "Light items" tab (used by /shaderlights config2) */
    public static Screen create(Screen parent, boolean openItemsTab) {
        TorchConfig current = TorchConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable(KEY + "title"))
                .setSavingRunnable(TorchConfig::applyAndSave);
        ConfigEntryBuilder eb = builder.entryBuilder();

        // ---------------------------------------------------------------- General tab
        ConfigCategory general = builder.getOrCreateCategory(Component.translatable(KEY + "category.general"));

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "enabled"), current.enabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "enabled.tooltip"))
                .setSaveConsumer(v -> TorchConfig.get().enabled = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "match_suffix"), current.matchTorchSuffix)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "match_suffix.tooltip"))
                .setSaveConsumer(v -> TorchConfig.get().matchTorchSuffix = v)
                .build());

        general.addEntry(eb.startIntSlider(Component.translatable(KEY + "light_level"), current.defaultLightLevel, 1, 15)
                .setDefaultValue(14)
                .setTooltip(Component.translatable(KEY + "light_level.tooltip"))
                .setSaveConsumer(v -> TorchConfig.get().defaultLightLevel = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "spoof_id"), current.spoofTorchItemId)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "spoof_id.tooltip"))
                .setSaveConsumer(v -> TorchConfig.get().spoofTorchItemId = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "send_color"), current.sendLightColor)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "send_color.tooltip"))
                .setSaveConsumer(v -> TorchConfig.get().sendLightColor = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "nearest_item"), current.pickNearestColorItem)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "nearest_item.tooltip"))
                .setSaveConsumer(v -> TorchConfig.get().pickNearestColorItem = v)
                .build());

        general.addEntry(eb.startEnumSelector(Component.translatable(KEY + "rgb_id_mode"),
                        TorchConfig.IdColorMode.class, current.rgbIdMode)
                .setDefaultValue(TorchConfig.IdColorMode.AUTO)
                .setEnumNameProvider(mode -> Component.translatable(KEY + "rgb_id_mode." + mode.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable(KEY + "rgb_id_mode.tooltip"))
                .setSaveConsumer(v -> TorchConfig.get().rgbIdMode = v)
                .build());

        // ---------------------------------------------------------------- Items tab
        ConfigCategory items = builder.getOrCreateCategory(Component.translatable(KEY + "category.items"));
        items.addEntry(eb.startTextDescription(Component.translatable(KEY + "items.help")).build());

        List<Row> rows = new ArrayList<>();
        for (TorchConfig.Entry entry : current.items) {
            rows.add(new Row(entry));
        }

        // Builds the editor widgets for one row. elem == null means the "+" button was pressed (a brand-new row).
        BiFunction<Row, NestedListListEntry<Row, MultiElementListEntry<Row>>, MultiElementListEntry<Row>> newCell =
                (elem, list) -> {
                    Row row = elem != null ? elem : new Row(new TorchConfig.Entry());
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
                    List<TorchConfig.Entry> out = new ArrayList<>();
                    for (Row row : saved) {
                        out.add(row.toEntry());
                    }
                    TorchConfig.get().items = out;
                },
                () -> new ArrayList<>(),
                eb.getResetButtonKey(),
                true,   // show the X (remove) button on every row
                true,   // new rows appear at the top, right under the "+" button
                newCell);
        items.addEntry(itemList);

        if (openItemsTab) {
            builder.setFallbackCategory(items);   // the tab Cloth opens on
        }
        return builder.build();
    }

    private static void buildWidgets(ConfigEntryBuilder eb, Row row) {
        TorchConfig.Entry d = row.data;

        int rgb = Colors.parseHex(d.color);
        boolean hasColor = rgb >= 0;
        float[] hsv = hasColor ? Colors.rgbToHsv(rgb) : new float[] {30f / 360f, 1f, 1f};
        row.originalHue = Math.round(hsv[0] * 360f);
        row.originalSaturation = Math.round(hsv[1] * 100f);

        row.id = eb.startStrField(Component.translatable(KEY + "item.id"), d.id)
                .setDefaultValue("")
                .setTooltip(Component.translatable(KEY + "item.id.tooltip"))
                .setErrorSupplier(s -> TorchMatcher.validateId(s).<Component>map(Component::translatable))
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
