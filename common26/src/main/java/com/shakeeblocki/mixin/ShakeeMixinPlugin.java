package com.shakeeblocki.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class ShakeeMixinPlugin implements IMixinConfigPlugin {
    private static final boolean HAS_SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    private static final boolean HAS_BBE = FabricLoader.getInstance().isModLoaded("betterblockentities");
    // Minecraft 26.2 changed the LevelRenderer submit API (renderHitOutline ->
    // submitBlockOutline, submitMovingBlock gained the outline color parameter), so
    // outline mixins are split into per-version compat packages and gated here.
    private static final Optional<Boolean> MINECRAFT_26_2_OR_NEWER = detectMinecraft26_2OrNewer();

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
            return HAS_SODIUM;
        }
        if (mixinClassName.contains(".bbe.")) {
            return HAS_BBE;
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
