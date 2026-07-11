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
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(BetterMobCombatReimagined.MOD_ID)
public final class BetterMobCombatReimagined {
    public static final String MOD_ID = "better_mob_combat_reimagined";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BetterMobCombatReimagined(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, BMCConfig.SPEC);
        modBus.addListener(BMCNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(CommonEvents::onEntityJoinLevel);
        LOGGER.info("Loading Better Mob Combat: Reimagined for NeoForge 1.21.1");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
