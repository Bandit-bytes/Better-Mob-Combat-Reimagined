package bmc_re.better_mob_combat;

import bmc_re.better_mob_combat.config.BMCConfig;
import bmc_re.better_mob_combat.event.CommonEvents;
import bmc_re.better_mob_combat.network.BMCNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(BetterMobCombatReimagined.MOD_ID)
public final class BetterMobCombatReimagined {
    public static final String MOD_ID = "better_mob_combat_reimagined";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BetterMobCombatReimagined() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BMCConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, BMCConfig.CLIENT_SPEC);

        modBus.addListener(BetterMobCombatReimagined::onCommonSetup);
        modBus.addListener(BetterMobCombatReimagined::onConfigLoading);
        modBus.addListener(BetterMobCombatReimagined::onConfigReloading);

        MinecraftForge.EVENT_BUS.addListener(CommonEvents::onEntityJoinLevel);
        MinecraftForge.EVENT_BUS.addListener(CommonEvents::onLivingDamage);
        LOGGER.info("Loading Better Mob Combat: Reimagined for Forge 1.20.1");
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(BMCNetwork::register);
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
        return new ResourceLocation(MOD_ID, path);
    }
}
