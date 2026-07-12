package bmc_re.better_mob_combat.event;

import bmc_re.better_mob_combat.api.RangedWeaponKind;
import bmc_re.better_mob_combat.config.BMCConfig;
import bmc_re.better_mob_combat.network.BMCNetwork;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public final class CommonEvents {
    private CommonEvents() {
    }

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // enableRangedAnimations is a CLIENT option now: whether to *play* the release animation is
        // a per-player cosmetic choice, so the server always broadcasts the release and the client
        // decides. The server's job here is only the authoritative gate - master switch + blacklist.
        if (event.getLevel().isClientSide || !BMCConfig.ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof Projectile projectile)) {
            return;
        }

        Entity owner = projectile.getOwner();
        if (!(owner instanceof Mob mob) || !mob.isAlive()) {
            return;
        }

        // Previously missing: a blacklisted skeleton still got the enhanced bow release animation,
        // because the only blacklist checks lived in the melee path it never entered.
        if (BMCConfig.isBlacklisted(mob.getType())) {
            return;
        }

        WeaponContext weapon = classify(mob.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (weapon == null) {
            weapon = classify(mob.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        if (weapon == null) {
            return;
        }

        BMCNetwork.sendRangedRelease(
                mob,
                weapon.kind(),
                weapon.hand() == InteractionHand.OFF_HAND
        );
    }

    private static WeaponContext classify(ItemStack stack, InteractionHand hand) {
        Item item = stack.getItem();
        if (item instanceof CrossbowItem) {
            return new WeaponContext(RangedWeaponKind.CROSSBOW, hand);
        }
        if (item instanceof BowItem) {
            return new WeaponContext(RangedWeaponKind.BOW, hand);
        }
        if (item instanceof TridentItem) {
            return new WeaponContext(RangedWeaponKind.SPEAR, hand);
        }
        if (item instanceof ProjectileWeaponItem) {
            return new WeaponContext(RangedWeaponKind.GENERIC, hand);
        }
        return null;
    }

    private record WeaponContext(RangedWeaponKind kind, InteractionHand hand) {
    }
}