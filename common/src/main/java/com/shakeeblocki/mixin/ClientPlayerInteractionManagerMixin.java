package com.shakeeblocki.mixin;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import com.shakeeblocki.config.ShakeeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private float destroyProgress;

    @Unique
    private boolean trackingPlacement;
    @Unique
    private BlockPos targetPos;
    @Unique
    private BlockPos breakingPos;
    @Unique
    private long breakingStartedAt = -1L;

    @Inject(
            method = "performUseItemOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"
            )
    )
    private void captureBeforePlacement(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        ClientLevel world = this.minecraft.level;
        if (world == null) {
            this.trackingPlacement = false;
            return;
        }

        ShakeeConfig config = ShakeeConfig.get();
        if (!config.enablePlacementAnimation) {
            this.trackingPlacement = false;
            return;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            this.trackingPlacement = false;
            return;
        }

        if (!config.isBlockAllowed(blockItem.getBlock())) {
            this.trackingPlacement = false;
            return;
        }

        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hitResult);
        this.targetPos = context.getClickedPos().immutable();

        this.trackingPlacement = true;
        ShakeeAnimationManager.markPlacementAttempt(
                this.targetPos,
                blockItem.getBlock(),
                player.getDeltaMovement()
        );
    }

    @Inject(method = "performUseItemOn", at = @At("RETURN"))
    private void finishPlacement(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (!this.trackingPlacement) return;
        this.trackingPlacement = false;

        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            ShakeeAnimationManager.cancelPlacementAttempt(this.targetPos);
        }
    }

    @Unique
    private BlockPos destroyTargetPos;
    @Unique
    private BlockState destroyTargetState;
    @Unique
    private BlockEntity destroyTargetBlockEntity;
    @Unique
    private BlockPos destroyPartnerPos;
    @Unique
    private BlockState destroyPartnerState;
    @Unique
    private BlockEntity destroyPartnerBlockEntity;
    @Unique
    private long destroyTargetTick = -1L;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void captureDestroyBlock(
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        clearDestroyCapture();
        ClientLevel world = this.minecraft.level;
        if (this.minecraft.player == null || world == null) return;

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return;

        this.destroyTargetPos = pos.immutable();
        this.destroyTargetState = state;
        this.destroyTargetBlockEntity = state.hasBlockEntity() ? world.getBlockEntity(pos) : null;
        this.destroyTargetTick = ShakeeAnimationManager.getClientTicks();

        if (!(state.getBlock() instanceof ChestBlock)) {
            BlockPos partnerPos = ShakeeAnimationManager.getConnectedPos(pos, state);
            if (partnerPos != null) {
                BlockState partnerState = world.getBlockState(partnerPos);
                if (!partnerState.isAir() && partnerState.is(state.getBlock())) {
                    this.destroyPartnerPos = partnerPos.immutable();
                    this.destroyPartnerState = partnerState;
                    this.destroyPartnerBlockEntity = partnerState.hasBlockEntity() ? world.getBlockEntity(partnerPos) : null;
                }
            }
        }
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void playDestroyAnimation(
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (Boolean.TRUE.equals(cir.getReturnValue())
                && this.destroyTargetPos != null
                && this.destroyTargetState != null) {
            ShakeeAnimationManager.onInstantBreak(
                    this.minecraft.level,
                    this.destroyTargetPos,
                    this.destroyTargetState,
                    this.destroyTargetBlockEntity,
                    this.destroyPartnerPos,
                    this.destroyPartnerState,
                    this.destroyPartnerBlockEntity
            );
        }
        clearDestroyCapture();
    }

    @Unique
    private void clearDestroyCapture() {
        this.destroyTargetPos = null;
        this.destroyTargetState = null;
        this.destroyTargetBlockEntity = null;
        this.destroyPartnerPos = null;
        this.destroyPartnerState = null;
        this.destroyPartnerBlockEntity = null;
        this.destroyTargetTick = -1L;
    }

    @Inject(method = "startDestroyBlock", at = @At("RETURN"))
    private void beginBreakingTracking(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;

        ClientLevel world = this.minecraft.level;
        if (world == null) return;

        if (this.minecraft.player != null && this.minecraft.player.getAbilities().instabuild) return;

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return;

        this.breakingPos = pos.immutable();
        this.breakingStartedAt = ShakeeAnimationManager.getClientTicks();
    }

    @Inject(method = "continueDestroyBlock", at = @At("RETURN"))
    private void updateBreakingAnimation(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        ClientLevel world = this.minecraft.level;
        if (world == null) {
            resetBreakingTracking();
            return;
        }

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            resetBreakingTracking();
            return;
        }

        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            if (this.breakingPos != null) {
                BlockState oldState = world.getBlockState(this.breakingPos);
                if (!oldState.isAir()) {
                    ShakeeAnimationManager.cancelBreakingNow(world, this.breakingPos);
                }
            }
            resetBreakingTracking();
            return;
        }

        if (!pos.equals(this.breakingPos)) {
            if (this.breakingPos != null) {
                BlockState oldState = world.getBlockState(this.breakingPos);
                if (!oldState.isAir()) {
                    ShakeeAnimationManager.cancelBreakingNow(world, this.breakingPos);
                }
            }
            this.breakingPos = pos.immutable();
            this.breakingStartedAt = ShakeeAnimationManager.getClientTicks();
            return;
        }

        long age = ShakeeAnimationManager.getClientTicks() - this.breakingStartedAt;
        if (age >= ShakeeConfig.get().breakingStartDelayTicks) {
            ShakeeAnimationManager.onBreakingProgress(
                    world,
                    this.breakingPos,
                    state,
                    this.destroyProgress
            );
        }
    }

    @Inject(method = "stopDestroyBlock", at = @At("HEAD"))
    private void cancelBreakingAnimation(CallbackInfo ci) {
        ClientLevel world = this.minecraft.level;
        if (world != null && this.breakingPos != null) {
            BlockState state = world.getBlockState(this.breakingPos);
            if (!state.isAir()) {
                ShakeeAnimationManager.stopBreaking(world, this.breakingPos);
            }
        }
        resetBreakingTracking();
    }

    @Unique
    private void resetBreakingTracking() {
        this.breakingPos = null;
        this.breakingStartedAt = -1L;
    }
}
