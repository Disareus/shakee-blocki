package com.shakeeblocki.mixin.bbe;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces Better Block Entities to treat animated or suppressed BlockEntities as not ready in
 * the static terrain mesh so Sodium extracts them for immediate rendering and BBE does not
 * bypass model rendering during breaking.
 */
@Mixin(value = BlockEntity.class, priority = 1500)
public abstract class BBEBlockEntityMixin {
    @Shadow
    @Final
    protected BlockPos worldPosition;

    @Dynamic("Added by Better Block Entities 1.4+ BlockEntityExt")
    @Inject(method = "bbe$isTerrainMeshReady()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void shakee$forceImmediateRenderDuringAnimation(CallbackInfoReturnable<Boolean> cir) {
        if (ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isAnimatedOrInvisible(this.worldPosition)) {
            cir.setReturnValue(false);
        }
    }

    @Dynamic("Added by older Better Block Entities BlockEntityExt")
    @Inject(method = "terrainMeshReady()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void shakee$forceImmediateRenderDuringAnimationLegacy(CallbackInfoReturnable<Boolean> cir) {
        if (ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isAnimatedOrInvisible(this.worldPosition)) {
            cir.setReturnValue(false);
        }
    }
}
