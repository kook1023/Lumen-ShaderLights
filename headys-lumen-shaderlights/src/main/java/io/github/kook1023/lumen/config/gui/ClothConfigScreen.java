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
        AbstractConfigListEntry<LumenConfig.FlickerMode> flicker;
        AbstractConfigListEntry<Integer> flickerSpeed;
        AbstractConfigListEntry<Integer> flickerStrength;
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
            e.flicker = flicker.getValue().name().toLowerCase(Locale.ROOT);
            e.flickerSpeed = flickerSpeed.getValue();
            e.flickerStrength = flickerStrength.getValue();
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

    /**
     * @param tab the tab to open on: 1 = General, 2 = Light items, 3 = Handheld, 4 = Placed blocks, 5 = Flicker
     *            (/lumen config 1-5)
     */
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

        general.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "nearest_item"), current.pickNearestColorItem)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "nearest_item.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().pickNearestColorItem = v)
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
                    inner.add(row.flicker);
                    inner.add(row.flickerSpeed);
                    inner.add(row.flickerStrength);
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

        // ---------------------------------------------------------------- Handheld tab
        ConfigCategory hand = builder.getOrCreateCategory(Component.translatable(KEY + "category.handheld"));
        hand.addEntry(eb.startTextDescription(Component.translatable(KEY + "handheld.help")).build());

        hand.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "send_color"), current.sendLightColor)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "send_color.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().sendLightColor = v)
                .build());

        hand.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "match_rendered"), current.matchRenderedItem)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "match_rendered.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().matchRenderedItem = v)
                .build());

        hand.addEntry(eb.startEnumSelector(Component.translatable(KEY + "rgb_id_mode"),
                        LumenConfig.IdColorMode.class, current.rgbIdMode)
                .setDefaultValue(LumenConfig.IdColorMode.AUTO)
                .setEnumNameProvider(mode -> Component.translatable(KEY + "rgb_id_mode." + mode.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable(KEY + "rgb_id_mode.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().rgbIdMode = v)
                .build());

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

        placed.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "placed_flicker"), current.placedFlicker)
                .setDefaultValue(true)
                .setTooltip(Component.translatable(KEY + "placed_flicker.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().placedFlicker = v)
                .build());

        // ---------------------------------------------------------------- Flicker tab
        ConfigCategory flick = builder.getOrCreateCategory(Component.translatable(KEY + "category.flicker"));
        flick.addEntry(eb.startTextDescription(Component.translatable(KEY + "flicker.help")).build());

        AbstractConfigListEntry<LumenConfig.FlickerPreset> preset = eb.startEnumSelector(
                        Component.translatable(KEY + "flicker.preset"), LumenConfig.FlickerPreset.class, current.flickerPreset)
                .setDefaultValue(LumenConfig.FlickerPreset.GROUPS)
                .setEnumNameProvider(p -> Component.translatable(KEY + "flicker.preset." + p.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable(KEY + "flicker.preset.tooltip"))
                .setSaveConsumer(v -> LumenConfig.get().flickerPreset = v)
                .build();
        flick.addEntry(preset);

        flick.addEntry(eb.startIntSlider(Component.translatable(KEY + "flicker.speed"), current.defaultFlickerSpeed, 1, 20)
                .setDefaultValue(6)
                .setTooltip(Component.translatable(KEY + "flicker.speed.tooltip"))
                .setTextGetter(v -> Component.translatable(KEY + "item.flicker_speed.value", v))
                .setSaveConsumer(v -> LumenConfig.get().defaultFlickerSpeed = v)
                .build());

        flick.addEntry(eb.startIntSlider(Component.translatable(KEY + "flicker.strength"), current.defaultFlickerStrength, 5, 100)
                .setDefaultValue(35)
                .setTooltip(Component.translatable(KEY + "flicker.strength.tooltip"))
                .setTextGetter(v -> Component.translatable(KEY + "item.flicker_strength.value", v))
                .setSaveConsumer(v -> LumenConfig.get().defaultFlickerStrength = v)
                .build());

        addGroup(eb, flick, preset, "torches", current.flickerTorches, true, v -> LumenConfig.get().flickerTorches = v);
        addGroup(eb, flick, preset, "lanterns", current.flickerLanterns, true, v -> LumenConfig.get().flickerLanterns = v);
        addGroup(eb, flick, preset, "fire", current.flickerFire, true, v -> LumenConfig.get().flickerFire = v);
        addGroup(eb, flick, preset, "redstone", current.flickerRedstone, false, v -> LumenConfig.get().flickerRedstone = v);
        addGroup(eb, flick, preset, "sea", current.flickerSea, false, v -> LumenConfig.get().flickerSea = v);
        addGroup(eb, flick, preset, "nether", current.flickerNether, true, v -> LumenConfig.get().flickerNether = v);
        addGroup(eb, flick, preset, "froglights", current.flickerFroglights, true, v -> LumenConfig.get().flickerFroglights = v);
        addGroup(eb, flick, preset, "cave", current.flickerCave, true, v -> LumenConfig.get().flickerCave = v);

        Screen screen = builder.build();
        // Pick the starting tab. (Cloth's setFallbackCategory does NOT work here: it compares tab names as plain text,
        // and our tab names are translated, so it never matches. The tab index is a public field, so set it directly.)
        if (tab >= 2 && tab <= 5 && screen instanceof AbstractConfigScreen tabs) {
            tabs.selectedCategoryIndex = tab - 1;
        }
        return screen;
    }

    /** One group switch. Shown in grey (and ignored) while the mode above is not "Use the groups". */
    private static void addGroup(ConfigEntryBuilder eb, ConfigCategory cat,
                                 AbstractConfigListEntry<LumenConfig.FlickerPreset> preset, String key, boolean value,
                                 boolean def, java.util.function.Consumer<Boolean> save) {
        cat.addEntry(eb.startBooleanToggle(Component.translatable(KEY + "flicker.group." + key), value)
                .setDefaultValue(def)
                .setTooltip(Component.translatable(KEY + "flicker.group." + key + ".tooltip"))
                .setYesNoTextSupplier(v -> {
                    boolean used = preset.getValue() == LumenConfig.FlickerPreset.GROUPS;
                    Component text = Component.translatable(KEY + (v ? "flicker.yes" : "flicker.no"));
                    return used ? text : Component.translatable(KEY + "flicker.ignored", text)
                            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
                })
                .setSaveConsumer(save)
                .build());
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

        row.flicker = eb.startEnumSelector(Component.translatable(KEY + "item.flicker"),
                        LumenConfig.FlickerMode.class, d.flickerMode())
                .setDefaultValue(LumenConfig.FlickerMode.FOLLOW)
                .setEnumNameProvider(mode -> Component.translatable(KEY + "item.flicker." + mode.name().toLowerCase(Locale.ROOT)))
                .setTooltip(Component.translatable(KEY + "item.flicker.tooltip"))
                .build();

        row.flickerSpeed = eb.startIntSlider(Component.translatable(KEY + "item.flicker_speed"), d.flickerSpeed, 1, 20)
                .setDefaultValue(6)
                .setTooltip(Component.translatable(KEY + "item.flicker_speed.tooltip"))
                .setTextGetter(v -> Component.translatable(KEY + "item.flicker_speed.value", v))
                .build();

        row.flickerStrength = eb.startIntSlider(Component.translatable(KEY + "item.flicker_strength"), d.flickerStrength, 5, 100)
                .setDefaultValue(35)
                .setTooltip(Component.translatable(KEY + "item.flicker_strength.tooltip"))
                .setTextGetter(v -> Component.translatable(KEY + "item.flicker_strength.value", v))
                .build();

        row.pretend = eb.startStringDropdownMenu(Component.translatable(KEY + "item.pretend"), d.pretendToBe)
                .setSelections(LightPalette.ids())
                .setDefaultValue("")
                .setSuggestionMode(true)
                .setTooltip(Component.translatable(KEY + "item.pretend.tooltip"))
                .build();
    }
}
