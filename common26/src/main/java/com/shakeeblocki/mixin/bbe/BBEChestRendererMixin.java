package com.shakeeblocki.mixin.bbe;

import betterblockentities.client.render.immediate.blockentity.renderers.BBEChestRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.MultiblockChestResources;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When Better Block Entities runs in FAST update mode (default without EMF), bbeModels hides
 * the chest bottom part because BBE expects the static bottom to remain in the terrain mesh.
 * Since Shakee suppresses the terrain mesh during animations, redirect to the full chest model.
 */
@Mixin(value = BBEChestRenderer.class, remap = false)
public abstract class BBEChestRendererMixin {
    @Shadow
    @Final
    private MultiblockChestResources<ChestModel> models;

    @Shadow
    @Final
    private MultiblockChestResources<ChestModel> bbeModels;

    @Redirect(
            method = "submit(Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(
                    value = "FIELD",
                    target = "Lbetterblockentities/client/render/immediate/blockentity/renderers/BBEChestRenderer;bbeModels:Lnet/minecraft/client/renderer/MultiblockChestResources;",
                    opcode = Opcodes.GETFIELD
            ),
            remap = false,
            require = 0
    )
    private MultiblockChestResources<ChestModel> shakee$useFullChestModels(
            BBEChestRenderer<?> instance,
            ChestRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector submitter,
            CameraRenderState camera
    ) {
        if (ShakeeAnimationManager.hasActiveAnimations()
                && renderState != null
                && renderState.blockPos != null
                && ShakeeAnimationManager.isAnimatedOrInvisible(renderState.blockPos)) {
            return this.models;
        }
        return this.bbeModels;
    }
}
