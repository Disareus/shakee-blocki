package com.shakeeblocki.mixin.bbe;

import betterblockentities.client.BBE;
import betterblockentities.client.render.immediate.blockentity.renderers.BBEAbstractSignRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During block entity submission, BBE sets limitVanillaSignRendering = true so signs only draw
 * text while relying on the chunk mesh for the wooden sign body. When a sign is animated and its
 * chunk mesh is suppressed, allow submitSign to draw the full sign body (including breakProgress)
 * and skip BBE's standalone manageCrumblingOverlay pass to avoid duplicate crack decals.
 */
@Mixin(BBEAbstractSignRenderer.class)
public abstract class BBESignRendererMixin {
    @Redirect(
            method = "submit",
            at = @At(
                    value = "FIELD",
                    target = "Lbetterblockentities/client/BBE$GlobalScope;limitVanillaSignRendering:Z",
                    opcode = Opcodes.GETSTATIC
            ),
            remap = false,
            require = 0
    )
    private boolean shakee$renderFullSignBodyDuringAnimation(
            SignRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector submitter,
            CameraRenderState camera
    ) {
        if (shakee$isAnimatedSign(renderState)) {
            return false;
        }
        return BBE.GlobalScope.limitVanillaSignRendering;
    }

    @Inject(method = "manageCrumblingOverlay", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void shakee$skipDuplicateSignCrumblingOverlay(
            SignRenderState renderState,
            PoseStack poseStack,
            SignBlock signBlock,
            BlockState blockState,
            CallbackInfo ci
    ) {
        if (shakee$isAnimatedSign(renderState)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean shakee$isAnimatedSign(SignRenderState renderState) {
        return ShakeeAnimationManager.hasActiveAnimations()
                && renderState != null
                && renderState.blockPos != null
                && ShakeeAnimationManager.isAnimatedOrInvisible(renderState.blockPos);
    }
}
