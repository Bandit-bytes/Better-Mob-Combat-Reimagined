package bmc_re.better_mob_combat.api;

import dev.kosmx.playerAnim.api.layered.AnimationStack;

/**
 * Better Mob Combat-specific state attached to client mobs. The mob animation stack and humanoid/armor bridge are embedded directly in this mod,
 * using Player Animator's own model and bend APIs.
 */
public interface MobAnimationAccess {
    AnimationStack bmc$getAnimationStack();

    float bmc$getRenderPartialTick();

    void bmc$setRenderPartialTick(float partialTick);

    boolean bmc$isAttackAnimationActive();

    /**
     * True while Better Combat owns either arm through an idle weapon body pose or attack layer.
     * Mob model subclasses use this to avoid replacing authored two-handed grips after the shared
     * humanoid animation pass has already applied them.
     */
    boolean bmc$isArmAnimationActive();

    /** True when the active Better Combat idle pose or attack explicitly requires both arms. */
    boolean bmc$isTwoHandedArmAnimationActive();

    /** True while conditional illager held-item layers must follow Better Combat arm ownership. */
    boolean bmc$shouldForceAttackItemVisible();

    void bmc$playAttackAnimation(
            String animationId,
            boolean offHand,
            boolean twoHanded,
            float length,
            float animationUpswing,
            float damageUpswing
    );
}
