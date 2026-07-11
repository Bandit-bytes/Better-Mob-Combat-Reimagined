package bmc_re.better_mob_combat.logic;

import net.bettercombat.api.AttackHand;
import net.bettercombat.api.ComboState;
import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.logic.WeaponRegistry;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class MobAttackSelector {
    private MobAttackSelector() {
    }

    public static boolean hasCombatWeapon(LivingEntity entity) {
        WeaponAttributes attributes = WeaponRegistry.getAttributes(entity.getMainHandItem());
        return hasAttacks(attributes);
    }

    public static boolean isDualWielding(LivingEntity entity) {
        WeaponAttributes main = WeaponRegistry.getAttributes(entity.getMainHandItem());
        WeaponAttributes off = WeaponRegistry.getAttributes(entity.getOffhandItem());
        return hasAttacks(main) && !main.isTwoHanded() && hasAttacks(off) && !off.isTwoHanded();
    }

    public static boolean shouldUseOffHand(LivingEntity entity, int comboCount) {
        return isDualWielding(entity) && Math.floorMod(comboCount, 2) == 1;
    }

    @Nullable
    public static AttackHand select(LivingEntity entity, int comboCount) {
        boolean dualWielding = isDualWielding(entity);
        boolean offHand = dualWielding && shouldUseOffHand(entity, comboCount);
        ItemStack stack = offHand ? entity.getOffhandItem() : entity.getMainHandItem();
        WeaponAttributes attributes = WeaponRegistry.getAttributes(stack);
        if (!hasAttacks(attributes)) {
            return null;
        }

        int handCombo = dualWielding ? Math.max(0, comboCount - (offHand ? 1 : 0)) / 2 : Math.max(0, comboCount);
        WeaponAttributes.Attack[] validAttacks = Arrays.stream(attributes.attacks())
                .filter(attack -> attack != null && conditionsPass(attack.conditions(), entity, offHand))
                .toArray(WeaponAttributes.Attack[]::new);
        if (validAttacks.length == 0) {
            return null;
        }

        int index = Math.floorMod(handCombo, validAttacks.length);
        return new AttackHand(validAttacks[index], new ComboState(index + 1, validAttacks.length), offHand, attributes, stack);
    }

    private static boolean hasAttacks(@Nullable WeaponAttributes attributes) {
        return attributes != null && attributes.attacks() != null && attributes.attacks().length > 0;
    }

    private static boolean conditionsPass(@Nullable WeaponAttributes.Condition[] conditions, LivingEntity entity, boolean offHandAttack) {
        return conditions == null || conditions.length == 0 || Arrays.stream(conditions)
                .allMatch(condition -> conditionPasses(condition, entity, offHandAttack));
    }

    private static boolean conditionPasses(@Nullable WeaponAttributes.Condition condition, LivingEntity entity, boolean offHandAttack) {
        if (condition == null) {
            return true;
        }

        return switch (condition) {
            case NOT_DUAL_WIELDING -> !isDualWielding(entity);
            case DUAL_WIELDING_ANY -> isDualWielding(entity);
            case DUAL_WIELDING_SAME -> isDualWielding(entity)
                    && entity.getMainHandItem().is(entity.getOffhandItem().getItem());
            case DUAL_WIELDING_SAME_CATEGORY -> sameWeaponCategory(entity);
            case NO_OFFHAND_ITEM -> entity.getOffhandItem().isEmpty();
            case OFF_HAND_SHIELD -> entity.getOffhandItem().getItem() instanceof ShieldItem;
            case MAIN_HAND_ONLY -> !offHandAttack;
            case OFF_HAND_ONLY -> offHandAttack;
            case MOUNTED -> entity.isPassenger();
            case NOT_MOUNTED -> !entity.isPassenger();
        };
    }

    private static boolean sameWeaponCategory(LivingEntity entity) {
        if (!isDualWielding(entity)) {
            return false;
        }
        WeaponAttributes main = WeaponRegistry.getAttributes(entity.getMainHandItem());
        WeaponAttributes off = WeaponRegistry.getAttributes(entity.getOffhandItem());
        return main != null && off != null && main.category() != null && !main.category().isBlank()
                && main.category().equals(off.category());
    }
}
