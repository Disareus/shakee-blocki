package com.shakeeblocki.animation;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Thread-safe registry governing block render suppression and off-thread neighbor face queries.
 * Uses primitive fastutil collections guarded by an explicit monitor and lock-free empty flags
 * so async chunk builder threads never touch client-thread animation maps or box coordinates.
 */
public final class ShakeeRenderSuppressor {
    private static final Object LOCK = new Object();
    private static final Long2IntOpenHashMap SUPPRESSED = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap ANIMATED = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap SUPPRESSED_SECTIONS = new Long2IntOpenHashMap();
    private static volatile boolean anySuppressed = false;
    private static volatile boolean anyAnimatedOrSuppressed = false;

    private ShakeeRenderSuppressor() {}

    private static long sectionKeyFromBlockPos(long posLong) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(BlockPos.getX(posLong)),
                SectionPos.blockToSectionCoord(BlockPos.getY(posLong)),
                SectionPos.blockToSectionCoord(BlockPos.getZ(posLong))
        );
    }

    public static boolean suppress(long posLong) {
        synchronized (LOCK) {
            int count = SUPPRESSED.get(posLong);
            SUPPRESSED.put(posLong, count + 1);
            if (count == 0) {
                long secKey = sectionKeyFromBlockPos(posLong);
                SUPPRESSED_SECTIONS.put(secKey, SUPPRESSED_SECTIONS.get(secKey) + 1);
            }
            anySuppressed = true;
            anyAnimatedOrSuppressed = true;
            return count == 0;
        }
    }

    public static boolean release(long posLong) {
        synchronized (LOCK) {
            int count = SUPPRESSED.get(posLong);
            if (count <= 0) {
                return false;
            }
            if (count == 1) {
                SUPPRESSED.remove(posLong);
                long secKey = sectionKeyFromBlockPos(posLong);
                int secCount = SUPPRESSED_SECTIONS.get(secKey);
                if (secCount <= 1) {
                    SUPPRESSED_SECTIONS.remove(secKey);
                } else {
                    SUPPRESSED_SECTIONS.put(secKey, secCount - 1);
                }
                anySuppressed = !SUPPRESSED.isEmpty();
                anyAnimatedOrSuppressed = anySuppressed || !ANIMATED.isEmpty();
                return true;
            }
            SUPPRESSED.put(posLong, count - 1);
            return false;
        }
    }

    public static void markAnimated(long posLong) {
        synchronized (LOCK) {
            int count = ANIMATED.get(posLong);
            ANIMATED.put(posLong, count + 1);
            anyAnimatedOrSuppressed = true;
        }
    }

    public static void unmarkAnimated(long posLong) {
        synchronized (LOCK) {
            int count = ANIMATED.get(posLong);
            if (count <= 0) {
                return;
            }
            if (count == 1) {
                ANIMATED.remove(posLong);
                anyAnimatedOrSuppressed = !SUPPRESSED.isEmpty() || !ANIMATED.isEmpty();
            } else {
                ANIMATED.put(posLong, count - 1);
            }
        }
    }

    public static boolean isSuppressed(long posLong) {
        if (!anySuppressed) {
            return false;
        }
        synchronized (LOCK) {
            return SUPPRESSED.containsKey(posLong);
        }
    }

    public static boolean isAnimatedOrSuppressed(long posLong) {
        if (!anyAnimatedOrSuppressed) {
            return false;
        }
        synchronized (LOCK) {
            return SUPPRESSED.containsKey(posLong) || ANIMATED.containsKey(posLong);
        }
    }

    public static boolean isSectionSuppressed(int secX, int secY, int secZ) {
        if (!anySuppressed) {
            return false;
        }
        long secKey = SectionPos.asLong(secX, secY, secZ);
        synchronized (LOCK) {
            return SUPPRESSED_SECTIONS.containsKey(secKey);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            SUPPRESSED.clear();
            ANIMATED.clear();
            SUPPRESSED_SECTIONS.clear();
            anySuppressed = false;
            anyAnimatedOrSuppressed = false;
        }
    }
}
