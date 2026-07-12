package bmc_re.better_mob_combat;

import bmc_re.better_mob_combat.config.BMCConfig;
import bmc_re.better_mob_combat.event.CommonEvents;
import bmc_re.better_mob_combat.network.BMCNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(BetterMobCombatReimagined.MOD_ID)
public final class BetterMobCombatReimagined {
    public static final String MOD_ID = "better_mob_combat_reimagined";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BetterMobCombatReimagined(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, BMCConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, BMCConfig.CLIENT_SPEC);

        modBus.addListener(BMCNetwork::registerPayloads);
        modBus.addListener(BetterMobCombatReimagined::onConfigLoading);
        modBus.addListener(BetterMobCombatReimagined::onConfigReloading);

        NeoForge.EVENT_BUS.addListener(CommonEvents::onEntityJoinLevel);
        LOGGER.info("Loading Better Mob Combat: Reimagined for NeoForge 1.21.1");
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        rebuildIfOurs(event.getConfig());
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        rebuildIfOurs(event.getConfig());
    }

    private static void rebuildIfOurs(ModConfig config) {
        if (config.getSpec() == BMCConfig.SERVER_SPEC) {
            BMCConfig.rebuildCaches();
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}