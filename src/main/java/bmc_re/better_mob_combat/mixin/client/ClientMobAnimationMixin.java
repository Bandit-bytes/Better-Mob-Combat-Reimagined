package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobAnimationAccess;
import bmc_re.better_mob_combat.config.BMCConfig;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.client.animation.AttackAnimationSubStack;
import net.bettercombat.client.animation.CustomAnimationPlayer;
import net.bettercombat.client.animation.PoseSubStack;
import net.bettercombat.client.animation.modifier.TransmissionSpeedModifier;
import net.bettercombat.logic.WeaponRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the embedded Mob Player Animator stack and adds Better Combat's pose and attack layers.
 * Implementing Player Animator's IAnimatedPlayer contract is the critical part that lets its
 * existing HumanoidModel armor/bend pipeline treat mobs exactly like animated players.
 *
 * <p>The lower-priority pose layers mirror Better Combat's player idle-pose system. This is what
 * gives two-handed swords, bows, crossbows and other attributed weapons their intended stance
 * between attacks. The attack layer remains at priority 2000 so swings always override the pose.</p>
 */
@Mixin(Mob.class)
public abstract class ClientMobAnimationMixin extends LivingEntity implements MobAnimationAccess, IAnimatedPlayer {
    @Unique
    private static final Set<ResourceLocation> BMC$MISSING_ANIMATIONS = new HashSet<>();

    /** Embedded Mob Player Animator storage. */
    @Unique
    private final Map<ResourceLocation, IAnimation> bmc$associatedAnimations = new HashMap<>();

    @Unique
    private final AnimationStack bmc$animationStack = new AnimationStack();

    @Unique
    private final AnimationApplier bmc$animationApplier = new AnimationApplier(this.bmc$animationStack);

    @Unique
    private final PoseSubStack bmc$mainHandItemPose = new PoseSubStack(null, false, true);

    @Unique
    private final PoseSubStack bmc$mainHandBodyPose = new PoseSubStack(null, true, true);

    @Unique
    private final PoseSubStack bmc$offHandItemPose = new PoseSubStack(null, false, true);

    @Unique
    private final PoseSubStack bmc$offHandBodyPose = new PoseSubStack(null, true, false);

    @Unique
    private final AttackAnimationSubStack bmc$attackAnimation =
            new AttackAnimationSubStack(new AdjustmentModifier(this::bmc$attackAdjustment));

    @Unique
    private float bmc$renderPartialTick;

    protected ClientMobAnimationMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bmc$initializeMobAnimationStack(EntityType<?> type, Level level, CallbackInfo ci) {
        if (level.isClientSide) {
            this.bmc$animationStack.addAnimLayer(1, this.bmc$offHandItemPose.base);
            this.bmc$animationStack.addAnimLayer(2, this.bmc$offHandBodyPose.base);
            this.bmc$animationStack.addAnimLayer(3, this.bmc$mainHandItemPose.base);
            this.bmc$animationStack.addAnimLayer(4, this.bmc$mainHandBodyPose.base);
            this.bmc$animationStack.addAnimLayer(2000, this.bmc$attackAnimation.base);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void bmc$tickMobAnimationStack(CallbackInfo ci) {
        if (this.level().isClientSide) {
            this.bmc$animationStack.tick();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void bmc$refreshMobWeaponPoses(CallbackInfo ci) {
        if (this.level().isClientSide) {
            this.bmc$updateWeaponPoses();
        }
    }

    @Override
    public AnimationStack bmc$getAnimationStack() {
        return this.bmc$animationStack;
    }

    @Override
    public AnimationStack getAnimationStack() {
        return this.bmc$animationStack;
    }

    @Override
    public AnimationApplier playerAnimator_getAnimation() {
        return this.bmc$animationApplier;
    }

    @Override
    public @Nullable IAnimation playerAnimator_getAnimation(@NotNull ResourceLocation id) {
        return this.bmc$associatedAnimations.get(id);
    }

    @Override
    public @Nullable IAnimation playerAnimator_setAnimation(
            @NotNull ResourceLocation id,
            @Nullable IAnimation animation
    ) {
        return animation == null
                ? this.bmc$associatedAnimations.remove(id)
                : this.bmc$associatedAnimations.put(id, animation);
    }

    @Override
    public float bmc$getRenderPartialTick() {
        return this.bmc$renderPartialTick;
    }

    @Override
    public void bmc$setRenderPartialTick(float partialTick) {
        this.bmc$renderPartialTick = partialTick;
    }

    @Override
    public boolean bmc$isAttackAnimationActive() {
        // Query the real animation player's own state instead of a hand-maintained tick counter.
        // A counter computed once from the intended length can drift out of sync with how the
        // animation actually plays once TransmissionSpeedModifier gears and fade-in easing are
        // involved - if it reaches zero even slightly before the real animation finishes, every
        // WrapWithCondition/ModifyVariable gated on this flips back to idle behavior mid-swing,
        // which shows up as the swing visibly cutting off partway through and snapping back to
        // the idle weapon-hold pose. This mirrors the original 1.20.1 mod's
        // bettermobcombat$hasActiveAttackAnimation(), which checks the same way.
        return this.bmc$attackAnimation.base.getAnimation() != null
                && this.bmc$attackAnimation.base.getAnimation().isActive();
    }

    @Override
    public void bmc$playAttackAnimation(
            String animationId,
            boolean offHand,
            boolean twoHanded,
            float length,
            float animationUpswing,
            float damageUpswing
    ) {
        if (!this.level().isClientSide) {
            return;
        }

        ResourceLocation id = ResourceLocation.tryParse(animationId);
        if (id == null) {
            BetterMobCombatReimagined.LOGGER.warn("Ignoring invalid Better Combat animation id '{}'", animationId);
            return;
        }

        KeyframeAnimation animation = this.bmc$getKeyframeAnimation(id, "attack");
        if (animation == null) {
            return;
        }

        try {
            // The vanilla swing flag is intentionally suppressed by the server combat mixin, so
            // clear Better Combat's idle grip explicitly before starting the high-priority attack.
            // Otherwise the idle item transform and the attack item transform stack together and
            // Fresh Animations makes the held axe appear to corkscrew around the arm.
            this.bmc$clearWeaponPoses(((Mob) (Object) this).isLeftHanded());

            KeyframeAnimation.AnimationBuilder copy = animation.mutableCopy();
            copy.torso.fullyEnablePart(true);
            copy.head.pitch.setEnabled(false);

            float safeLength = Math.max(1.0F, length);
            float sourceUpswing = Mth.clamp(animationUpswing, 0.01F, 0.99F);
            float targetUpswing = Mth.clamp(damageUpswing, 0.01F, 0.99F);

            // Visual-only fine adjustment: shift exactly where in the client's playback the swing
            // appears to reach its hit pose, without touching the server's damage timing at all
            // (that's locked to whole ticks and isn't adjustable more finely than that). Expressed in
            // ticks so it reads intuitively next to the rest of this mod's tick-based timing.
            float nudgeTicks = BMCConfig.CLIENT_HIT_TIMING_NUDGE_TICKS.get().floatValue();
            if (nudgeTicks != 0.0F) {
                targetUpswing = Mth.clamp(targetUpswing + nudgeTicks / safeLength, 0.01F, 0.99F);
            }

            float baseSpeed = Math.max(0.01F, (float) animation.endTick / safeLength);
            float upswingSpeed = Math.max(0.01F, baseSpeed * sourceUpswing / targetUpswing);
            float downwindSpeed = Math.max(0.01F, baseSpeed * (1.0F - sourceUpswing) / (1.0F - targetUpswing));

            // Reach the animation's original tipping point exactly when the server applies damage,
            // then consume the remaining keyframes over the rest of the weapon cooldown.
            this.bmc$attackAnimation.speed.set(
                    upswingSpeed,
                    List.of(
                            new TransmissionSpeedModifier.Gear(safeLength * targetUpswing, downwindSpeed),
                            new TransmissionSpeedModifier.Gear(safeLength, baseSpeed)
                    )
            );

            boolean mirror = offHand;
            if (((Mob) (Object) this).isLeftHanded()) {
                mirror = !mirror;
            }
            this.bmc$attackAnimation.mirror.setEnabled(mirror);

            CustomAnimationPlayer player = new CustomAnimationPlayer(copy.build(), 0);
            player.setFirstPersonMode(FirstPersonMode.NONE);
            // Blending in from the idle pose takes real time on top of the upswing/downswing speed
            // math above, which assumes the attack pose is playing at full weight from tick 0. A long
            // fade-in (e.g. driven by this animation's own beginTick) pushes the visible swing later
            // than the server's actual damage tick, showing up as "hit sound plays, then the axe
            // catches up a moment later." Cap it to a couple of ticks - enough to avoid a hard snap
            // from the idle pose, but small enough not to visibly desync from the impact.
            int fadeIn = Mth.clamp(copy.beginTick, 1, 2);
            this.bmc$attackAnimation.base.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(fadeIn, Ease.INOUTSINE),
                    player
            );
        } catch (RuntimeException exception) {
            BetterMobCombatReimagined.LOGGER.error("Failed to play mob attack animation '{}'", id, exception);
        }
    }

    @Unique
    private void bmc$updateWeaponPoses() {
        Mob mob = (Mob) (Object) this;
        boolean leftHanded = mob.isLeftHanded();

        if (!BMCConfig.ENABLED.get() || !BMCConfig.ENABLE_WEAPON_IDLE_POSES.get()) {
            this.bmc$clearWeaponPoses(leftHanded);
            return;
        }

        ItemStack mainHand = this.getMainHandItem();
        ItemStack offHand = this.getOffhandItem();
        if (this.bmc$isAttackAnimationActive()
                || this.swinging
                || this.isSwimming()
                || this.isUsingItem()
                || this.isFallFlying()
                || CrossbowItem.isCharged(mainHand)
                || CrossbowItem.isCharged(offHand)
                || ((mainHand.getItem() instanceof ProjectileWeaponItem
                || offHand.getItem() instanceof ProjectileWeaponItem) && mob.isAggressive())) {
            this.bmc$clearWeaponPoses(leftHanded);
            return;
        }

        WeaponAttributes mainAttributes = WeaponRegistry.getAttributes(mainHand);
        WeaponAttributes offHandAttributes = WeaponRegistry.getAttributes(offHand);

        KeyframeAnimation mainPose = this.bmc$getPose(mainAttributes == null ? null : mainAttributes.pose());
        KeyframeAnimation offHandPose = this.bmc$getPose(
                offHandAttributes == null ? null : offHandAttributes.offHandPose()
        );

        // Item channels must remain active even while the mob is moving. Better Combat stores
        // weapon-specific grip rotation/position in rightItem/leftItem, separately from arm poses.
        this.bmc$mainHandItemPose.setPose(mainPose, leftHanded);
        this.bmc$offHandItemPose.setPose(offHandPose, leftHanded);

        // One-handed body poses should not lock the entire mob while walking/crouching. Two-handed
        // poses remain active because their arm placement is necessary for the weapon to look right.
        KeyframeAnimation mainBodyPose = mainPose;
        KeyframeAnimation offHandBodyPose = offHandPose;
        boolean twoHanded = mainAttributes != null && mainAttributes.isTwoHanded();
        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > 0.0009D;
        if (!twoHanded && (moving || this.isShiftKeyDown())) {
            mainBodyPose = null;
            offHandBodyPose = null;
        }

        this.bmc$mainHandBodyPose.setPose(mainBodyPose, leftHanded);
        this.bmc$offHandBodyPose.setPose(offHandBodyPose, leftHanded);
    }

    @Unique
    private void bmc$clearWeaponPoses(boolean leftHanded) {
        this.bmc$mainHandItemPose.setPose(null, leftHanded);
        this.bmc$mainHandBodyPose.setPose(null, leftHanded);
        this.bmc$offHandItemPose.setPose(null, leftHanded);
        this.bmc$offHandBodyPose.setPose(null, leftHanded);
    }

    @Unique
    private KeyframeAnimation bmc$getPose(String animationId) {
        if (animationId == null || animationId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(animationId);
        return id == null ? null : this.bmc$getKeyframeAnimation(id, "idle pose");
    }

    @Unique
    private KeyframeAnimation bmc$getKeyframeAnimation(ResourceLocation id, String purpose) {
        var playable = PlayerAnimationRegistry.getAnimation(id);
        if (playable == null) {
            if (BMC$MISSING_ANIMATIONS.add(id)) {
                BetterMobCombatReimagined.LOGGER.warn(
                        "Player Animator could not find Better Combat mob {} animation '{}'",
                        purpose,
                        id
                );
            }
            return null;
        }

        if (!(playable instanceof KeyframeAnimation animation)) {
            if (BMC$MISSING_ANIMATIONS.add(id)) {
                BetterMobCombatReimagined.LOGGER.warn(
                        "Better Combat mob {} animation '{}' uses unsupported playable type '{}'",
                        purpose,
                        id,
                        playable.getClass().getName()
                );
            }
            return null;
        }
        return animation;
    }

    @Unique
    private Optional<AdjustmentModifier.PartModifier> bmc$attackAdjustment(String partName) {
        float pitch = (float) Math.toRadians(this.getXRot());
        return switch (partName) {
            case "torso" -> Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(-pitch * 0.75F, 0.0F, 0.0F),
                    Vec3f.ZERO
            ));
            case "rightArm", "leftArm" -> Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(pitch * 0.25F, 0.0F, 0.0F),
                    Vec3f.ZERO
            ));
            case "rightLeg", "leftLeg" -> Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(-pitch * 0.75F, 0.0F, 0.0F),
                    Vec3f.ZERO
            ));
            default -> Optional.empty();
        };
    }
}