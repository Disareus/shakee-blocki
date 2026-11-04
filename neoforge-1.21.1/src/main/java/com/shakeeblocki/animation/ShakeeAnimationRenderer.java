package com.shakeeblocki.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Handles custom world rendering for blocks undergoing animated transformations.
 * Uses a reusable render view to avoid GC allocations during render frames.
 */
public final class ShakeeAnimationRenderer {
    private static final double MAX_RENDER_DISTANCE_SQ = 64.0D * 64.0D;
    private static final Direction[] DIRECTIONS = Direction.values();

    // Single reusable instance for synchronous client block tessellation
    private static final ReusableRenderView REUSABLE_VIEW = new ReusableRenderView();
    private static final RandomSource RANDOM_SOURCE = RandomSource.create();
    private static final RandomSource PROBE_RANDOM = RandomSource.createThreadSafe();

    // Render-thread only flag: lets BlockModelRendererMixin distinguish our own
    // re-tessellation of animated blocks from the vanilla chunk meshing loop.
    private static boolean renderingAnimation = false;

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

        BakedModel model = client.getBlockRenderer().getBlockModel(state);
        if (model == null || model.isCustomRenderer()) {
            return false;
        }

        try {
            PROBE_RANDOM.setSeed(42L);
            if (!model.getQuads(state, null, PROBE_RANDOM, ModelData.EMPTY, null).isEmpty()) {
                return true;
            }
            for (Direction dir : DIRECTIONS) {
                PROBE_RANDOM.setSeed(42L);
                if (!model.getQuads(state, dir, PROBE_RANDOM, ModelData.EMPTY, null).isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ignored) {
            // Dynamic modded models that require live BlockEntity ModelData still render via MODEL.
            return true;
        }
    }

    public static boolean isRenderingAnimation() {
        return renderingAnimation;
    }

    @SuppressWarnings("unused")
    public static void clearPool() {
        REUSABLE_VIEW.reset();
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        if (!ShakeeAnimationManager.hasActiveAnimations()) {
            ShakeeAnimationManager.clearRenderedBlockEntitiesThisFrame();
            return;
        }

        try {
            Minecraft client = Minecraft.getInstance();
            ClientLevel world = client.level;
            if (world == null) return;

            PoseStack poseStack = event.getPoseStack();
            if (poseStack == null) return;

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 cameraPos = camera != null && camera.isInitialized() ? camera.getPosition() : Vec3.ZERO;
            float tickDelta = client.getTimer().getGameTimeDeltaPartialTick(false);
            long currentTicks = ShakeeAnimationManager.getClientTicks();
            BlockRenderDispatcher blockRenderer = client.getBlockRenderer();
            BlockEntityRenderDispatcher beDispatcher = client.getBlockEntityRenderDispatcher();
            MultiBufferSource consumers = client.renderBuffers().bufferSource();

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
                        renderFallbackBlockEntity(world, beDispatcher, poseStack, consumers, animation, pos, cameraPos, tickDelta);
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

                BakedModel model = blockRenderer.getBlockModel(state);
                BlockEntity fallbackBe = state.hasBlockEntity() && animation.isDestroyed()
                        ? animation.getOrCreateFallbackBlockEntity(world)
                        : null;
                REUSABLE_VIEW.setup(world, pos, state, fallbackBe);
                buildBlock(blockRenderer, REUSABLE_VIEW, model, state, pos, poseStack, consumers);

                poseStack.popPose();
            }
        } finally {
            ShakeeAnimationManager.clearRenderedBlockEntitiesThisFrame();
        }
    }

    private static void renderFallbackBlockEntity(
            ClientLevel world,
            BlockEntityRenderDispatcher beDispatcher,
            PoseStack poseStack,
            MultiBufferSource consumers,
            ShakeeAnimationState animation,
            BlockPos pos,
            Vec3 cameraPos,
            float tickDelta
    ) {
        BlockEntity be = !animation.isDestroyed() ? world.getBlockEntity(pos) : null;
        if (be == null || !be.hasLevel() || !be.getType().isValid(be.getBlockState())) {
            be = animation.getOrCreateFallbackBlockEntity(world);
        }
        if (be == null) return;

        poseStack.pushPose();
        try {
            poseStack.translate(
                    (double) pos.getX() - cameraPos.x,
                    (double) pos.getY() - cameraPos.y,
                    (double) pos.getZ() - cameraPos.z
            );
            // BlockEntityRenderDispatcherMixin applies animation.applyLocal inside render().
            beDispatcher.render(be, tickDelta, poseStack, consumers);
        } catch (RuntimeException ignored) {
            // Guard against custom BERs assuming live chunk attachment.
        } finally {
            poseStack.popPose();
        }
    }

    private static void buildBlock(
            BlockRenderDispatcher dispatcher,
            BlockAndTintGetter view,
            BakedModel model,
            BlockState state,
            BlockPos pos,
            PoseStack poseStack,
            MultiBufferSource consumers
    ) {
        BlockEntity blockEntity = view.getBlockEntity(pos);
        ModelData modelData = blockEntity != null ? blockEntity.getModelData() : ModelData.EMPTY;
        long seed = state.getSeed(pos);
        RANDOM_SOURCE.setSeed(seed);
        ChunkRenderTypeSet renderTypes = model.getRenderTypes(state, RANDOM_SOURCE, modelData);

        renderingAnimation = true;
        try {
            if (renderTypes.isEmpty()) {
                RenderType fallbackType = ItemBlockRenderTypes.getMovingBlockRenderType(state);
                VertexConsumer buffer = consumers.getBuffer(fallbackType);
                RANDOM_SOURCE.setSeed(seed);
                dispatcher.getModelRenderer().tesselateBlock(
                        view,
                        model,
                        state,
                        pos,
                        poseStack,
                        buffer,
                        false,
                        RANDOM_SOURCE,
                        seed,
                        OverlayTexture.NO_OVERLAY,
                        modelData,
                        fallbackType
                );
                return;
            }

            for (RenderType chunkLayer : renderTypes.asList()) {
                RenderType movingLayer = RenderTypeHelper.getMovingBlockRenderType(chunkLayer);
                VertexConsumer buffer = consumers.getBuffer(movingLayer);
                RANDOM_SOURCE.setSeed(seed);
                dispatcher.getModelRenderer().tesselateBlock(
                        view,
                        model,
                        state,
                        pos,
                        poseStack,
                        buffer,
                        false,
                        RANDOM_SOURCE,
                        seed,
                        OverlayTexture.NO_OVERLAY,
                        modelData,
                        chunkLayer
                );
            }
        } finally {
            renderingAnimation = false;
        }
    }

    /**
     * Lightweight BlockAndTintGetter delegating to the real world while forcing the
     * original block state at the animated position (correct AO/light during BREAK animations).
     */
    public static final class ReusableRenderView implements BlockAndTintGetter {
        private ClientLevel world;
        private BlockState blockState;
        private BlockPos blockPos;
        private BlockEntity fallbackBlockEntity;

        public void setup(ClientLevel world, BlockPos pos, BlockState state, BlockEntity fallbackBlockEntity) {
            this.world = world;
            this.blockPos = pos;
            this.blockState = state;
            this.fallbackBlockEntity = fallbackBlockEntity;
        }

        public void reset() {
            this.world = null;
            this.blockPos = null;
            this.blockState = null;
            this.fallbackBlockEntity = null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return this.world != null ? this.world.getBlockTint(pos, colorResolver) : -1;
        }

        @Override
        public int getHeight() {
            return this.world != null ? this.world.getHeight() : 384;
        }

        @Override
        public int getMinBuildHeight() {
            return this.world != null ? this.world.getMinBuildHeight() : -64;
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
            return pos.equals(this.blockPos)
                    ? this.blockState
                    : (this.world != null ? this.world.getBlockState(pos) : this.blockState);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }
    }
}
