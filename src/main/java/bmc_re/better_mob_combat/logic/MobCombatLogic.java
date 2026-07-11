package bmc_re.better_mob_combat.logic;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobCombatState;
import bmc_re.better_mob_combat.config.BMCConfig;
import bmc_re.better_mob_combat.network.BMCNetwork;
import net.bettercombat.BetterCombatMod;
import net.bettercombat.api.AttackHand;
import net.bettercombat.api.WeaponAttributes;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MobCombatLogic {
    private static final ResourceLocation DAMAGE_MODIFIER_ID = BetterMobCombatReimagined.id("attack_damage_multiplier");

    private MobCombatLogic() {
    }

    public static boolean isEligible(Mob mob) {
        if (!BMCConfig.ENABLED.get() || mob.level().isClientSide || !mob.isAlive()) {
            return false;
        }
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (BMCConfig.ENTITY_BLACKLIST.get().contains(entityId.toString())) {
            return false;
        }
        return MobAttackSelector.hasCombatWeapon(mob);
    }

    public static void playFallbackMeleeAnimation(Mob mob) {
        if (!BMCConfig.ENABLED.get()
                || !BMCConfig.ENABLE_FALLBACK_MELEE_ANIMATIONS.get()
                || mob.level().isClientSide
                || !mob.isAlive()) {
            return;
        }

        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (BMCConfig.ENTITY_BLACKLIST.get().contains(entityId.toString())) {
            return;
        }

        ItemStack stack = mob.getMainHandItem();
        if (stack.getItem() instanceof ProjectileWeaponItem) {
            return;
        }

        String animation = stack.isEmpty()
                ? "bettercombat:one_handed_punch"
                : "bettercombat:one_handed_slash_horizontal_right";
        BMCNetwork.sendAttackAnimation(
                mob,
                animation,
                false,
                false,
                12.0F,
                0.5F,
                0.06F
        );
    }

    public static boolean beginAttack(Mob mob, Entity intendedTarget) {
        if (!isEligible(mob)) {
            return false;
        }

        MobCombatState state = (MobCombatState) mob;
        if (state.bmc$getPendingAttack() != null || state.bmc$getAttackCooldown() > 0) {
            return true;
        }

        AttackHand hand = MobAttackSelector.select(mob, state.bmc$getComboCount());
        if (hand == null || hand.attack() == null || hand.attack().animation() == null || hand.attack().animation().isBlank()) {
            return false;
        }

        // AI range checks can be stale by the time doHurtTarget is invoked. Revalidate against the
        // selected combo attack before broadcasting an animation or beginning a windup.
        if (!(intendedTarget instanceof LivingEntity livingTarget)
                || !validTarget(mob, livingTarget)
                || !isWithinAttackStartRange(mob, livingTarget, hand)) {
            return true;
        }

        int startDelay = BMCConfig.ATTACK_START_DELAY.get();
        state.bmc$setPendingAttack(hand);
        state.bmc$setIntendedTargetId(intendedTarget.getId());
        state.bmc$setAttackStartDelayTicks(startDelay);
        state.bmc$setWindupTicks(0);
        state.bmc$setComboResetTicks(BMCConfig.COMBO_RESET_TICKS.get());

        if (startDelay <= 0) {
            startPendingAttack(mob, state, hand);
        }
        return true;
    }

    public static void tick(Mob mob) {
        MobCombatState state = (MobCombatState) mob;

        if (state.bmc$getAttackCooldown() > 0) {
            state.bmc$setAttackCooldown(state.bmc$getAttackCooldown() - 1);
        }

        if (state.bmc$getComboResetTicks() > 0) {
            state.bmc$setComboResetTicks(state.bmc$getComboResetTicks() - 1);
            if (state.bmc$getComboResetTicks() == 0 && state.bmc$getPendingAttack() == null) {
                state.bmc$setComboCount(0);
            }
        }

        AttackHand pending = state.bmc$getPendingAttack();
        if (pending == null) {
            return;
        }

        ItemStack currentStack = pending.isOffHand() ? mob.getOffhandItem() : mob.getMainHandItem();
        if (!ItemStack.isSameItemSameComponents(currentStack, pending.itemStack()) || mob.isDeadOrDying()) {
            cancelPending(state);
            return;
        }

        Entity intendedTarget = resolveIntendedTarget(mob, state.bmc$getIntendedTargetId());
        if (BMCConfig.TRACK_TARGET_DURING_WINDUP.get()
                && intendedTarget instanceof LivingEntity livingTarget
                && livingTarget.isAlive()) {
            mob.getLookControl().setLookAt(livingTarget, 30.0F, 30.0F);
        }

        int startDelay = state.bmc$getAttackStartDelayTicks();
        if (startDelay > 0) {
            startDelay--;
            state.bmc$setAttackStartDelayTicks(startDelay);
            if (startDelay > 0) {
                return;
            }
            // Begin the visible upswing after the original commitment delay. Do not consume the
            // first windup tick here: the animation packet starts at this boundary, so waiting the
            // full impactTick count keeps server damage on the client strike frame.
            startPendingAttack(mob, state, pending);
            return;
        }

        // Match the original Better Mob Combat counter semantics: consume one complete
        // windup tick, then apply damage only after the counter reaches zero. Resolving at
        // one makes the server hit a frame early, which is especially obvious on fast axes.
        int windup = Math.max(0, state.bmc$getWindupTicks() - 1);
        state.bmc$setWindupTicks(windup);
        if (windup > 0) {
            return;
        }

        performAttack(mob, pending, intendedTarget);
        state.bmc$setPendingAttack(null);
        state.bmc$setIntendedTargetId(-1);
        state.bmc$setComboCount(state.bmc$getComboCount() + 1);
        state.bmc$setComboResetTicks(BMCConfig.COMBO_RESET_TICKS.get());
    }

    public static int attackIntervalForCurrentWeapon(Mob mob) {
        MobCombatState state = (MobCombatState) mob;
        AttackHand hand = MobAttackSelector.select(mob, state.bmc$getComboCount());
        if (hand == null) {
            return 20;
        }
        return BMCConfig.ATTACK_START_DELAY.get()
                + MobCombatMath.attackIntervalTicks(mob, hand)
                + BMCConfig.ADDITIONAL_ATTACK_COOLDOWN.get();
    }

    public static boolean isWithinWeaponRange(Mob mob, LivingEntity target) {
        AttackHand hand = MobAttackSelector.select(mob, ((MobCombatState) mob).bmc$getComboCount());
        return hand != null && isWithinAttackStartRange(mob, target, hand);
    }

    private static boolean isWithinAttackStartRange(Mob mob, LivingEntity target, AttackHand hand) {
        double fullRange = MobCombatMath.attackRange(mob, hand);
        double startRange = Math.max(0.5D, fullRange * BMCConfig.ATTACK_START_RANGE_MULTIPLIER.get());
        return insideAttackShape(mob, target, hand.attack(), startRange);
    }

    private static void cancelPending(MobCombatState state) {
        state.bmc$setPendingAttack(null);
        state.bmc$setAttackStartDelayTicks(0);
        state.bmc$setWindupTicks(0);
        state.bmc$setIntendedTargetId(-1);
        state.bmc$setWeaponOverride(null);
    }

    private static void startPendingAttack(Mob mob, MobCombatState state, AttackHand hand) {
        float animationLength = MobCombatMath.attackDurationTicks(mob, hand);
        int interval = MobCombatMath.attackIntervalTicks(mob, hand);
        state.bmc$setAttackCooldown(interval + BMCConfig.ADDITIONAL_ATTACK_COOLDOWN.get());
        state.bmc$setWindupTicks(MobCombatMath.impactTick(mob, hand));

        BMCNetwork.sendAttackAnimation(
                mob,
                hand.attack().animation(),
                hand.isOffHand(),
                hand.attributes().isTwoHanded(),
                animationLength,
                MobCombatMath.animationUpswing(hand),
                MobCombatMath.synchronizedDamageUpswing(mob, hand)
        );
        playConfiguredSound(mob, hand.attack().swingSound());
    }

    @Nullable
    private static Entity resolveIntendedTarget(Mob mob, int id) {
        return id < 0 ? null : mob.level().getEntity(id);
    }

    private static void performAttack(Mob mob, AttackHand hand, @Nullable Entity intendedTarget) {
        List<LivingEntity> targets = findTargets(mob, hand, intendedTarget);
        boolean hitAnything = false;
        for (LivingEntity target : targets) {
            hitAnything |= invokeVanillaAttack(mob, target, hand);
        }
        if (hitAnything) {
            playConfiguredSound(mob, hand.attack().impactSound());
        }
        // Match the original mod: a committed attack counts as activity even if the target moved
        // outside the authored hit shape during the upswing.
        mob.setNoActionTime(0);
    }

    private static List<LivingEntity> findTargets(Mob mob, AttackHand hand, @Nullable Entity intendedTarget) {
        double range = MobCombatMath.attackRange(mob, hand);
        AABB searchBox = mob.getBoundingBox().inflate(range, Math.max(1.5D, range), range);
        List<LivingEntity> candidates = new ArrayList<>(mob.level().getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                target -> validTarget(mob, target) && insideAttackShape(mob, target, hand.attack(), range)
        ));

        candidates.sort(Comparator
                .<LivingEntity>comparingInt(target -> target == intendedTarget ? 0 : 1)
                .thenComparingDouble(mob::distanceToSqr));

        int maxTargets = BMCConfig.MAX_TARGETS.get();
        return candidates.size() > maxTargets ? new ArrayList<>(candidates.subList(0, maxTargets)) : candidates;
    }

    private static boolean validTarget(Mob mob, LivingEntity target) {
        if (target == mob || !target.isAlive() || target.isSpectator()) {
            return false;
        }
        if (!BMCConfig.ALLOW_FRIENDLY_FIRE.get() && mob.isAlliedTo(target)) {
            return false;
        }
        return !BMCConfig.REQUIRE_LINE_OF_SIGHT.get() || mob.hasLineOfSight(target);
    }

    private static boolean insideAttackShape(Mob mob, LivingEntity target, WeaponAttributes.Attack attack, double range) {
        return MobAttackCollision.intersects(mob, target, attack, range);
    }

    private static boolean invokeVanillaAttack(Mob mob, LivingEntity target, AttackHand hand) {
        MobCombatState state = (MobCombatState) mob;
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        AttributeModifierSwap offHandSwap = hand.isOffHand()
                ? AttributeModifierSwap.install(mob, mob.getMainHandItem(), hand.itemStack())
                : AttributeModifierSwap.EMPTY;

        double multiplier = Math.max(0.0D, hand.attack().damageMultiplier());
        if (damage != null) {
            damage.removeModifier(DAMAGE_MODIFIER_ID);
            if (multiplier != 1.0D) {
                damage.addTransientModifier(new AttributeModifier(
                        DAMAGE_MODIFIER_ID,
                        multiplier - 1.0D,
                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                ));
            }
        }

        try {
            if (hand.isOffHand()) {
                state.bmc$setWeaponOverride(hand.itemStack());
            }
            // Better Combat's fast-attack option bypasses LivingEntity hurt throttling. The original
            // Better Mob Combat did the same before delegating to the mob's vanilla attack method.
            if (BetterCombatMod.getConfig() != null && BetterCombatMod.getConfig().allow_fast_attacks) {
                target.invulnerableTime = 0;
            }
            state.bmc$setCallingVanillaAttack(true);
            return mob.doHurtTarget(target);
        } finally {
            state.bmc$setCallingVanillaAttack(false);
            state.bmc$setWeaponOverride(null);
            if (damage != null) {
                damage.removeModifier(DAMAGE_MODIFIER_ID);
            }
            offHandSwap.restore();
        }
    }

    private static void playConfiguredSound(Mob mob, @Nullable WeaponAttributes.Sound soundData) {
        if (soundData == null || soundData.id() == null || soundData.id().isBlank()) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(soundData.id());
        if (id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (sound == null) {
            return;
        }
        float randomness = Math.max(0.0F, soundData.randomness());
        float pitch = soundData.pitch() + (mob.getRandom().nextFloat() * 2.0F - 1.0F) * randomness;
        mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), sound, mob.getSoundSource(), soundData.volume(), pitch);
    }

    private record ModifierBinding(AttributeInstance instance, AttributeModifier modifier) {
        ModifierKey key() {
            return new ModifierKey(this.instance, this.modifier.id());
        }
    }

    private record ModifierKey(AttributeInstance instance, ResourceLocation id) {
    }

    /**
     * Makes an off-hand weapon behave as though it occupied MAINHAND for the duration of the vanilla
     * mob attack without changing equipment slots. This preserves enchantments and knockback while
     * avoiding equipment-change events, sounds and client flicker.
     */
    private static final class AttributeModifierSwap {
        private static final AttributeModifierSwap EMPTY = new AttributeModifierSwap(Set.of(), Map.of());

        private final Set<ModifierKey> touched;
        private final Map<ModifierKey, AttributeModifier> previous;

        private AttributeModifierSwap(Set<ModifierKey> touched, Map<ModifierKey, AttributeModifier> previous) {
            this.touched = touched;
            this.previous = previous;
        }

        static AttributeModifierSwap install(Mob mob, ItemStack currentMainHand, ItemStack replacement) {
            List<ModifierBinding> current = collectRelevantMainHandModifiers(mob, currentMainHand);
            List<ModifierBinding> desired = collectRelevantMainHandModifiers(mob, replacement);
            Set<ModifierKey> touched = new LinkedHashSet<>();
            current.forEach(binding -> touched.add(binding.key()));
            desired.forEach(binding -> touched.add(binding.key()));

            Map<ModifierKey, AttributeModifier> previous = new LinkedHashMap<>();
            for (ModifierKey key : touched) {
                AttributeModifier existing = key.instance().getModifier(key.id());
                if (existing != null) {
                    previous.put(key, existing);
                }
                key.instance().removeModifier(key.id());
            }
            for (ModifierBinding binding : desired) {
                binding.instance().addTransientModifier(binding.modifier());
            }
            return new AttributeModifierSwap(touched, previous);
        }

        void restore() {
            for (ModifierKey key : this.touched) {
                key.instance().removeModifier(key.id());
            }
            for (Map.Entry<ModifierKey, AttributeModifier> entry : this.previous.entrySet()) {
                entry.getKey().instance().addTransientModifier(entry.getValue());
            }
        }

        private static List<ModifierBinding> collectRelevantMainHandModifiers(Mob mob, ItemStack stack) {
            List<ModifierBinding> bindings = new ArrayList<>();
            stack.forEachModifier(EquipmentSlot.MAINHAND, (Holder<Attribute> attribute, AttributeModifier modifier) -> {
                if (!attribute.is(Attributes.ATTACK_DAMAGE) && !attribute.is(Attributes.ATTACK_KNOCKBACK)) {
                    return;
                }
                AttributeInstance instance = mob.getAttribute(attribute);
                if (instance != null) {
                    bindings.add(new ModifierBinding(instance, modifier));
                }
            });
            return bindings;
        }
    }
}
