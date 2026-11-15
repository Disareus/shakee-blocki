package com.shakeeblocki.platform;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Minimal cross-platform adapter. Must be initialized exactly once during client
 * startup by the loader-specific entry point (Fabric initializer or NeoForge @Mod hook).
 */
public final class ShakeePlatform {
    private static volatile Path configDir = null;
    private static volatile Predicate<BlockState> bakedModelGeometryPredicate =
            state -> state != null && state.getRenderShape() == RenderShape.MODEL;
    private static volatile BiPredicate<BlockState, BlockState> chestCompatibilityPredicate =
            (a, b) -> a != null && b != null && a.is(b.getBlock());
    private static volatile boolean chunkBakedBlockEntities = false;

    private ShakeePlatform() {}

    public static void init(Path configDirPath, Predicate<BlockState> bakedModelCheck) {
        init(configDirPath, bakedModelCheck, null);
    }

    public static void init(
            Path configDirPath,
            Predicate<BlockState> bakedModelCheck,
            BiPredicate<BlockState, BlockState> chestCompatibilityCheck
    ) {
        configDir = configDirPath;
        if (bakedModelCheck != null) {
            bakedModelGeometryPredicate = bakedModelCheck;
        }
        if (chestCompatibilityCheck != null) {
            chestCompatibilityPredicate = chestCompatibilityCheck;
        }
    }

    public static Path configDir() {
        Path dir = configDir;
        if (dir == null) {
            throw new IllegalStateException("ShakeePlatform was not initialized by the loader entry point");
        }
        return dir;
    }

    /**
     * Checks whether a block state has baked quad geometry in the chunk mesh.
     * In Minecraft 1.21.5+, pure BlockEntity-rendered blocks (chests, signs, beds, skulls,
     * banners, shulker boxes, decorated pots) report {@link RenderShape#MODEL} with an empty
     * particle-only model, whereas utility/furniture blocks (furnaces, smokers, blast furnaces,
     * barrels, lecterns, brewing stands, modded furniture) bake real quads into chunk sections.
     */
    public static boolean hasBakedModelGeometry(BlockState state) {
        return state != null && bakedModelGeometryPredicate.test(state);
    }

    /**
     * Checks whether two block states belong to the same block or compatible chest family
     * (e.g. CopperChestBlock oxidation/waxing transitions in 1.21.9+).
     */
    public static boolean areCompatibleStates(BlockState a, BlockState b) {
        return a != null && b != null && chestCompatibilityPredicate.test(a, b);
    }

    /**
     * Marks whether an optimization mod (e.g. Better Block Entities or Enhanced Block Entities)
     * bakes BlockEntity geometry directly into chunk terrain meshes even when the vanilla
     * BlockStateModel reports zero quads.
     */
    public static void setChunkBakedBlockEntities(boolean enabled) {
        chunkBakedBlockEntities = enabled;
    }

    public static boolean hasChunkBakedBlockEntities() {
        return chunkBakedBlockEntities;
    }
}
