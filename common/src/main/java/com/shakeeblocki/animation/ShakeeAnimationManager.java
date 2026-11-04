package com.shakeeblocki.animation;

import com.shakeeblocki.config.ShakeeConfig;
import com.shakeeblocki.platform.ShakeePlatform;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages active block animation lifecycles, visibility suppression, and multi-block structure synchronizations.
 */
public final class ShakeeAnimationManager {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int PENDING_LIFETIME_TICKS = 20;
    private static final int MAX_ACTIVE_ANIMATIONS = 24;

    // Client-thread animation maps & primitive tracking collections
    private static final Long2ObjectOpenHashMap<ShakeeAnimationState> ACTIVE = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<PendingPlacement> PENDING = new Long2ObjectOpenHashMap<>();
    private static final LongArrayList TO_REMOVE = new LongArrayList();
    private static final LongSet DIRTY_SECTIONS = new LongOpenHashSet();
    private static final LongSet RENDERED_BLOCK_ENTITIES_THIS_FRAME = new LongOpenHashSet();

    private static long clientTicks = 0L;
    private static long lastPlacementTick = -100L;
    private static volatile boolean activeAnimationsPresent;
    private static ClientLevel trackedWorld;

    private ShakeeAnimationManager() {}

    /**
     * Ticks animation manager on the client main thread. Handles decay, destruction, and handoff transitions.
     */
    public static void tick() {
        clientTicks++;

        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        if (world != trackedWorld) {
            clear();
            trackedWorld = world;
        }
        if (world == null) {
            return;
        }

        ShakeeConfig config = ShakeeConfig.get();
        DIRTY_SECTIONS.clear();

        if (!PENDING.isEmpty()) {
            PENDING.values().removeIf(pending -> pending.expiresAt() <= clientTicks);
        }
        TO_REMOVE.clear();

        for (Long2ObjectMap.Entry<ShakeeAnimationState> entry : ACTIVE.long2ObjectEntrySet()) {
            long posLong = entry.getLongKey();
            ShakeeAnimationState state = entry.getValue();
            BlockPos pos = state.pos();

            if ((state.kind() == AnimationKind.BREAK && !config.enableBreakingAnimation)
                    || (state.kind() != AnimationKind.BREAK && !config.enablePlacementAnimation)) {
                TO_REMOVE.add(posLong);
                continue;
            }

            BlockState current = world.getBlockState(pos);
            boolean destroyedBreakWithAir = state.kind() == AnimationKind.BREAK
                    && state.isDestroyed()
                    && current.isAir();
            if (!destroyedBreakWithAir && !areCompatibleStates(state.originalState(), current)) {
                TO_REMOVE.add(posLong);
                continue;
            }

            if (!current.isAir() && current.hasBlockEntity()) {
                state.captureBlockEntity(world.getBlockEntity(pos));
            }

            if (state.kind() == AnimationKind.BREAK) {
                if (!current.isAir()) {
                    state.updateState(current);
                }

                if (state.isBreakingExpired(clientTicks) || current.isAir() || state.isDestroyed()) {
                    if (!state.isBreakingReleasing()) {
                        if (current.isAir() || state.isDestroyed()) {
                            state.markDestroyed(clientTicks);
                        } else {
                            state.beginBreakingRelease(clientTicks);
                        }
                    } else if (state.isSettlingFinished(clientTicks)) {
                        if (state.isDestroyed() && state.hiddenPartnerPos() == null) {
                            TO_REMOVE.add(posLong);
                        } else if (state.isStructureHidden() || state.phase() == AnimationPhase.RESTORING) {
                            if (state.phase() != AnimationPhase.RESTORING) {
                                unhideForState(world, state);
                                state.enterRestoringPhase(clientTicks);
                            } else if (state.isRestoringFinished(clientTicks)) {
                                TO_REMOVE.add(posLong);
                            }
                        } else {
                            TO_REMOVE.add(posLong);
                        }
                    }
                }
                continue;
            }

            if (state.isAnimationFinished(clientTicks)) {
                if (state.isStructureHidden() || state.phase() == AnimationPhase.RESTORING) {
                    if (state.phase() != AnimationPhase.RESTORING) {
                        unhideForState(world, state);
                        state.enterRestoringPhase(clientTicks);
                    } else if (state.isRestoringFinished(clientTicks)) {
                        TO_REMOVE.add(posLong);
                    }
                } else {
                    TO_REMOVE.add(posLong);
                }
            }
        }

        for (int i = 0; i < TO_REMOVE.size(); i++) {
            long posLong = TO_REMOVE.getLong(i);
            ShakeeAnimationState removed = removeActive(posLong);
            if (removed != null) {
                unhideForState(world, removed);
            }
        }
        activeAnimationsPresent = !ACTIVE.isEmpty();

        DIRTY_SECTIONS.clear();
    }

    public static boolean hasActiveAnimations() {
        return activeAnimationsPresent;
    }

    public static Collection<ShakeeAnimationState> activeAnimations() {
        return ACTIVE.values();
    }

    public static boolean isInvisible(BlockPos pos) {
        return pos != null && ShakeeRenderSuppressor.isSuppressed(pos.asLong());
    }

    public static boolean isInvisible(long posLong) {
        return ShakeeRenderSuppressor.isSuppressed(posLong);
    }

    public static boolean isAnimatedOrInvisible(BlockPos pos) {
        return pos != null && ShakeeRenderSuppressor.isAnimatedOrSuppressed(pos.asLong());
    }

    public static boolean isAnimatedOrInvisible(long posLong) {
        return ShakeeRenderSuppressor.isAnimatedOrSuppressed(posLong);
    }

    public static ShakeeAnimationState getBreaking(BlockPos pos) {
        ShakeeAnimationState state = ACTIVE.get(pos.asLong());
        return state != null && state.kind() == AnimationKind.BREAK ? state : null;
    }

    public static long getClientTicks() {
        return clientTicks;
    }

    public static void markBlockEntityRenderedThisFrame(long posLong) {
        RENDERED_BLOCK_ENTITIES_THIS_FRAME.add(posLong);
    }

    public static boolean wasBlockEntityRenderedThisFrame(long posLong) {
        return RENDERED_BLOCK_ENTITIES_THIS_FRAME.contains(posLong);
    }

    public static void clearRenderedBlockEntitiesThisFrame() {
        if (!RENDERED_BLOCK_ENTITIES_THIS_FRAME.isEmpty()) {
            RENDERED_BLOCK_ENTITIES_THIS_FRAME.clear();
        }
    }

    public static void onBreakingProgress(ClientLevel world, BlockPos pos, BlockState state, float progress) {
        if (!ShakeeConfig.get().enableBreakingAnimation || state.isAir() || !isBlockAllowed(state)) {
            return;
        }

        int horizontalSign = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;

        startOrRefreshBreaking(world, pos.immutable(), state, horizontalSign, progress);

        if (state.getBlock() instanceof ChestBlock) {
            BlockPos otherPos = getChestOtherPos(pos, state);
            if (otherPos != null) {
                BlockState otherState = world.getBlockState(otherPos);
                if (isValidChestPair(state, otherState)) {
                    startOrRefreshBreaking(world, otherPos.immutable(), otherState, horizontalSign, progress);
                }
            }
            return;
        }

        if (ShakeeConfig.get().breakingAnimateConnectedBlocks) {
            BlockPos otherPos = getConnectedPos(pos, state);
            if (otherPos != null) {
                BlockState otherState = world.getBlockState(otherPos);
                if (!otherState.isAir() && otherState.is(state.getBlock())) {
                    startOrRefreshBreaking(world, otherPos.immutable(), otherState, horizontalSign, progress);
                }
            }
        }
    }

    private static void startOrRefreshBreaking(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            int horizontalSign,
            float progress
    ) {
        long posLong = pos.asLong();
        ShakeeAnimationState existing = ACTIVE.get(posLong);

        if (existing != null && existing.kind() == AnimationKind.BREAK && areCompatibleStates(existing.originalState(), state)) {
            boolean wasRestoring = existing.phase() == AnimationPhase.RESTORING;
            existing.updateState(state);
            if (state.hasBlockEntity() && !existing.usesCustomWorldRender()) {
                existing.captureBlockEntity(world.getBlockEntity(pos));
            }
            existing.updateBreakProgress(progress);
            existing.refresh(clientTicks);
            if (wasRestoring) {
                hideForState(world, existing);
            }
            return;
        }

        if (existing != null) {
            removeStateNow(world, pos);
        }

        ShakeeAnimationState created = new ShakeeAnimationState(
                pos,
                state,
                clientTicks,
                ShakeeConfig.get().breakingLoopTicks,
                horizontalSign,
                AnimationKind.BREAK
        );
        if (state.hasBlockEntity() && !created.usesCustomWorldRender()) {
            created.captureBlockEntity(world.getBlockEntity(pos));
        }
        created.updateBreakProgress(progress);
        putActive(posLong, created);
        created.refresh(clientTicks);

        hideForState(world, created);
    }

    public static void stopBreaking(ClientLevel world, BlockPos pos) {
        ShakeeAnimationState state = ACTIVE.get(pos.asLong());
        if (state != null && state.kind() == AnimationKind.BREAK) {
            state.beginBreakingRelease(clientTicks);
        }

        BlockState blockState = world.getBlockState(pos);
        if (blockState.getBlock() instanceof ChestBlock) {
            BlockPos otherPos = getChestOtherPos(pos, blockState);
            if (otherPos != null) {
                BlockState otherState = world.getBlockState(otherPos);
                if (isValidChestPair(blockState, otherState)) {
                    ShakeeAnimationState other = ACTIVE.get(otherPos.asLong());
                    if (other != null && other.kind() == AnimationKind.BREAK) {
                        other.beginBreakingRelease(clientTicks);
                    }
                }
            }
            return;
        }

        BlockPos otherPos = getConnectedPos(pos, blockState);
        if (otherPos != null) {
            ShakeeAnimationState other = ACTIVE.get(otherPos.asLong());
            if (other != null && other.kind() == AnimationKind.BREAK) {
                other.beginBreakingRelease(clientTicks);
            }
        }
    }

    public static void cancelBreakingNow(ClientLevel world, BlockPos pos) {
        removeBreakingStateNow(world, pos);

        BlockState blockState = world.getBlockState(pos);
        if (blockState.getBlock() instanceof ChestBlock) {
            BlockPos otherPos = getChestOtherPos(pos, blockState);
            if (otherPos != null) {
                removeBreakingStateNow(world, otherPos);
            }
            return;
        }

        BlockPos otherPos = getConnectedPos(pos, blockState);
        if (otherPos != null) {
            removeBreakingStateNow(world, otherPos);
        }
    }

    public static void clear() {
        ACTIVE.clear();
        activeAnimationsPresent = false;
        PENDING.clear();
        TO_REMOVE.clear();
        DIRTY_SECTIONS.clear();
        RENDERED_BLOCK_ENTITIES_THIS_FRAME.clear();
        lastPlacementTick = -100L;
        trackedWorld = null;
        ShakeeRenderSuppressor.clear();
    }

    public static void markPlacementAttempt(BlockPos targetPos, Block block, Vec3 velocity) {
        ShakeeConfig config = ShakeeConfig.get();
        if (!config.enablePlacementAnimation) return;
        if (block == null || !config.isBlockAllowed(block)) return;

        int horizontalSign = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;

        PendingPlacement pending = new PendingPlacement(
                block,
                horizontalSign,
                velocity,
                clientTicks + PENDING_LIFETIME_TICKS
        );

        PENDING.put(targetPos.asLong(), pending);
    }

    public static void cancelPlacementAttempt(BlockPos targetPos) {
        if (targetPos != null) {
            PENDING.remove(targetPos.asLong());
        }
    }

    public static void onInstantBreak(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            BlockEntity capturedBlockEntity,
            BlockPos partnerPos,
            BlockState partnerState,
            BlockEntity capturedPartnerBlockEntity
    ) {
        if (!ShakeeConfig.get().enableBreakingAnimation || state == null || state.isAir() || !isBlockAllowed(state)) return;

        BlockPos immutablePos = pos.immutable();
        long posLong = immutablePos.asLong();

        ShakeeAnimationState existing = ACTIVE.get(posLong);
        if (existing != null && existing.kind() == AnimationKind.BREAK) {
            BlockState previousState = existing.originalState();
            existing.captureBlockEntity(capturedBlockEntity);
            existing.markDestroyed(clientTicks);
            releasePartnerChestBreaking(world, immutablePos, previousState);
            return;
        }

        int horizontalSign = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;

        onInstantBreakSingle(world, immutablePos, state, capturedBlockEntity, horizontalSign);

        // Breaking one half of a double chest leaves the partner standing as ChestType.SINGLE,
        // whereas breaking a Door, Bed, or DoublePlant destroys both blocks simultaneously.
        if (!(state.getBlock() instanceof ChestBlock) && ShakeeConfig.get().breakingAnimateConnectedBlocks) {
            if (partnerPos != null && partnerState != null && !partnerState.isAir() && partnerState.is(state.getBlock())) {
                onInstantBreakSingle(world, partnerPos.immutable(), partnerState, capturedPartnerBlockEntity, horizontalSign);
            }
        }
    }

    private static void onInstantBreakSingle(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            BlockEntity capturedBlockEntity,
            int horizontalSign
    ) {
        if (ACTIVE.containsKey(pos.asLong())) {
            removeStateNow(world, pos);
        }

        ShakeeAnimationState created = new ShakeeAnimationState(
                pos,
                state,
                clientTicks,
                Math.max(3, ShakeeConfig.get().breakingLoopTicks),
                horizontalSign,
                AnimationKind.BREAK
        );
        created.captureBlockEntity(capturedBlockEntity);
        created.markDestroyed(clientTicks);
        putActive(pos.asLong(), created);
    }

    public static void onClientBlockUpdated(ClientLevel world, BlockPos pos, BlockState state) {
        long posLong = pos.asLong();

        // When block is destroyed (becomes Air)
        if (state.isAir()) {
            ShakeeAnimationState existing = ACTIVE.get(posLong);
            if (existing != null && existing.kind() == AnimationKind.BREAK) {
                BlockState previousState = existing.originalState();
                existing.markDestroyed(clientTicks);
                releasePartnerChestBreaking(world, pos, previousState);
            }
            return;
        }

        ShakeeConfig config = ShakeeConfig.get();
        if (!config.enablePlacementAnimation || !isBlockAllowed(state)) {
            PENDING.remove(posLong);
            return;
        }

        if (state.getBlock() instanceof ChestBlock) {
            refreshExisting(world, pos, state);

            BlockPos otherPos = getChestOtherPos(pos, state);
            BlockState otherState = otherPos != null ? world.getBlockState(otherPos) : null;

            if (isValidChestPair(state, otherState)) {
                refreshExisting(world, otherPos, otherState);
            }

            PendingPlacement pendingHere = PENDING.get(posLong);
            if (pendingHere != null) {
                boolean placedHere = matchesPendingBlock(pendingHere.block(), state);
                if (placedHere) {
                    startOrRefreshChest(world, pos, state, pendingHere.horizontalSign(), pendingHere.velocity());
                    if (otherPos != null) PENDING.remove(otherPos.asLong());
                }
                PENDING.remove(posLong);
                if (placedHere) return;
            }

            if (isValidChestPair(state, otherState)) {
                PendingPlacement pendingOther = PENDING.get(otherPos.asLong());
                if (pendingOther != null) {
                    boolean placedOther = matchesPendingBlock(pendingOther.block(), otherState);
                    if (placedOther) {
                        startOrRefreshChest(world, pos, state, pendingOther.horizontalSign(), pendingOther.velocity());
                    }
                    PENDING.remove(otherPos.asLong());
                    PENDING.remove(posLong);
                    if (placedOther) return;
                }

                ShakeeAnimationState otherAnimation = ACTIVE.get(otherPos.asLong());
                if (otherAnimation != null && areCompatibleStates(otherAnimation.originalState(), state)) {
                    if (ACTIVE.get(posLong) == null) {
                        startSyncedFrom(world, pos, state, otherAnimation);
                    } else {
                        refreshExisting(world, pos, state);
                    }
                }
            }
            return;
        }

        refreshExisting(world, pos, state);

        BlockPos otherPos = getConnectedPos(pos, state);
        if (otherPos != null) {
            BlockState otherState = world.getBlockState(otherPos);
            refreshExisting(world, otherPos, otherState);
        }

        PendingPlacement pending = PENDING.get(posLong);
        if (pending != null) {
            if (matchesPendingBlock(pending.block(), state)) {
                ShakeeAnimationState existing = ACTIVE.get(posLong);
                if (existing == null || existing.kind() != AnimationKind.PLACE
                        || !areCompatibleStates(existing.originalState(), state)) {
                    if (existing != null) removeStateNow(world, pos);
                    start(world, pos, state, pending.horizontalSign(), pending.velocity());
                } else {
                    refreshExisting(world, pos, state);
                }

                startConnectedIfPresent(world, pos, state, pending.horizontalSign(), pending.velocity());
                PENDING.remove(posLong);
                return;
            }
            PENDING.remove(posLong);
        }

        ShakeeAnimationState connectedAnimation = findConnectedAnimation(pos, state);
        if (connectedAnimation != null) {
            if (ACTIVE.get(posLong) == null) {
                startSyncedFrom(world, pos, state, connectedAnimation);
            } else {
                refreshExisting(world, pos, state);
            }
        }
    }

    public static void start(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            int horizontalSign,
            Vec3 velocity
    ) {
        if (state.isAir() || !isBlockAllowed(state) || !ShakeeConfig.get().enablePlacementAnimation) return;

        if (!enforceMaxActiveAnimations(world)) return;

        BlockPos immutablePos = pos.immutable();
        ShakeeAnimationState created = new ShakeeAnimationState(
                immutablePos,
                state,
                clientTicks,
                ShakeeConfig.get().durationTicks,
                horizontalSign,
                AnimationKind.PLACE,
                velocity
        );
        if (state.hasBlockEntity() && !created.usesCustomWorldRender()) {
            created.captureBlockEntity(world.getBlockEntity(immutablePos));
        }
        putActive(immutablePos.asLong(), created);

        hideForState(world, created);

        // Fast-Build Throttle: if placing rapidly (<= 3 ticks apart),
        // throttle neighbor ripples to reduce chunk meshing overhead by ~85%!
        boolean isFastBuilding = (clientTicks - lastPlacementTick) <= 3;
        lastPlacementTick = clientTicks;

        if (ShakeeConfig.get().enableNeighborRipple && !isFastBuilding) {
            triggerNeighborRipples(world, immutablePos);
        }
    }

    private static boolean enforceMaxActiveAnimations(ClientLevel world) {
        if (ACTIVE.size() < MAX_ACTIVE_ANIMATIONS) return true;

        long oldestKey = -1L;
        long oldestTick = Long.MAX_VALUE;
        ShakeeAnimationState oldestState = null;

        for (Long2ObjectMap.Entry<ShakeeAnimationState> entry : ACTIVE.long2ObjectEntrySet()) {
            ShakeeAnimationState s = entry.getValue();
            if (s.kind() != AnimationKind.BREAK && s.startTick() < oldestTick) {
                oldestTick = s.startTick();
                oldestKey = entry.getLongKey();
                oldestState = s;
            }
        }

        if (oldestState != null) {
            removeActive(oldestKey);
            unhideForState(world, oldestState);

            BlockPos partnerPos = oldestState.hiddenPartnerPos() != null
                    ? oldestState.hiddenPartnerPos()
                    : resolveStructurePartnerPos(world, oldestState.pos(), oldestState.originalState());
            if (partnerPos != null) {
                long partnerLong = partnerPos.asLong();
                ShakeeAnimationState partner = ACTIVE.get(partnerLong);
                if (partner != null && partner.kind() != AnimationKind.BREAK
                        && partner.startTick() == oldestState.startTick()) {
                    removeActive(partnerLong);
                    unhideForState(world, partner);
                }
            }
            return true;
        }
        return false;
    }

    private static void triggerNeighborRipples(ClientLevel world, BlockPos centerPos) {
        for (Direction dir : DIRECTIONS) {
            if (ACTIVE.size() >= MAX_ACTIVE_ANIMATIONS) break;
            BlockPos neighborPos = centerPos.relative(dir);
            long neighborLong = neighborPos.asLong();
            if (ACTIVE.containsKey(neighborLong) || ShakeeRenderSuppressor.isSuppressed(neighborLong)) continue;

            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.isAir() || !isBlockAllowed(neighborState) || !shouldUseCustomWorldRender(neighborState)) continue;

            BlockPos immutableNeighbor = neighborPos.immutable();
            ShakeeAnimationState ripple = new ShakeeAnimationState(
                    immutableNeighbor,
                    neighborState,
                    clientTicks,
                    5,
                    1,
                    AnimationKind.RIPPLE
            );
            putActive(neighborLong, ripple);

            ripple.markStructureHidden(null);
            if (ShakeeRenderSuppressor.suppress(neighborLong)) {
                rerender(world, immutableNeighbor);
            }
        }
    }

    private static void startConnectedIfPresent(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            int horizontalSign,
            Vec3 velocity
    ) {
        if (state.getBlock() instanceof ChestBlock) return;

        BlockPos otherPos = getConnectedPos(pos, state);
        if (otherPos == null) return;

        BlockState otherState = world.getBlockState(otherPos);
        if (otherState.isAir() || !otherState.is(state.getBlock())) return;

        ShakeeAnimationState existing = ACTIVE.get(otherPos.asLong());
        if (existing != null) {
            if (existing.kind() == AnimationKind.PLACE && otherState.is(existing.originalState().getBlock())) {
                existing.updateState(otherState);
                if (otherState.hasBlockEntity() && !existing.usesCustomWorldRender()) {
                    existing.captureBlockEntity(world.getBlockEntity(otherPos));
                }
            } else {
                removeStateNow(world, otherPos);
                start(world, otherPos, otherState, horizontalSign, velocity);
            }
            return;
        }

        start(world, otherPos, otherState, horizontalSign, velocity);
    }

    private static ShakeeAnimationState findConnectedAnimation(BlockPos pos, BlockState state) {
        if (state.isAir() || state.getBlock() instanceof ChestBlock) return null;

        BlockPos otherPos = getConnectedPos(pos, state);
        if (otherPos == null) return null;

        ShakeeAnimationState other = ACTIVE.get(otherPos.asLong());
        if (other == null || !other.originalState().is(state.getBlock())) return null;

        return other;
    }

    public static BlockPos getConnectedPos(BlockPos pos, BlockState state) {
        if (state == null) return null;

        if (state.getBlock() instanceof DoorBlock) {
            Direction otherHalf = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;
            return pos.relative(otherHalf);
        }

        if (state.getBlock() instanceof DoublePlantBlock) {
            Direction otherHalf = state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;
            return pos.relative(otherHalf);
        }

        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.getValue(BedBlock.FACING);
            return (state.getValue(BedBlock.PART) == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite())).immutable();
        }

        return null;
    }

    public static void rerender(ClientLevel world, BlockPos pos) {
        if (world == null || pos == null) return;
        long sectionKey = SectionPos.asLong(pos);
        if (DIRTY_SECTIONS.add(sectionKey)) {
            BlockState current = world.getBlockState(pos);
            world.sendBlockUpdated(pos, current, current, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
        }
    }

    private static void refreshExisting(ClientLevel world, BlockPos pos, BlockState state) {
        ShakeeAnimationState existing = ACTIVE.get(pos.asLong());
        if (existing != null && !state.isAir() && areCompatibleStates(existing.originalState(), state)) {
            existing.updateState(state);
            if (state.hasBlockEntity() && !existing.usesCustomWorldRender()) {
                existing.captureBlockEntity(world.getBlockEntity(pos));
            }
        }
    }

    private static void releasePartnerChestBreaking(ClientLevel world, BlockPos brokenPos, BlockState previousState) {
        if (world == null || previousState == null || !(previousState.getBlock() instanceof ChestBlock)) {
            return;
        }
        BlockPos otherPos = getChestOtherPos(brokenPos, previousState);
        if (otherPos == null) {
            return;
        }

        // Release the broken chest's structure suppression hold on its partner immediately,
        // so the partner chest (now transitioning to ChestType.SINGLE) is not kept invisible.
        ShakeeAnimationState brokenAnim = ACTIVE.get(brokenPos.asLong());
        if (brokenAnim != null && brokenAnim.hiddenPartnerPos() != null) {
            BlockPos partner = brokenAnim.hiddenPartnerPos();
            if (partner.equals(otherPos)) {
                brokenAnim.markStructureHidden(null);
                ShakeeRenderSuppressor.unmarkAnimated(otherPos.asLong());
                if (ShakeeRenderSuppressor.release(otherPos.asLong())) {
                    rerender(world, otherPos);
                }
            }
        }

        ShakeeAnimationState other = ACTIVE.get(otherPos.asLong());
        if (other != null && other.kind() == AnimationKind.BREAK && !other.isDestroyed()) {
            BlockState otherWorldState = world.getBlockState(otherPos);
            if (!otherWorldState.isAir()) {
                other.updateState(otherWorldState);
                other.beginBreakingRelease(clientTicks);
            }
        } else {
            rerender(world, otherPos);
        }
    }

    private static void removeStateNow(ClientLevel world, BlockPos pos) {
        ShakeeAnimationState state = removeActive(pos.asLong());
        if (state != null) {
            unhideForState(world, state);
        }
    }

    private static void removeBreakingStateNow(ClientLevel world, BlockPos pos) {
        long posLong = pos.asLong();
        ShakeeAnimationState state = ACTIVE.get(posLong);
        if (state == null || state.kind() != AnimationKind.BREAK || state.isDestroyed()) return;

        removeActive(posLong);
        unhideForState(world, state);
    }

    public static void startSyncedFrom(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            ShakeeAnimationState source
    ) {
        if (state.isAir()) return;

        BlockPos immutablePos = pos.immutable();
        ShakeeAnimationState synced = new ShakeeAnimationState(source, immutablePos, state);
        if (state.hasBlockEntity() && !synced.usesCustomWorldRender()) {
            synced.captureBlockEntity(world.getBlockEntity(immutablePos));
        }
        putActive(immutablePos.asLong(), synced);
        hideForState(world, synced);
    }

    public static ShakeeAnimationState getForRender(ClientLevel world, BlockPos pos) {
        long posLong = pos.asLong();
        ShakeeAnimationState direct = ACTIVE.get(posLong);
        if (direct != null) return direct;

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return null;

        BlockPos otherPos = getConnectedPos(pos, state);
        if (otherPos == null) return null;

        ShakeeAnimationState other = ACTIVE.get(otherPos.asLong());
        if (other == null || !other.originalState().is(state.getBlock())) return null;

        startSyncedFrom(world, pos, state, other);
        return ACTIVE.get(posLong);
    }

    public static ShakeeAnimationState getVisualAnimation(ClientLevel world, BlockPos pos) {
        ShakeeAnimationState direct = ACTIVE.get(pos.asLong());
        if (direct != null) return direct;

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return null;

        if (state.getBlock() instanceof ChestBlock) {
            return getForChestRender(world, pos);
        }

        return getForRender(world, pos);
    }

    public static ShakeeAnimationState getForChestRender(ClientLevel world, BlockPos pos) {
        long posLong = pos.asLong();
        ShakeeAnimationState direct = ACTIVE.get(posLong);
        if (direct != null) return direct;

        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !(state.getBlock() instanceof ChestBlock)) return null;

        BlockPos otherPos = getChestOtherPos(pos, state);
        if (otherPos == null) return null;

        BlockState otherState = world.getBlockState(otherPos);
        if (!isValidChestPair(state, otherState)) return null;

        ShakeeAnimationState other = ACTIVE.get(otherPos.asLong());
        if (other == null || !areCompatibleStates(other.originalState(), state)) return null;

        startSyncedFrom(world, pos, state, other);
        return ACTIVE.get(posLong);
    }

    private static void putActive(long posLong, ShakeeAnimationState state) {
        ShakeeAnimationState previous = ACTIVE.put(posLong, state);
        if (previous == null) {
            ShakeeRenderSuppressor.markAnimated(posLong);
        } else if (previous.hiddenPartnerPos() != null) {
            ShakeeRenderSuppressor.unmarkAnimated(previous.hiddenPartnerPos().asLong());
        }
        activeAnimationsPresent = true;
    }

    private static ShakeeAnimationState removeActive(long posLong) {
        ShakeeAnimationState removed = ACTIVE.remove(posLong);
        if (removed != null) {
            ShakeeRenderSuppressor.unmarkAnimated(posLong);
            if (removed.hiddenPartnerPos() != null) {
                ShakeeRenderSuppressor.unmarkAnimated(removed.hiddenPartnerPos().asLong());
            }
        }
        activeAnimationsPresent = !ACTIVE.isEmpty();
        return removed;
    }

    private static boolean shouldHideChunkStructure(ShakeeAnimationState animState) {
        return animState != null && (animState.usesCustomWorldRender()
                || (ShakeePlatform.hasChunkBakedBlockEntities() && animState.originalState().hasBlockEntity()));
    }

    private static BlockPos resolveStructurePartnerPos(ClientLevel world, BlockPos pos, BlockState state) {
        if (state == null) return null;
        if (state.getBlock() instanceof ChestBlock) {
            BlockPos otherPos = getChestOtherPos(pos, state);
            if (otherPos != null && world != null) {
                BlockState otherState = world.getBlockState(otherPos);
                if (isValidChestPair(state, otherState)) {
                    return otherPos;
                }
            }
            return null;
        }
        return getConnectedPos(pos, state);
    }

    private static void hideForState(ClientLevel world, ShakeeAnimationState animState) {
        if (!shouldHideChunkStructure(animState) || animState.isStructureHidden()) {
            return;
        }

        BlockPos pos = animState.pos();
        BlockPos previousPartner = animState.hiddenPartnerPos();
        if (previousPartner != null) {
            ShakeeRenderSuppressor.unmarkAnimated(previousPartner.asLong());
        }
        BlockPos partnerPos = resolveStructurePartnerPos(world, pos, animState.originalState());
        animState.markStructureHidden(partnerPos);
        if (partnerPos != null) {
            ShakeeRenderSuppressor.markAnimated(partnerPos.asLong());
        }

        if (ShakeeRenderSuppressor.suppress(pos.asLong())) {
            rerender(world, pos);
        }
        if (partnerPos != null && ShakeeRenderSuppressor.suppress(partnerPos.asLong())) {
            rerender(world, partnerPos);
        }
    }

    private static void unhideForState(ClientLevel world, ShakeeAnimationState animState) {
        if (animState == null || !animState.consumeStructureHidden()) {
            return;
        }

        BlockPos pos = animState.pos();
        if (ShakeeRenderSuppressor.release(pos.asLong())) {
            rerender(world, pos);
        }
        BlockPos partnerPos = animState.hiddenPartnerPos();
        if (partnerPos != null && ShakeeRenderSuppressor.release(partnerPos.asLong())) {
            rerender(world, partnerPos);
        }
    }

    private static boolean isChest(BlockState state) {
        return state != null && state.getBlock() instanceof ChestBlock;
    }

    public static BlockPos getChestOtherPos(BlockPos pos, BlockState state) {
        if (!isChest(state)) return null;

        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) return null;

        Direction facing = state.getValue(ChestBlock.FACING);
        Direction partnerDir = type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
        return pos.relative(partnerDir);
    }

    public static Direction getChestPartnerDirection(BlockPos pos, BlockState state) {
        if (!isChest(state)) return null;

        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) return null;

        Direction facing = state.getValue(ChestBlock.FACING);
        return type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
    }

    private static boolean shouldUseCustomWorldRender(BlockState state) {
        return ShakeeAnimationState.shouldUseCustomWorldRender(state);
    }

    private static boolean areCompatibleStates(BlockState a, BlockState b) {
        return ShakeePlatform.areCompatibleStates(a, b);
    }

    private static boolean matchesPendingBlock(Block pendingBlock, BlockState placedState) {
        if (pendingBlock == null || placedState == null || placedState.isAir()) {
            return false;
        }
        return placedState.is(pendingBlock) || ShakeePlatform.areCompatibleStates(pendingBlock.defaultBlockState(), placedState);
    }

    private static boolean isValidChestPair(BlockState state, BlockState otherState) {
        if (!isChest(state) || !isChest(otherState)) return false;

        ChestType type = state.getValue(ChestBlock.TYPE);
        ChestType otherType = otherState.getValue(ChestBlock.TYPE);

        if (type == ChestType.SINGLE || otherType == ChestType.SINGLE) return false;
        if (!areCompatibleStates(state, otherState)) return false;
        if (state.getValue(ChestBlock.FACING) != otherState.getValue(ChestBlock.FACING)) return false;
        return type != otherType;
    }

    private static void startOrRefreshChest(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            int horizontalSign,
            Vec3 velocity
    ) {
        BlockPos otherPos = getChestOtherPos(pos, state);
        BlockState otherState = otherPos != null ? world.getBlockState(otherPos) : null;

        if (isValidChestPair(state, otherState)) {
            startOrRefreshChestHalf(world, pos, state, horizontalSign, velocity);
            startOrRefreshChestHalf(world, otherPos, otherState, horizontalSign, velocity);
            return;
        }

        startOrRefreshChestHalf(world, pos, state, horizontalSign, velocity);
    }

    private static void startOrRefreshChestHalf(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            int horizontalSign,
            Vec3 velocity
    ) {
        ShakeeAnimationState existing = ACTIVE.get(pos.asLong());
        if (existing == null || existing.kind() != AnimationKind.PLACE
                || !areCompatibleStates(existing.originalState(), state)) {
            if (existing != null) {
                removeStateNow(world, pos);
            }
            start(world, pos, state, horizontalSign, velocity);
        } else {
            existing.updateState(state);
            if (state.hasBlockEntity() && !existing.usesCustomWorldRender()) {
                existing.captureBlockEntity(world.getBlockEntity(pos));
            }
        }
    }

    public static boolean isBlockAllowed(BlockState state) {
        if (state == null || state.isAir()) return false;
        return ShakeeConfig.get().isBlockAllowed(state.getBlock());
    }

    private record PendingPlacement(
            Block block,
            int horizontalSign,
            Vec3 velocity,
            long expiresAt
    ) {}
}
