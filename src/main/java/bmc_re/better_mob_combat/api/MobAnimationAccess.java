package bmc_re.better_mob_combat.api;

import dev.kosmx.playerAnim.api.layered.AnimationStack;


public interface MobAnimationAccess {
    AnimationStack bmc$getAnimationStack();

    float bmc$getRenderPartialTick();

    void bmc$setRenderPartialTick(float partialTick);

    boolean bmc$isAttackAnimationActive();

    boolean bmc$isArmAnimationActive();

    boolean bmc$isTwoHandedArmAnimationActive();

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
