package com.shakeeblocki.mixin.embeddium;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.embeddedt.embeddium.impl.render.chunk.compile.pipeline.BlockOcclusionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockOcclusionCache.class)
public class BlockOcclusionCacheMixin {
    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void forceFaceNearInvisibleBlock(
            BlockState selfState,
            BlockGetter view,
            BlockPos pos,
            Direction facing,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!ShakeeAnimationManager.hasActiveAnimations() || pos == null || facing == null) {
            return;
        }

        long neighborLong = BlockPos.asLong(
                pos.getX() + facing.getStepX(),
                pos.getY() + facing.getStepY(),
                pos.getZ() + facing.getStepZ()
        );
        if (ShakeeAnimationManager.isAnimatedOrInvisible(neighborLong)) {
            cir.setReturnValue(true);
        }
    }
}
