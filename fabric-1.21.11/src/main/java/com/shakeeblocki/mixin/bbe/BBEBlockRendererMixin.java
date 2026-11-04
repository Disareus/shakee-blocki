package com.shakeeblocki.mixin.bbe;

import betterblockentities.client.chunk.pipeline.BBEBlockRenderer;
import betterblockentities.client.chunk.pipeline.BBEEmitter;
import com.shakeeblocki.animation.ShakeeAnimationManager;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.services.PlatformModelEmitter;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * Prevents Better Block Entities from baking chest (or any block entity) quads into the
 * static chunk mesh while the block is suppressed or animated by Shakee.
 */
@Mixin(value = BBEBlockRenderer.class, remap = false)
public abstract class BBEBlockRendererMixin {
    @Inject(method = "emitChest", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void shakee$suppressAnimatedChestInTerrain(
            Predicate<Direction> predicate,
            RandomSource randomSource,
            BlockState blockState,
            BBEEmitter emitter,
            BlockEntity blockEntity,
            CallbackInfo ci
    ) {
        if (blockEntity != null
                && ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isInvisible(blockEntity.getBlockPos())) {
            ci.cancel();
        }
    }

    @Inject(method = "emitBlockModel", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void shakee$suppressAllBbeTerrainEmissionDuringAnimation(
            PlatformModelEmitter emitter,
            BlockStateModel model,
            Predicate<Direction> predicate,
            MutableQuadViewImpl quadView,
            RandomSource random,
            BlockAndTintGetter tintGetter,
            LevelSlice slice,
            BlockPos pos,
            BlockState state,
            PlatformModelEmitter.Bufferer bufferer,
            CallbackInfo ci
    ) {
        if (pos != null
                && ShakeeAnimationManager.hasActiveAnimations()
                && ShakeeAnimationManager.isInvisible(pos)) {
            ci.cancel();
        }
    }
}
