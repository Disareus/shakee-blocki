package com.shakeeblocki.mixin;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientWorldMixin {

    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void onSetBlocksDirty(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CallbackInfo ci
    ) {
        // Register active animation and suppression before setBlocksDirty/sendBlockUpdated mark sections dirty.
        ShakeeAnimationManager.onClientBlockUpdated((ClientLevel) (Object) this, pos, newState);
    }

    @Inject(method = "setBlock", at = @At("RETURN"))
    private void onSetBlock(
            BlockPos pos,
            BlockState state,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            ShakeeAnimationManager.onClientBlockUpdated((ClientLevel) (Object) this, pos, state);
        }
    }
}
