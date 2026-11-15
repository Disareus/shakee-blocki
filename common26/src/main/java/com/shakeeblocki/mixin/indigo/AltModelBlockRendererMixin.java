package com.shakeeblocki.mixin.indigo;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric Renderer API routes chunk section tesselation through AltModelBlockRenderer,
 * bypassing Vanilla ModelBlockRenderer for hybrid BlockEntity and waterlogged blocks.
 */
@Mixin(value = AltModelBlockRendererImpl.class, remap = false)
public abstract class AltModelBlockRendererMixin {
    @Shadow
    @Final
    private boolean cull;

    @Shadow
    private BlockPos pos;

    @Inject(method = "tesselateBlock", at = @At("HEAD"), cancellable = true)
    private void skipInvisibleAnimatedBlock(
            QuadEmitter emitter,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState blockState,
            BlockStateModel model,
            long seed,
            CallbackInfo ci
    ) {
        if (!ShakeeAnimationManager.hasActiveAnimations()) {
            return;
        }

        if (!(level instanceof MovingBlockRenderState) && ShakeeAnimationManager.isInvisible(pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldCullFace", at = @At("HEAD"), cancellable = true)
    private void forceFaceNextToInvisibleBlock(
            Direction side,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!this.cull || side == null || this.pos == null || !ShakeeAnimationManager.hasActiveAnimations()) {
            return;
        }

        long neighborLong = BlockPos.asLong(
                this.pos.getX() + side.getStepX(),
                this.pos.getY() + side.getStepY(),
                this.pos.getZ() + side.getStepZ()
        );
        if (ShakeeAnimationManager.isAnimatedOrInvisible(neighborLong)) {
            // shouldCullFace returns true to cull and false to keep the face visible.
            cir.setReturnValue(false);
        }
    }
}
