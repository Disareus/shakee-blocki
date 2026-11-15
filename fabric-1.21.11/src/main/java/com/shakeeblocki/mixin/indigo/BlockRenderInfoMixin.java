package com.shakeeblocki.mixin.indigo;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures neighboring block faces next to animated or hidden blocks are not culled by Indigo
 * when hybrid or cached block states remain non-air during chunk compilation.
 */
@Mixin(value = BlockRenderInfo.class, remap = false)
public abstract class BlockRenderInfoMixin {
    @Shadow
    public BlockPos blockPos;

    @Shadow
    private boolean enableCulling;

    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true)
    private void forceFaceNextToInvisibleBlock(
            Direction side,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!this.enableCulling || side == null || this.blockPos == null || !ShakeeAnimationManager.hasActiveAnimations()) {
            return;
        }

        long neighborLong = BlockPos.asLong(
                this.blockPos.getX() + side.getStepX(),
                this.blockPos.getY() + side.getStepY(),
                this.blockPos.getZ() + side.getStepZ()
        );
        if (ShakeeAnimationManager.isAnimatedOrInvisible(neighborLong)) {
            cir.setReturnValue(true);
        }
    }
}
