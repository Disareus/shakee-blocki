package com.shakeeblocki.mixin.bbe;

import betterblockentities.client.render.immediate.OverlayRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When a block entity is undergoing a Shakee animation or has its chunk mesh suppressed,
 * prevent BBE from considering the block entity fully rendered in the static terrain mesh.
 * Returning false from manageCrumblingOverlay forces BBE to submit the actual animated
 * block entity model rather than skipping it to render only a standalone crack decal.
 */
@Mixin(value = OverlayRenderer.class, remap = false)
public abstract class BBEOverlayRendererMixin {
    @Inject(method = "manageCrumblingOverlay", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void shakee$forceImmediateModelRenderDuringAnimation(
            BlockEntity blockEntity,
            PoseStack poseStack,
            Model<?> model,
            Object state,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (blockEntity != null
                && ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isAnimatedOrInvisible(blockEntity.getBlockPos())) {
            cir.setReturnValue(false);
        }
    }
}
