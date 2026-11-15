package com.shakeeblocki.mixin;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * Gates optional Sodium and Embeddium mixins on NeoForge.
 * Uses LoadingModList because ModList is not yet initialized when FML loads mixin configs.
 */
public class ShakeeNeoForgeMixinPlugin implements IMixinConfigPlugin {
    private static final boolean HAS_SODIUM = isModPresent("sodium");
    private static final boolean HAS_EMBEDDIUM = isModPresent("embeddium");
    private static final boolean HAS_IMMERSIVE_VFX = isModPresent("immersivevfx");

    private static boolean isModPresent(String modId) {
        LoadingModList loadingList = LoadingModList.get();
        if (loadingList != null) {
            return loadingList.getModFileById(modId) != null;
        }
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(modId);
    }

    private static boolean targetClassExists(String targetClassName) {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".sodium.")) {
            return HAS_SODIUM && targetClassExists(targetClassName);
        }
        if (mixinClassName.contains(".embeddium.")) {
            return HAS_EMBEDDIUM && targetClassExists(targetClassName);
        }
        if (mixinClassName.contains(".immersivevfx.")) {
            return HAS_IMMERSIVE_VFX && targetClassExists(targetClassName);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
