package com.shakeeblocki.animation;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Handles custom world rendering for blocks undergoing animated transformations.
 * Uses reusable render states to avoid GC allocations during render frames.
 */
public final class ShakeeAnimationRenderer {
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0D * 64.0D;
    private static final Direction[] DIRECTIONS = Direction.values();

    // Single reusable instance for synchronous client block tesselation
    private static final ReusableMovingBlockRenderState REUSABLE_STATE = new ReusableMovingBlockRenderState();
    private static final List<PooledBlockEntityRenderEntry> BLOCK_ENTITY_STATE_POOL = new ArrayList<>();
    private static final RandomSource RANDOM_SOURCE = RandomSource.createThreadSafe();
    private static final ThreadLocal<List<BlockModelPart>> PART_SCRATCH = ThreadLocal.withInitial(() -> new ArrayList<>(4));

    private ShakeeAnimationRenderer() {}

    public static boolean hasBakedModelGeometry(BlockState state) {
        if (state == null || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        if (!state.hasBlockEntity()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getBlockRenderer() == null) {
            return true;
        }

        BlockStateModel model = client.getBlockRenderer().getBlockModel(state);
        if (model == null) {
            return false;
        }

        List<BlockModelPart> parts = PART_SCRATCH.get();
        parts.clear();
        RANDOM_SOURCE.setSeed(42L);
        model.collectParts(RANDOM_SOURCE, parts);

        for (int i = 0, size = parts.size(); i < size; i++) {
            BlockModelPart part = parts.get(i);
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
        REUSABLE_STATE.reset();
        BLOCK_ENTITY_STATE_POOL.clear();
    }

    /**
     * Appends fallback render states for animated BlockEntity-rendered blocks that Vanilla/Sodium
     * skipped during extraction (uncompiled async chunk section, fading-in previously empty section,
     * or destroyed block whose live BlockEntity was already removed).
     */
    @SuppressWarnings("unchecked")
    public static void appendMissingAnimatedBlockEntities(WorldExtractionContext context) {
        if (!ShakeeAnimationManager.hasActiveAnimations()) return;

        ClientLevel world = context.world();
        LevelRenderState worldState = context.worldState();
        if (world == null || worldState == null) return;

        Minecraft client = Minecraft.getInstance();
        BlockEntityRenderDispatcher beDispatcher = client.getBlockEntityRenderDispatcher();
        Camera camera = context.camera();
        Vec3 cameraPos = camera != null && camera.isInitialized() ? camera.position() : Vec3.ZERO;
        float tickDelta = context.tickCounter().getGameTimeDeltaPartialTick(false);
        List<BlockEntityRenderState> extractedStates = worldState.blockEntityRenderStates;
        int initialSize = extractedStates.size();
        int bePoolIndex = 0;

        for (ShakeeAnimationState animation : ShakeeAnimationManager.activeAnimations()) {
            if (animation.usesCustomWorldRender()) continue;
            if (animation.isDestroyed() && animation.phase() == AnimationPhase.RESTORING) {
                continue;
            }

            BlockState state = animation.originalState();
            if (!state.hasBlockEntity()) continue;

            BlockPos pos = animation.pos();
            double dx = (double) pos.getX() + 0.5D - cameraPos.x;
            double dy = (double) pos.getY() + 0.5D - cameraPos.y;
            double dz = (double) pos.getZ() + 0.5D - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ) {
                continue;
            }

            if (containsBlockPos(extractedStates, initialSize, pos)) {
                continue;
            }

            BlockEntity be = !animation.isDestroyed() ? world.getBlockEntity(pos) : null;
            if (be == null || !be.hasLevel() || !be.getType().isValid(be.getBlockState())) {
                be = animation.getOrCreateFallbackBlockEntity(world);
            }
            if (be == null) continue;

            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer =
                    (BlockEntityRenderer<BlockEntity, BlockEntityRenderState>) (BlockEntityRenderer<?, ?>) beDispatcher.getRenderer(be);
            if (renderer == null) continue;

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
                extractedStates.add(entry.renderState);
            } catch (RuntimeException ignored) {
                // Guard against custom BERs assuming live chunk attachment.
            }
        }
    }

    private static boolean containsBlockPos(List<BlockEntityRenderState> states, int count, BlockPos pos) {
        for (int i = 0; i < count; i++) {
            BlockEntityRenderState state = states.get(i);
            if (state != null && pos.equals(state.blockPos)) {
                return true;
            }
        }
        return false;
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

    public static void render(WorldRenderContext context) {
        if (!ShakeeAnimationManager.hasActiveAnimations()) return;

        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        if (world == null) return;

        PoseStack poseStack = context.matrices();
        if (poseStack == null) return;

        MultiBufferSource consumers = context.consumers();
        if (consumers == null) {
            consumers = client.renderBuffers().bufferSource();
        }

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera != null && camera.isInitialized() ? camera.position() : Vec3.ZERO;
        float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long currentTicks = ShakeeAnimationManager.getClientTicks();
        BlockRenderDispatcher blockRenderer = client.getBlockRenderer();

        for (ShakeeAnimationState animation : ShakeeAnimationManager.activeAnimations()) {
            if (!animation.usesCustomWorldRender()) continue;
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

            poseStack.pushPose();
            poseStack.translate(
                    (double) pos.getX() - cameraPos.x,
                    (double) pos.getY() - cameraPos.y,
                    (double) pos.getZ() - cameraPos.z
            );

            animation.applyLocal(poseStack, currentTicks, tickDelta);

            RenderType renderType = ItemBlockRenderTypes.getMovingBlockRenderType(state);
            VertexConsumer buffer = consumers.getBuffer(renderType);
            BlockStateModel model = blockRenderer.getBlockModel(state);

            RANDOM_SOURCE.setSeed(state.getSeed(pos));
            List<BlockModelPart> parts = PART_SCRATCH.get();
            parts.clear();
            model.collectParts(RANDOM_SOURCE, parts);

            try {
                BlockEntity fallbackBe = state.hasBlockEntity() && animation.isDestroyed()
                        ? animation.getOrCreateFallbackBlockEntity(world)
                        : null;
                REUSABLE_STATE.setup(world, pos, state, fallbackBe);
                blockRenderer.getModelRenderer().tesselateBlock(
                        REUSABLE_STATE,
                        parts,
                        state,
                        pos,
                        poseStack,
                        buffer,
                        false,
                        OverlayTexture.NO_OVERLAY
                );
            } finally {
                parts.clear();
            }

            poseStack.popPose();
        }
    }

    public static final class ReusableMovingBlockRenderState extends MovingBlockRenderState {
        private ClientLevel world;
        private BlockEntity fallbackBlockEntity;

        public void setup(ClientLevel world, BlockPos pos, BlockState state, BlockEntity fallbackBlockEntity) {
            this.world = world;
            this.fallbackBlockEntity = fallbackBlockEntity;
            this.randomSeedPos = pos;
            this.blockPos = pos;
            this.blockState = state;
            this.biome = world.getBiome(pos);
            this.level = world;
        }

        public void reset() {
            this.world = null;
            this.fallbackBlockEntity = null;
            this.level = null;
            this.blockPos = null;
            this.blockState = null;
            this.randomSeedPos = null;
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return this.world != null ? this.world.getShade(direction, shade) : 1.0F;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return this.world != null ? this.world.getLightEngine() : null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return this.world != null ? this.world.getBlockTint(pos, colorResolver) : -1;
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
