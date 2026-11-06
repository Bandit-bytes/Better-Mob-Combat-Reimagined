package bmc_re.better_mob_combat.logic;

import bmc_re.better_mob_combat.config.BMCConfig;
import net.bettercombat.BetterCombat;
import net.bettercombat.api.AttackHand;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class MobCombatMath {
    private static final double DEFAULT_ATTACK_RANGE = 3.0D;

    private MobCombatMath() {
    }

    public static float attackDurationTicks(Mob mob, AttackHand hand) {
        double attackSpeed;
        if (!hand.isOffHand() && mob.getAttribute(Attributes.ATTACK_SPEED) != null) {
            attackSpeed = mob.getAttributeValue(Attributes.ATTACK_SPEED);
        } else {
            attackSpeed = itemAttackSpeed(hand.itemStack(), EquipmentSlot.MAINHAND);
        }
        double exactDuration = 20.0D / Math.max(0.1D, attackSpeed);
        double minimum = BMCConfig.MINIMUM_ATTACK_INTERVAL.get();
        if (BetterCombat.config == null || !BetterCombat.config.allow_fast_attacks) {
            minimum = Math.max(minimum, 10.0D);
        }

        return (float) Math.max(minimum, exactDuration);
    }

    public static int attackIntervalTicks(Mob mob, AttackHand hand) {
        return Math.max(1, Math.round(attackDurationTicks(mob, hand)));
    }

    public static int impactTick(Mob mob, AttackHand hand) {
        float duration = attackDurationTicks(mob, hand);
        int interval = attackIntervalTicks(mob, hand);
        int impact = Math.max(1, Mth.ceil(duration * adjustedUpswing(hand)));
        return Mth.clamp(impact, 1, interval);
    }

    public static float synchronizedDamageUpswing(Mob mob, AttackHand hand) {
        float duration = attackDurationTicks(mob, hand);
        return Mth.clamp(impactTick(mob, hand) / duration, 0.01F, 0.99F);
    }

    public static int windupTicks(Mob mob, AttackHand hand) {
        return impactTick(mob, hand);
    }

    public static float animationUpswing(AttackHand hand) {
        return (float) Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D);
    }

    public static float adjustedUpswing(AttackHand hand) {
        // AttackHand#upswingRate already applies Better Combat's global upswing multiplier.
        // Do not force the result back up to the raw weapon JSON value: doing so makes mobs hit
        // substantially later than players when an attack defines a large custom upswing (for
        // example 1.0 with Better Combat's 0.5 global multiplier). The BMC setting is an additive
        // bonus to that multiplier, matching the original config description.
        double rawUpswing = Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D);
        double additional = rawUpswing * BMCConfig.ADDITIONAL_UPSWING_MULTIPLIER.get();
        double configured = hand.upswingRate() + additional;
        return (float) Mth.clamp(configured, 0.01D, 1.0D);
    }

    public static float totalUpswingMultiplier(AttackHand hand) {
        double rawUpswing = Math.max(0.0001D, Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D));
        return (float) Mth.clamp(adjustedUpswing(hand) / rawUpswing, 0.2D, 1.0D);
    }

    public static double attackRange(Mob mob, AttackHand hand) {
        // Better Combat 1.x (Minecraft 1.20.1) stores the absolute reach in the weapon attributes.
        // There is no per-attack range multiplier or interaction-range attribute on this version.
        double range = hand.attributes().attackRange();
        if (range <= 0.0D) {
            range = DEFAULT_ATTACK_RANGE;
        }
        return Math.max(0.5D, range + BMCConfig.ADDITIONAL_ATTACK_RANGE.get());
    }

    private static double itemAttackSpeed(ItemStack stack, EquipmentSlot slot) {
        double base = 4.0D;
        List<AttributeModifier> modifiers = new ArrayList<>(stack.getAttributeModifiers(slot).get(Attributes.ATTACK_SPEED));
        return Math.max(0.1D, applyModifiers(base, modifiers));
    }

    private static double applyModifiers(double base, List<AttributeModifier> modifiers) {
        double add = 0.0D;
        double addBase = 0.0D;
        double multiplyTotal = 1.0D;
        for (AttributeModifier modifier : modifiers) {
            switch (modifier.getOperation()) {
                case ADDITION -> add += modifier.getAmount();
                case MULTIPLY_BASE -> addBase += modifier.getAmount();
                case MULTIPLY_TOTAL -> multiplyTotal *= 1.0D + modifier.getAmount();
            }
        }
        return (base + add + base * addBase) * multiplyTotal;
    }
}
