package bmc_re.better_mob_combat.logic;

import bmc_re.better_mob_combat.config.BMCConfig;
import net.bettercombat.BetterCombatMod;
import net.bettercombat.api.AttackHand;
import net.bettercombat.api.WeaponAttributes;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class MobCombatMath {
    private MobCombatMath() {
    }

    /** Exact player-style attack duration before integer server-tick quantization. */
    public static float attackDurationTicks(Mob mob, AttackHand hand) {
        double attackSpeed;
        if (!hand.isOffHand() && mob.getAttribute(Attributes.ATTACK_SPEED) != null) {
            attackSpeed = mob.getAttributeValue(Attributes.ATTACK_SPEED);
        } else {
            attackSpeed = itemAttackSpeed(hand.itemStack(), EquipmentSlot.MAINHAND);
        }
        double exactDuration = 20.0D / Math.max(0.1D, attackSpeed);
        double minimum = BMCConfig.MINIMUM_ATTACK_INTERVAL.get();

        // When Better Combat fast attacks are disabled, Minecraft keeps a hurt target invulnerable
        // for 10 ticks. Starting another visible combo swing sooner guarantees an apparent contact
        // with no damage. Respect that window instead of playing attacks vanilla will reject.
        if (BetterCombatMod.getConfig() == null || !BetterCombatMod.getConfig().allow_fast_attacks) {
            minimum = Math.max(minimum, 10.0D);
        }

        return (float) Math.max(minimum, exactDuration);
    }

    /** Match the original mod's rounded whole-tick weapon cooldown. */
    public static int attackIntervalTicks(Mob mob, AttackHand hand) {
        return Math.max(1, Math.round(attackDurationTicks(mob, hand)));
    }

    /**
     * Match Better Mob Combat 1.20.1: damage is committed when the rounded upswing counter reaches
     * zero. There is no extra packet/render compensation tick.
     */
    public static int impactTick(Mob mob, AttackHand hand) {
        float duration = attackDurationTicks(mob, hand);
        int interval = attackIntervalTicks(mob, hand);
        int impact = Math.max(1, Mth.ceil(duration * adjustedUpswing(hand)));
        return Mth.clamp(impact, 1, interval);
    }

    /** Client tipping point corresponding to the exact server impact tick. */
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
        double rawUpswing = Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D);
        double additional = rawUpswing * BMCConfig.ADDITIONAL_UPSWING_MULTIPLIER.get();
        double configured = hand.upswingRate() + additional;
        // Never resolve server damage before the animation's authored impact fraction. Current
        // Better Combat may expose a lower hand upswing rate (for example 0.20 for an axe whose
        // animation impact is authored at 0.30), which otherwise makes the player take damage
        // several frames before the visible weapon arrives.
        return (float) Mth.clamp(Math.max(rawUpswing, configured), 0.2D, 1.0D);
    }

    public static float totalUpswingMultiplier(AttackHand hand) {
        double rawUpswing = Math.max(0.0001D, Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D));
        return (float) Mth.clamp(adjustedUpswing(hand) / rawUpswing, 0.2D, 1.0D);
    }

    /**
     * Adapt Better Combat's current range model to the original Better Mob Combat baseline.
     *
     * <p>In the 1.20.1 presets, common weapons authored an absolute 2.5-block attack range. Current
     * Better Combat presets instead author a range bonus around the player's interaction range. A mob
     * must not inherit the player's 3.0-block base, but a width-derived fallback is far too short and
     * erases the weapon preset. Use the original 2.5-block baseline, then apply the current weapon's
     * interaction-range modifiers or Better Combat range bonus. Legacy/modded absolute ranges remain
     * authoritative.</p>
     */
    public static double attackRange(Mob mob, AttackHand hand) {
        WeaponAttributes attributes = hand.attributes();
        double rangeMultiplier = Math.max(0.05F, hand.attack().rangeMultiplier());

        double legacyAbsoluteRange = attributes.attackRange();
        if (legacyAbsoluteRange > 0.0D) {
            return Math.max(0.5D, legacyAbsoluteRange * rangeMultiplier);
        }

        ItemStack stack = hand.itemStack();
        double range = itemHasModifier(stack, Attributes.ENTITY_INTERACTION_RANGE)
                ? applyItemModifiers(2.5D, stack, EquipmentSlot.MAINHAND, Attributes.ENTITY_INTERACTION_RANGE)
                : 2.5D + attributes.rangeBonus();

        return Math.max(0.5D, range * rangeMultiplier);
    }

    private static boolean itemHasModifier(ItemStack stack, Holder<Attribute> searchedAttribute) {
        final boolean[] found = {false};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(searchedAttribute)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static double applyItemModifiers(
            double base,
            ItemStack stack,
            EquipmentSlot slot,
            Holder<Attribute> searchedAttribute
    ) {
        List<AttributeModifier> modifiers = new ArrayList<>();
        stack.forEachModifier(slot, (attribute, modifier) -> {
            if (attribute.equals(searchedAttribute)) {
                modifiers.add(modifier);
            }
        });
        return applyModifiers(base, modifiers);
    }

    private static double itemAttackSpeed(ItemStack stack, EquipmentSlot slot) {
        double base = 4.0D;
        List<AttributeModifier> modifiers = new ArrayList<>();
        stack.forEachModifier(slot, (Holder<Attribute> attribute, AttributeModifier modifier) -> {
            if (attribute.is(Attributes.ATTACK_SPEED)) {
                modifiers.add(modifier);
            }
        });
        return Math.max(0.1D, applyModifiers(base, modifiers));
    }

    private static double applyModifiers(double base, List<AttributeModifier> modifiers) {
        double add = 0.0D;
        double addBase = 0.0D;
        double multiplyTotal = 1.0D;
        for (AttributeModifier modifier : modifiers) {
            switch (modifier.operation()) {
                case ADD_VALUE -> add += modifier.amount();
                case ADD_MULTIPLIED_BASE -> addBase += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> multiplyTotal *= 1.0D + modifier.amount();
            }
        }
        return (base + add + base * addBase) * multiplyTotal;
    }
}
