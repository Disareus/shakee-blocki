package com.shakeeblocki.mixin.bbe;

import betterblockentities.client.render.immediate.blockentity.extentions.BlockEntityExt;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces Sodium to always extract animated or suppressed BlockEntities into LevelRenderState
 * instead of letting BBE skip extraction.
 */
@Mixin(value = SodiumWorldRenderer.class, priority = 2000)
public abstract class BBESodiumWorldRendererMixin {
    @Dynamic("Added by Better Block Entities 1.4+ SodiumWorldRendererMixin")
    @Inject(method = "bbe$shouldManage", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void shakee$neverManageAnimatedBlock(
            BlockEntityExt ext,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (ext instanceof BlockEntity be
                && ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isAnimatedOrInvisible(be.getBlockPos())) {
            cir.setReturnValue(false);
        }
    }

    @Dynamic("Added by older Better Block Entities SodiumWorldRendererMixin")
    @Inject(method = "shouldManage", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void shakee$neverManageAnimatedBlockLegacy(
            BlockEntityExt ext,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (ext instanceof BlockEntity be
                && ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isAnimatedOrInvisible(be.getBlockPos())) {
            cir.setReturnValue(false);
        }
    }
}
