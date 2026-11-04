package com.shakeeblocki.mixin.bbe;

import betterblockentities.client.render.immediate.blockentity.renderers.BBEChestRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In BBE 1.21.11, submitManagedModel swaps singleModel/doubleLeftModel/doubleRightModel to
 * ChestModelOverride instances that hide the chest bottom in FAST update mode. Restore the
 * original full models before submit() passes them into submitManagedModel for animated chests.
 */
@Mixin(BBEChestRenderer.class)
public abstract class BBEChestRendererMixin {
    @Shadow(remap = false)
    private ChestModel singleModel;

    @Shadow(remap = false)
    private ChestModel doubleLeftModel;

    @Shadow(remap = false)
    private ChestModel doubleRightModel;

    @Shadow(remap = false)
    @Final
    private ChestModel singleModelOrg;

    @Shadow(remap = false)
    @Final
    private ChestModel doubleLeftModelOrg;

    @Shadow(remap = false)
    @Final
    private ChestModel doubleRightModelOrg;

    @Inject(method = "submit", at = @At("HEAD"), remap = false, require = 0)
    private void shakee$useFullChestModels(
            ChestRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector submitter,
            CameraRenderState camera,
            CallbackInfo ci
    ) {
        if (ShakeeAnimationManager.hasActiveAnimations()
                && renderState != null
                && renderState.blockPos != null
                && ShakeeAnimationManager.isAnimatedOrInvisible(renderState.blockPos)) {
            this.singleModel = this.singleModelOrg;
            this.doubleLeftModel = this.doubleLeftModelOrg;
            this.doubleRightModel = this.doubleRightModelOrg;
        }
    }
}
