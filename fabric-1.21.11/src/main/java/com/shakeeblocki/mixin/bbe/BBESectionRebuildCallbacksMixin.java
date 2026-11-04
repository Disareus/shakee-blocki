package com.shakeeblocki.mixin.bbe;

import betterblockentities.client.chunk.section.SectionRebuildCallbacks;
import com.shakeeblocki.animation.ShakeeRenderSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Better Block Entities from consuming WAITING_TERRAIN callbacks during suppressed chunk meshing passes.
 * If a chunk section is meshed while blocks in it are suppressed by Shakee, the resulting mesh
 * lacks block entity quads; letting BBE mark terrainMeshReady=true causes premature or permanent invisibility.
 */
@Mixin(value = SectionRebuildCallbacks.class, remap = false)
public abstract class BBESectionRebuildCallbacksMixin {
    @Inject(method = "fireIfWaiting", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void shakee$preventPrematureMeshReady(long key, CallbackInfo ci) {
        // Decode BBE section key:
        // ((x & 0x3FFFFF) << 42) | ((y & 0xFFFFF) << 22) | (z & 0x3FFFFF)
        int x = (int) (key >> 42);
        int y = (int) ((key << 22) >> 44);
        int z = (int) ((key << 42) >> 42);
        if (ShakeeRenderSuppressor.isSectionSuppressed(x, y, z)) {
            ci.cancel();
        }
    }
}
