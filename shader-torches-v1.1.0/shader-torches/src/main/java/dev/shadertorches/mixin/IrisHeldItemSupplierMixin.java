package dev.shadertorches.mixin;

import dev.shadertorches.iris.IrisBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Newer Iris builds (package net.irisshaders.iris). Silently skipped if that class does not exist. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.IdMapUniforms$HeldItemSupplier", remap = false)
public class IrisHeldItemSupplierMixin {
    @Inject(method = "update", at = @At("TAIL"), remap = false, require = 0)
    private void shadertorches$afterUpdate(CallbackInfo ci) {
        IrisBridge.afterUpdate(this);
    }
}
