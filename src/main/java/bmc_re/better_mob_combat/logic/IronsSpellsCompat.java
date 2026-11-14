package bmc_re.better_mob_combat.logic;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detection helpers for Iron's Spells 'n Spellbooks casting mobs (and addons built on them, such
 * as Dark Doppelganger), resolved by class name so Iron's Spells stays an optional dependency.
 *
 * <p>These mobs render through GeckoLib instead of a vanilla {@code HumanoidModel}, and run their
 * own GeckoLib melee and spellcasting animations.</p>
 */
public final class IronsSpellsCompat {
    private static final String SPELL_CASTING_MOB =
            "io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMob";

    private static final Map<Class<?>, Boolean> CASTING_MOB_CLASSES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> IS_ANIMATING_METHODS = new ConcurrentHashMap<>();

    private IronsSpellsCompat() {
    }

    public static boolean isSpellCastingMob(LivingEntity entity) {
        return entity instanceof Mob && CASTING_MOB_CLASSES.computeIfAbsent(entity.getClass(), type -> {
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                if (SPELL_CASTING_MOB.equals(cursor.getName())) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Whether the mob is currently playing one of its own GeckoLib animations (a spell cast, an
     * Iron's melee move, a spawn animation...). Better Combat idle poses must not override those.
     */
    public static boolean isPlayingNativeAnimation(LivingEntity entity) {
        Optional<Method> method = IS_ANIMATING_METHODS.computeIfAbsent(entity.getClass(), type -> {
            try {
                return Optional.of(type.getMethod("isAnimating"));
            } catch (NoSuchMethodException | RuntimeException ignored) {
                return Optional.empty();
            }
        });
        if (method.isEmpty()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.get().invoke(entity));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }
}
