package io.github.kook1023.lumen.mixin;

import io.github.kook1023.lumen.iris.PlacedLights;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When Iris stores a shader pack's block id map, hand it a copy with your placed lights added (see
 * {@link PlacedLights}). Covers the current and the older Iris package. Silently skipped without Iris.
 */
@Pseudo
@Mixin(targets = {
        "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings",
        "net.coderbot.iris.block_rendering.BlockRenderingSettings"
}, remap = false)
public abstract class PlacedLightsMixin {
    @ModifyVariable(method = "setBlockStateIds", at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
    private Object2IntMap<BlockState> headys_lumen$addPlacedLights(Object2IntMap<BlockState> blockStateIds) {
        return PlacedLights.augment(blockStateIds);
    }
}
