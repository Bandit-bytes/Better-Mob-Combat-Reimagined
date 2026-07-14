package bmc_re.better_mob_combat.logic;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobCombatState;
import bmc_re.better_mob_combat.config.BMCConfig;
import bmc_re.better_mob_combat.network.BMCNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visual-only bridge for modded mobs that bypass vanilla {@link Mob#doHurtTarget(Entity)}.
 *
 * <p>Some custom entities own their complete melee routine. Better Mob Combat's normal server
 * mixin never sees those attacks, but its global vanilla-swing suppression still used to remove
 * their visible swing. This class recognizes that path and broadcasts only a client animation. It
 * never replaces damage, reach, cooldowns, target selection, or special mechanics from the other
 * mod.</p>
 */
public final class ExternalAttackCompat {
    private static final Map<UUID, Long> LAST_VISUAL_TICK = new HashMap<>();
    private static final Map<Class<?>, Boolean> CUSTOM_MELEE_OVERRIDE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_BRIDGES = ConcurrentHashMap.newKeySet();
    private static final int DEDUPE_TICKS = 10;

    private ExternalAttackCompat() {
    }

    public static boolean hasCustomMeleeOverride(Mob mob) {
        return CUSTOM_MELEE_OVERRIDE_CACHE.computeIfAbsent(mob.getClass(), type -> {
            try {
                Method method = type.getMethod("doHurtTarget", Entity.class);
                Class<?> owner = method.getDeclaringClass();
                return owner != Mob.class && !owner.getName().startsWith("net.minecraft.");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        });
    }

    /**
     * True for custom melee implementations and for the Dreadknight's generated attack path.
     * The registry-name check keeps this compatibility optional and avoids linking against the
     * other mod's classes.
     */
    public static boolean needsVisualBridge(Mob mob) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (id != null) {
            String namespace = id.getNamespace().replace("_", "").replace("-", "")
                    .toLowerCase(Locale.ROOT);
            String path = id.getPath().replace("_", "").replace("-", "")
                    .toLowerCase(Locale.ROOT);
            if (namespace.contains("undeadunleashed") && path.contains("dreadknight")) {
                return true;
            }
        }

        // Keep the generic path narrow: only custom melee implementations that are actually
        // holding a Better Combat-resolved weapon should be converted to visual-only mode.
        try {
            return hasCustomMeleeOverride(mob) && MobAttackSelector.hasCombatWeapon(mob);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Called from the swing mixin before BMC suppresses vanilla's arm swing. */
    public static boolean handleSwing(Mob mob, InteractionHand requestedHand) {
        if (!needsVisualBridge(mob) || !play(mob, requestedHand, "swing")) {
            return false;
        }
        // Treat this mob's original melee method as authoritative. If its override delegates to
        // Mob#doHurtTarget, the normal BMC injection must not replace the custom hit after this
        // visual-only packet has already been sent.
        ((MobCombatState) mob).bmc$setCallingVanillaAttack(true);
        return true;
    }

    /** Safety net for custom attack procedures that deal direct melee damage without swing(). */
    public static void handleSuccessfulMeleeDamage(Mob mob) {
        if (needsVisualBridge(mob) && !isRecent(mob)) {
            play(mob, InteractionHand.MAIN_HAND, "damage");
        }
    }

    private static boolean play(Mob mob, InteractionHand requestedHand, String trigger) {
        if (!BMCConfig.ENABLED.get() || mob.level().isClientSide || !mob.isAlive()
                || BMCConfig.isBlacklisted(mob.getType())) {
            return false;
        }

        MobCombatState state = (MobCombatState) mob;
        if (state.bmc$getPendingAttack() != null || isRecent(mob)) {
            return false;
        }

        boolean selectedBetterCombatAnimation =
                playSelectedBetterCombatAnimation(mob, state.bmc$getComboCount(), requestedHand);
        if (!selectedBetterCombatAnimation) {
            ItemStack stack = requestedHand == InteractionHand.OFF_HAND
                    ? mob.getOffhandItem()
                    : mob.getMainHandItem();
            if (stack.getItem() instanceof ProjectileWeaponItem
                    || !BMCConfig.ENABLE_FALLBACK_MELEE_ANIMATIONS.get()) {
                return false;
            }
            BMCNetwork.sendAttackAnimation(
                    mob,
                    stack.isEmpty()
                            ? "bettercombat:one_handed_punch"
                            : "bettercombat:one_handed_slash_horizontal_right",
                    requestedHand == InteractionHand.OFF_HAND,
                    false,
                    12.0F,
                    0.5F,
                    0.08F
            );
        }

        LAST_VISUAL_TICK.put(mob.getUUID(), mob.level().getGameTime());
        String logKey = mob.getType() + "|" + mob.getClass().getName() + "|" + trigger
                + "|" + selectedBetterCombatAnimation;
        if (LOGGED_BRIDGES.add(logKey)) {
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC external-attack] mob={} class={} trigger={} selectedBetterCombatAnimation={}",
                    mob.getType(),
                    mob.getClass().getName(),
                    trigger,
                    selectedBetterCombatAnimation
            );
        }
        return true;
    }

    /** True while a custom mob may still call through to super.doHurtTarget after its own swing. */
    public static boolean isRecent(Mob mob) {
        Long tick = LAST_VISUAL_TICK.get(mob.getUUID());
        if (tick == null) {
            return false;
        }
        long age = mob.level().getGameTime() - tick;
        if (age >= DEDUPE_TICKS || age < 0L) {
            LAST_VISUAL_TICK.remove(mob.getUUID());
            return false;
        }
        return true;
    }

    /**
     * Reflects over BMC's already-loaded selector so this optional compatibility bridge does not
     * add another hard linkage to Better Combat's implementation classes.
     */
    private static boolean playSelectedBetterCombatAnimation(
            Mob mob,
            int comboCount,
            InteractionHand requestedHand
    ) {
        try {
            Method select = MobAttackSelector.class.getMethod("select", LivingEntity.class, int.class);
            Object hand = select.invoke(null, mob, comboCount);
            if (hand == null) {
                return false;
            }

            Method isOffHand = hand.getClass().getMethod("isOffHand");
            if (requestedHand == InteractionHand.OFF_HAND && !Boolean.TRUE.equals(isOffHand.invoke(hand))) {
                Object alternate = select.invoke(null, mob, comboCount + 1);
                if (alternate != null && Boolean.TRUE.equals(isOffHand.invoke(alternate))) {
                    hand = alternate;
                }
            }

            Object attack = hand.getClass().getMethod("attack").invoke(hand);
            Object attributes = hand.getClass().getMethod("attributes").invoke(hand);
            if (attack == null || attributes == null) {
                return false;
            }

            String animation = (String) attack.getClass().getMethod("animation").invoke(attack);
            if (animation == null || animation.isBlank()) {
                return false;
            }

            Class<?> attackHandClass = hand.getClass();
            float length = ((Number) MobCombatMath.class
                    .getMethod("attackDurationTicks", Mob.class, attackHandClass)
                    .invoke(null, mob, hand)).floatValue();
            float animationUpswing = ((Number) MobCombatMath.class
                    .getMethod("animationUpswing", attackHandClass)
                    .invoke(null, hand)).floatValue();
            float damageUpswing = ((Number) MobCombatMath.class
                    .getMethod("synchronizedDamageUpswing", Mob.class, attackHandClass)
                    .invoke(null, mob, hand)).floatValue();
            boolean offHand = Boolean.TRUE.equals(isOffHand.invoke(hand));
            boolean twoHanded = Boolean.TRUE.equals(attributes.getClass().getMethod("isTwoHanded").invoke(attributes));

            BMCNetwork.sendAttackAnimation(
                    mob,
                    animation,
                    offHand,
                    twoHanded,
                    length,
                    animationUpswing,
                    damageUpswing
            );
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }
}
