package com.shakeeblocki.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class ShakeeMixinPlugin implements IMixinConfigPlugin {
    private static final boolean HAS_SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    private static final boolean HAS_BBE = FabricLoader.getInstance().isModLoaded("betterblockentities");
    private static final boolean HAS_IMMERSIVE_VFX = FabricLoader.getInstance().isModLoaded("immersivevfx");
    private static final boolean HAS_INDIGO = isIndigoActive();
    // Minecraft 26.2 changed the LevelRenderer submit API (renderHitOutline ->
    // submitBlockOutline, submitMovingBlock gained the outline color parameter), so
    // outline mixins are split into per-version compat packages and gated here.
    private static final Optional<Boolean> MINECRAFT_26_2_OR_NEWER = detectMinecraft26_2OrNewer();

    private static boolean isIndigoActive() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("fabric-renderer-indigo")) {
            return false;
        }
        // Matches IndigoMixinConfigPlugin: custom renderers (e.g. Sodium) disable Indigo via this metadata key.
        for (ModContainer container : loader.getAllMods()) {
            if (container.getMetadata().containsCustomValue("fabric-renderer-api-v1:contains_renderer")) {
                return false;
            }
        }
        return true;
    }

    private static boolean targetClassExists(String targetClassName) {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Optional<Boolean> detectMinecraft26_2OrNewer() {
        try {
            Version baseline;
            try {
                baseline = Version.parse("26.2");
            } catch (Exception e) {
                return Optional.empty();
            }
            return FabricLoader.getInstance().getModContainer("minecraft").flatMap(container -> {
                try {
                    return Optional.of(Version.parse(
                            container.getMetadata().getVersion().getFriendlyString()));
                } catch (Exception e) {
                    return Optional.<Version>empty();
                }
            }).map(version -> version.compareTo(baseline) >= 0);
        } catch (Exception e) {
            return Optional.empty();
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
        if (mixinClassName.contains(".indigo.")) {
            return HAS_INDIGO && targetClassExists(targetClassName);
        }
        if (mixinClassName.contains(".bbe.")) {
            return HAS_BBE && targetClassExists(targetClassName);
        }
        if (mixinClassName.contains(".immersivevfx.")) {
            return HAS_IMMERSIVE_VFX && targetClassExists(targetClassName);
        }
        if (mixinClassName.endsWith(".WorldRendererOutlineMixin261")) {
            return MINECRAFT_26_2_OR_NEWER.isPresent() && !MINECRAFT_26_2_OR_NEWER.get();
        }
        if (mixinClassName.endsWith(".WorldRendererOutlineMixin262")) {
            return MINECRAFT_26_2_OR_NEWER.isPresent() && MINECRAFT_26_2_OR_NEWER.get();
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
