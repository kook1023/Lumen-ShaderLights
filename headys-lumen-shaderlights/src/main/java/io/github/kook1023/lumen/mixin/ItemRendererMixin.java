package io.github.kook1023.lumen.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kook1023.lumen.iris.RenderedItemBridge;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Minecraft's item renderer so packs that make the held item glow from how it is DRAWN (e.g. current
 * Rethinking Voxels releases) see your item as the vanilla light it should look like. See {@link RenderedItemBridge}.
 *
 * Iris sets its own id at the very start (HEAD) of render(). We run at the first pushPose() call instead, which is
 * guaranteed to come after every HEAD hook and before anything is drawn, so our value is the one the shader sees.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    private static final String RENDER = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;"
            + "ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "IILnet/minecraft/client/resources/model/BakedModel;)V";

    @Inject(method = RENDER,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0),
            require = 0)
    private void headys_lumen$swapItemLook(ItemStack stack, ItemDisplayContext context, boolean leftHand,
                                           PoseStack poseStack, MultiBufferSource buffers, int light, int overlay,
                                           BakedModel model, CallbackInfo ci) {
        RenderedItemBridge.beforeRender(stack);
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0)
    private void headys_lumen$restoreItemLook(CallbackInfo ci) {
        RenderedItemBridge.afterRender();
    }
}
