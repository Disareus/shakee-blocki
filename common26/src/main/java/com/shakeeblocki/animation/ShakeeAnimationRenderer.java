package com.shakeeblocki.animation;

import java.util.ArrayList;
import java.util.List;

import com.shakeeblocki.compat.ShakeeSubmitAdapter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Handles custom world rendering for animated blocks in Minecraft 26.1.2-26.3.
 * Uses pooled render state lists to eliminate per-frame object allocations.
 */
public final class ShakeeAnimationRenderer {
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0D * 64.0D;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final List<WorldAwareMovingBlockRenderState> STATE_POOL = new ArrayList<>();
    private static final List<PooledBlockEntityRenderEntry> BLOCK_ENTITY_STATE_POOL = new ArrayList<>();
    private static final RandomSource RANDOM_SOURCE = RandomSource.createThreadSafe();
    private static final ThreadLocal<List<BlockStateModelPart>> PART_SCRATCH = ThreadLocal.withInitial(() -> new ArrayList<>(4));

    private ShakeeAnimationRenderer() {}

    public static boolean hasBakedModelGeometry(BlockState state) {
        if (state == null || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        if (!state.hasBlockEntity()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getModelManager() == null) {
            return true;
        }

        BlockStateModelSet modelSet = client.getModelManager().getBlockStateModelSet();
        if (modelSet == null) {
            return true;
        }

        BlockStateModel model = modelSet.get(state);
        if (model == null) {
            return false;
        }

        List<BlockStateModelPart> parts = PART_SCRATCH.get();
        parts.clear();
        RANDOM_SOURCE.setSeed(42L);
        model.collectParts(RANDOM_SOURCE, parts);

        for (int i = 0, size = parts.size(); i < size; i++) {
            BlockStateModelPart part = parts.get(i);
            if (!part.getQuads(null).isEmpty()) {
                parts.clear();
                return true;
            }
            for (Direction dir : DIRECTIONS) {
                if (!part.getQuads(dir).isEmpty()) {
                    parts.clear();
                    return true;
                }
            }
        }
        parts.clear();
        return false;
    }

    public static void clearPool() {
        STATE_POOL.clear();
        BLOCK_ENTITY_STATE_POOL.clear();
    }

    public static void render(LevelRenderContext context) {
        if (!ShakeeAnimationManager.hasActiveAnimations()) {
            ShakeeAnimationManager.clearRenderedBlockEntitiesThisFrame();
            return;
        }

        try {
            Minecraft client = Minecraft.getInstance();
            ClientLevel world = client.level;
            if (world == null) return;

            PoseStack poseStack = context.poseStack();
            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 cameraPos = context.levelState().cameraRenderState != null && context.levelState().cameraRenderState.pos != null
                    ? context.levelState().cameraRenderState.pos
                    : Vec3.ZERO;
            long currentTicks = ShakeeAnimationManager.getClientTicks();
            BlockEntityRenderDispatcher beDispatcher = client.getBlockEntityRenderDispatcher();

            int poolIndex = 0;
            int bePoolIndex = 0;

            for (ShakeeAnimationState animation : ShakeeAnimationManager.activeAnimations()) {
                if (animation.isDestroyed() && animation.phase() == AnimationPhase.RESTORING) {
                    continue;
                }
                BlockPos pos = animation.pos();

                // Distance culling: skip animation calculation if block is too far from camera
                double dx = (double) pos.getX() + 0.5D - cameraPos.x;
                double dy = (double) pos.getY() + 0.5D - cameraPos.y;
                double dz = (double) pos.getZ() + 0.5D - cameraPos.z;
                if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ) {
                    continue;
                }

                BlockState state = animation.originalState();

                if (!animation.usesCustomWorldRender()) {
                    if (state.hasBlockEntity()
                            && !ShakeeAnimationManager.wasBlockEntityRenderedThisFrame(pos.asLong())) {
                        bePoolIndex = submitFallbackBlockEntity(
                                context,
                                world,
                                beDispatcher,
                                poseStack,
                                animation,
                                pos,
                                cameraPos,
                                tickDelta,
                                bePoolIndex
                        );
                    }
                    continue;
                }

                poseStack.pushPose();
                poseStack.translate(
                        (double) pos.getX() - cameraPos.x,
                        (double) pos.getY() - cameraPos.y,
                        (double) pos.getZ() - cameraPos.z
                );

                animation.applyLocal(poseStack, currentTicks, tickDelta);

                BlockEntity fallbackBe = state.hasBlockEntity() && animation.isDestroyed()
                        ? animation.getOrCreateFallbackBlockEntity(world)
                        : null;
                WorldAwareMovingBlockRenderState renderState;
                if (poolIndex < STATE_POOL.size()) {
                    renderState = STATE_POOL.get(poolIndex);
                    renderState.setup(world, pos, state, fallbackBe);
                } else {
                    renderState = new WorldAwareMovingBlockRenderState(world, pos, state, fallbackBe);
                    STATE_POOL.add(renderState);
                }
                poolIndex++;

                ShakeeSubmitAdapter.get().submitMovingBlock(context.submitNodeCollector(), poseStack, renderState);
                poseStack.popPose();
            }
        } finally {
            ShakeeAnimationManager.clearRenderedBlockEntitiesThisFrame();
        }
    }

    @SuppressWarnings("unchecked")
    private static int submitFallbackBlockEntity(
            LevelRenderContext context,
            ClientLevel world,
            BlockEntityRenderDispatcher beDispatcher,
            PoseStack poseStack,
            ShakeeAnimationState animation,
            BlockPos pos,
            Vec3 cameraPos,
            float tickDelta,
            int bePoolIndex
    ) {
        BlockEntity be = !animation.isDestroyed() ? world.getBlockEntity(pos) : null;
        if (be == null || !be.hasLevel() || !be.getType().isValid(be.getBlockState())) {
            be = animation.getOrCreateFallbackBlockEntity(world);
        }
        if (be == null) {
            return bePoolIndex;
        }

        BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer =
                (BlockEntityRenderer<BlockEntity, BlockEntityRenderState>) (BlockEntityRenderer<?, ?>) beDispatcher.getRenderer(be);
        if (renderer == null) {
            return bePoolIndex;
        }

        PooledBlockEntityRenderEntry entry;
        if (bePoolIndex < BLOCK_ENTITY_STATE_POOL.size()) {
            entry = BLOCK_ENTITY_STATE_POOL.get(bePoolIndex);
            entry.ensureRenderer(renderer);
        } else {
            entry = new PooledBlockEntityRenderEntry(renderer);
            BLOCK_ENTITY_STATE_POOL.add(entry);
        }
        bePoolIndex++;

        try {
            renderer.extractRenderState(be, entry.renderState, tickDelta, cameraPos, null);
        } catch (RuntimeException ignored) {
            return bePoolIndex;
        }

        poseStack.pushPose();
        try {
            poseStack.translate(
                    (double) pos.getX() - cameraPos.x,
                    (double) pos.getY() - cameraPos.y,
                    (double) pos.getZ() - cameraPos.z
            );
            // ChestBlockEntityRendererMixin / BlockEntityRenderManagerMixin applies animation.applyLocal during submit.
            beDispatcher.submit(entry.renderState, poseStack, context.submitNodeCollector(), context.levelState().cameraRenderState);
        } catch (RuntimeException ignored) {
            // Guard against custom BERs assuming live chunk attachment.
        } finally {
            poseStack.popPose();
        }
        return bePoolIndex;
    }

    private static final class PooledBlockEntityRenderEntry {
        private BlockEntityRenderer<?, ?> renderer;
        private BlockEntityRenderState renderState;

        private PooledBlockEntityRenderEntry(BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer) {
            this.renderer = renderer;
            this.renderState = renderer.createRenderState();
        }

        private void ensureRenderer(BlockEntityRenderer<BlockEntity, BlockEntityRenderState> target) {
            if (this.renderer != target || this.renderState == null) {
                this.renderer = target;
                this.renderState = target.createRenderState();
            }
        }
    }

    private static final class WorldAwareMovingBlockRenderState extends MovingBlockRenderState {
        private ClientLevel world;
        private BlockEntity fallbackBlockEntity;

        private WorldAwareMovingBlockRenderState(ClientLevel world, BlockPos pos, BlockState state, BlockEntity fallbackBlockEntity) {
            setup(world, pos, state, fallbackBlockEntity);
        }

        public void setup(ClientLevel world, BlockPos pos, BlockState state, BlockEntity fallbackBlockEntity) {
            this.world = world;
            this.fallbackBlockEntity = fallbackBlockEntity;
            this.randomSeedPos = pos;
            this.blockPos = pos;
            this.blockState = state;
            this.biome = world.getBiome(pos);
            this.cardinalLighting = world.cardinalLighting();
            this.lightEngine = world.getLightEngine();
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return this.world != null ? this.world.cardinalLighting() : null;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return this.world != null ? this.world.getLightEngine() : null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver color) {
            return this.world != null ? this.world.getBlockTint(pos, color) : -1;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (this.world != null) {
                BlockEntity live = this.world.getBlockEntity(pos);
                if (live != null) {
                    return live;
                }
            }
            return pos.equals(this.blockPos) ? this.fallbackBlockEntity : null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(this.blockPos) ? this.blockState : (this.world != null ? this.world.getBlockState(pos) : this.blockState);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return this.world != null ? this.world.getHeight() : 256;
        }

        @Override
        public int getMinY() {
            return this.world != null ? this.world.getMinY() : 0;
        }
    }
}
