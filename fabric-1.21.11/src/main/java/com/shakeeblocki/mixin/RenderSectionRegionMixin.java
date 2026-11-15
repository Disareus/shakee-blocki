package com.shakeeblocki.mixin;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin {
    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void hideInvisibleBlockDuringChunkBuild(
            BlockPos pos,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (!ShakeeAnimationManager.hasActiveAnimations() || !ShakeeAnimationManager.isInvisible(pos)) {
            return;
        }

        // Preserve BlockEntity registration and fluid geometry for hybrid blocks;
        // Indigo TerrainRenderContextMixin / ModelBlockRendererMixin suppress the baked block model.
        BlockState realState = cir.getReturnValue();
        if (realState != null && !realState.isAir() && !realState.hasBlockEntity() && realState.getFluidState().isEmpty()) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
