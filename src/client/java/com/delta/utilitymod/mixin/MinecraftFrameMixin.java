package com.delta.utilitymod.mixin;

import com.delta.utilitymod.DeltaUtilityModClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the per-frame loop so camera smoothing can run at render framerate
 * instead of tick rate (20 Hz), which is what makes bot aim look fluid.
 */
@Mixin(Minecraft.class)
abstract class MinecraftFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void delta$onFrameStart(boolean tick, CallbackInfo ci) {
        DeltaUtilityModClient.onRenderFrame((Minecraft) (Object) this);
    }
}
