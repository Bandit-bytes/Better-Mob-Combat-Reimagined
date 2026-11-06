package bmc_re.better_mob_combat.network;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.RangedWeaponKind;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class BMCNetwork {
    private static final String PROTOCOL_VERSION = "3";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            BetterMobCombatReimagined.id("main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static boolean registered;

    private BMCNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        int id = 0;
        CHANNEL.registerMessage(
                id++,
                MobAttackPayload.class,
                MobAttackPayload::write,
                MobAttackPayload::decode,
                MobAttackPayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                id++,
                MobRangedReleasePayload.class,
                MobRangedReleasePayload::write,
                MobRangedReleasePayload::decode,
                MobRangedReleasePayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void sendRangedRelease(Mob mob, RangedWeaponKind kind, boolean offHand) {
        CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> mob),
                MobRangedReleasePayload.create(mob.getId(), kind, offHand)
        );
    }

    public static void sendAttackAnimation(
            Mob mob,
            String animationId,
            boolean offHand,
            boolean twoHanded,
            float length,
            float animationUpswing,
            float damageUpswing
    ) {
        CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> mob),
                new MobAttackPayload(
                        mob.getId(),
                        animationId,
                        offHand,
                        twoHanded,
                        length,
                        animationUpswing,
                        damageUpswing
                )
        );
    }
}
