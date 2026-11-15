package com.shakeeblocki.mixin.indigo;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.TerrainRenderContext;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Indigo routes chunk RenderShape.MODEL blocks through TerrainRenderContext#bufferModel instead
 * of Vanilla ModelBlockRenderer#tesselateBlock, so static models must also be suppressed here.
 */
@Mixin(value = TerrainRenderContext.class, remap = false)
public abstract class TerrainRenderContextMixin {
    @Inject(method = "bufferModel", at = @At("HEAD"), cancellable = true)
    private void skipInvisibleAnimatedBlock(
            BlockStateModel model,
            BlockState blockState,
            BlockPos blockPos,
            CallbackInfo ci
    ) {
        if (ShakeeAnimationManager.hasActiveAnimations() && ShakeeAnimationManager.isInvisible(blockPos)) {
            ci.cancel();
        }
    }
}
