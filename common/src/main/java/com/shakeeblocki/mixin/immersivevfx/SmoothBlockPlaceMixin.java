package com.shakeeblocki.mixin.immersivevfx;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import com.shakeeblocki.config.ShakeeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents ImmersiveVFX's SmoothBlockPlace from spawning a second animated block overlay
 * at positions already handled or enabled for animation by Shakee Blocki.
 */
@Pseudo
@Mixin(targets = "com.shinyflvres.immersivevfx.client.SmoothBlockPlace", remap = false)
public abstract class SmoothBlockPlaceMixin {
    @Inject(
            method = "placed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void shakee$skipHandledPlacementAnimation(
            BlockPos pos,
            BlockState oldState,
            int sequenceNr,
            CallbackInfo ci
    ) {
        if (pos == null) {
            return;
        }
        if (ShakeeAnimationManager.isAnimatedOrInvisible(pos)) {
            ci.cancel();
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (ShakeeConfig.get().enablePlacementAnimation
                && client != null
                && client.level != null
                && ShakeeAnimationManager.isBlockAllowed(client.level.getBlockState(pos))) {
            ci.cancel();
        }
    }
}
