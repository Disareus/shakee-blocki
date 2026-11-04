package com.shakeeblocki.mixin.bbe;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In BBE 1.4+, bbe$submitBreakingOverlays wraps dispatcher.submit in LevelRenderer.submitBlockEntities
 * and intercepts breaking blocks to submit only the crumbling crack decal without the base model,
 * expecting the static model to remain in the terrain mesh.
 * When Shakee suppresses the terrain mesh during animation, force op.call(...) to render the real model.
 */
@Mixin(value = LevelRenderer.class, priority = 2000)
public abstract class BBELevelRendererMixin {
    @Dynamic("Added by Better Block Entities 1.4+ LevelRendererMixin")
    @Inject(
            method = "bbe$submitBreakingOverlays",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void shakee$forceRealSubmitDuringBreaking(
            BlockEntityRenderDispatcher dispatcher,
            BlockEntityRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector submitter,
            CameraRenderState camera,
            Operation<Void> op,
            CallbackInfo ci
    ) {
        if (renderState != null && renderState.blockPos != null
                && ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isAnimatedOrInvisible(renderState.blockPos)) {
            op.call(dispatcher, renderState, poseStack, submitter, camera);
            ci.cancel();
        }
    }
}
