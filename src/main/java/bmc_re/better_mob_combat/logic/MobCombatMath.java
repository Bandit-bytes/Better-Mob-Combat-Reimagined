package bmc_re.better_mob_combat.logic;

import bmc_re.better_mob_combat.config.BMCConfig;
import net.bettercombat.api.AttackHand;
import net.bettercombat.api.WeaponAttributes;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
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

    /**
     * Exact player-style attack duration before Minecraft's integer server ticks quantize it.
     * Keeping this as a float is important: Better Combat's animation speed and upswing point are
     * both fractional, while damage can only be applied on a whole server tick.
     */
    public static float attackDurationTicks(Mob mob, AttackHand hand) {
        double attackSpeed;
        if (!hand.isOffHand() && mob.getAttribute(Attributes.ATTACK_SPEED) != null) {
            // Match the original 1.20.1 port: use the mob's live value so entity-specific base
            // attack speed and the equipped main-hand weapon's modifiers are both respected.
            attackSpeed = mob.getAttributeValue(Attributes.ATTACK_SPEED);
        } else {
            // Off-hand attributes are not normally installed on a mob. Evaluate that item's
            // MAINHAND modifiers against the player-style base value, just as Better Combat does.
            attackSpeed = itemAttackSpeed(hand.itemStack(), EquipmentSlot.MAINHAND);
        }
        double exactDuration = 20.0D / Math.max(0.1D, attackSpeed);
        return (float) Math.max(BMCConfig.MINIMUM_ATTACK_INTERVAL.get(), exactDuration);
    }

    /**
     * Whole-tick cooldown used by mob AI. Match the original Better Mob Combat behavior by rounding
     * the exact duration rather than always rounding upward.
     */
    public static int attackIntervalTicks(Mob mob, AttackHand hand) {
        return Math.max(1, Math.round(attackDurationTicks(mob, hand)));
    }

    /**
     * Whole server tick on which the hitbox is sampled and damage is applied.
     */
    public static int impactTick(Mob mob, AttackHand hand) {
        float duration = attackDurationTicks(mob, hand);
        int interval = attackIntervalTicks(mob, hand);
        int impact = Math.round(duration * adjustedUpswing(hand));
        return Mth.clamp(impact, 1, interval);
    }

    /**
     * Client animation progress corresponding to the quantized server impact tick. Sending this
     * effective value removes sub-tick drift between the keyframed strike pose and damage.
     */
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
        // AttackHand.upswingRate() already includes Better Combat's global upswing setting.
        // The old port used this computed value and clamped it to at least 20% of the swing.
        return (float) Mth.clamp(
                hand.upswingRate() * BMCConfig.UPSWING_MULTIPLIER.get(),
                0.2D,
                1.0D
        );
    }

    public static double attackRange(Mob mob, AttackHand hand) {
        WeaponAttributes attributes = hand.attributes();
        double legacyAbsoluteRange = attributes.attackRange();
        if (legacyAbsoluteRange > 0.0D) {
            return Math.max(0.5D, legacyAbsoluteRange * Math.max(0.05F, hand.attack().rangeMultiplier()));
        }

        double interactionRange = BMCConfig.FALLBACK_BASE_RANGE.get();
        if (mob.getAttribute(Attributes.ENTITY_INTERACTION_RANGE) != null) {
            interactionRange = mob.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        }

        // Better Combat's range_bonus is a fallback for items that do not already provide Minecraft's
        // ENTITY_INTERACTION_RANGE component. Adding both double-counts reach on many 1.21 weapons.
        double range = itemHasInteractionRangeModifier(hand.itemStack())
                ? interactionRange
                : interactionRange + attributes.rangeBonus();
        return Math.max(0.5D, range * Math.max(0.05F, hand.attack().rangeMultiplier()));
    }

    private static boolean itemHasInteractionRangeModifier(ItemStack stack) {
        var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return false;
        }
        return modifiers.modifiers().stream()
                .anyMatch(entry -> entry.attribute().is(Attributes.ENTITY_INTERACTION_RANGE));
    }

    private static double itemAttackSpeed(ItemStack stack, EquipmentSlot slot) {
        // Player-style attack speed starts at 4.0. Weapon attribute modifiers then alter it.
        double base = 4.0D;
        List<AttributeModifier> modifiers = new ArrayList<>();
        stack.forEachModifier(slot, (Holder<Attribute> attribute, AttributeModifier modifier) -> {
            if (attribute.is(Attributes.ATTACK_SPEED)) {
                modifiers.add(modifier);
            }
        });

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
        return Math.max(0.1D, (base + add + base * addBase) * multiplyTotal);
    }
}
