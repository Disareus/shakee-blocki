package com.shakeeblocki.mixin.bbe;

import betterblockentities.render.AltRenderDispatcher;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import com.shakeeblocki.animation.ShakeeAnimationState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies Shakee local transforms to BlockEntities rendered via BBE's custom AltRenderDispatcher API.
 */
@Mixin(AltRenderDispatcher.class)
public abstract class BBEAltRenderDispatcherMixin {
    @Inject(method = "submit", at = @At("HEAD"), remap = false, require = 0)
    private void shakee$applyAltAnimation(
            BlockEntityRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector submitter,
            CameraRenderState camera,
            CallbackInfo ci
    ) {
        poseStack.pushPose();
        if (!ShakeeAnimationManager.hasActiveAnimations() || renderState == null || renderState.blockPos == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }

        ShakeeAnimationState animation = ShakeeAnimationManager.getVisualAnimation(client.level, renderState.blockPos);
        if (animation == null) {
            return;
        }

        float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        animation.applyLocal(poseStack, ShakeeAnimationManager.getClientTicks(), tickDelta);
    }

    @Inject(method = "submit", at = @At("RETURN"), remap = false, require = 0)
    private void shakee$popAltAnimation(
            BlockEntityRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector submitter,
            CameraRenderState camera,
            CallbackInfo ci
    ) {
        poseStack.popPose();
    }
}
