package com.shakeeblocki.mixin.embeddium;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import org.embeddedt.embeddium.api.render.chunk.BlockRenderContext;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import org.embeddedt.embeddium.impl.render.chunk.compile.pipeline.BlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockRenderer.class)
public class BlockRendererMixin {
    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void hideInvisibleBlock(
            BlockRenderContext ctx,
            ChunkBuildBuffers buffers,
            CallbackInfo ci
    ) {
        if (ShakeeAnimationManager.hasActiveAnimations() && ctx != null && ShakeeAnimationManager.isInvisible(ctx.pos())) {
            ci.cancel();
        }
    }
}
