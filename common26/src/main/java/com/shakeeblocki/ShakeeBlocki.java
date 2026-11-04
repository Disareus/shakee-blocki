package com.shakeeblocki;

import com.shakeeblocki.animation.ShakeeAnimationManager;
import com.shakeeblocki.animation.ShakeeAnimationRenderer;
import com.shakeeblocki.config.ShakeeConfig;
import com.shakeeblocki.platform.ShakeePlatform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.ChestBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShakeeBlocki implements ClientModInitializer {
    public static final String MOD_ID = "shakee_blocki";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        ShakeePlatform.init(
                loader.getConfigDir(),
                ShakeeAnimationRenderer::hasBakedModelGeometry,
                (a, b) -> a.is(b.getBlock()) || (a.getBlock() instanceof ChestBlock chest && chest.chestCanConnectTo(b))
        );
        ShakeePlatform.setChunkBakedBlockEntities(
                loader.isModLoaded("betterblockentities") || loader.isModLoaded("enhancedblockentities")
        );
        ShakeeConfig.load();
        LOGGER.info("Shakee Blocki initialized!");

        LevelRenderEvents.COLLECT_SUBMITS.register(ShakeeAnimationRenderer::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ShakeeAnimationManager.tick();
            if (client.level == null) {
                ShakeeAnimationRenderer.clearPool();
            }
        });
    }
}
