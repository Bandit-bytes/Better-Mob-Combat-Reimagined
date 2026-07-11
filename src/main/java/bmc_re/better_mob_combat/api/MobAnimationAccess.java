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

    void bmc$playAttackAnimation(
            String animationId,
            boolean offHand,
            boolean twoHanded,
            float length,
            float animationUpswing,
            float damageUpswing
    );
}
