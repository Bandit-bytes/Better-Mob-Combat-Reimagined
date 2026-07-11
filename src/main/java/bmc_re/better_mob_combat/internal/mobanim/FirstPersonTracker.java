package bmc_re.better_mob_combat.internal.mobanim;

/**
 * Embedded Mob Player Animator state used by Player Animator's first-person model hook.
 * Mobs normally render in third person, but keeping the same contract prevents stale pose state.
 */
public interface FirstPersonTracker {
    boolean bmc$isFirstPersonNext();

    void bmc$setFirstPersonNext(boolean firstPersonNext);
}
