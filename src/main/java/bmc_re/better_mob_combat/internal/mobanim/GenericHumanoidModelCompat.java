package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobAnimationAccess;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Player Animator bridge for modded humanoids whose renderer uses a custom {@link EntityModel}
 * instead of vanilla's {@link HumanoidModel} or {@link IllagerModel}.
 *
 * <p>MCreator/Blockbench mobs commonly still expose familiar head/body/arm/leg ModelParts, but the
 * normal HumanoidModel mixin never sees them. This adapter resolves those parts once per live model
 * instance and applies only the channels currently owned by Better Mob Combat immediately before
 * the model is drawn. It therefore leaves the custom model's idle, walk, cloth and decorative bones
 * alone while allowing its weapon arm(s) to follow the same resource-pack swing as vanilla mobs.</p>
 */
public final class GenericHumanoidModelCompat {
    private static final Map<EntityModel<?>, PartBinding> BINDINGS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

    private static final Map<EmbeddedPlayerAnimator.AnimatedPart, String[]> ALIASES = aliases();

    private GenericHumanoidModelCompat() {
    }

    /**
     * Returns whether a renderer model has a complete player-style humanoid skeleton.
     *
     * <p>This deliberately uses a strict check for generic models. Several vanilla non-humanoids
     * expose parts named {@code head} or {@code body}; treating a single matching name as proof of
     * humanoid compatibility lets a punch animation move only that part. Spiders are the clearest
     * example: their head becomes detached while the rest of the model keeps its normal pose.</p>
     */
    public static boolean supportsModel(EntityModel<?> model) {
        if (model instanceof HumanoidModel<?> || model instanceof IllagerModel<?>) {
            return true;
        }

        // Vanilla has several creature-specific models with humanoid-looking field names (most
        // notably IronGolemModel). Their pivots and authored attack poses are not player-model
        // compatible, so the reflective adapter is reserved for third-party custom models.
        Package modelPackage = model.getClass().getPackage();
        if (modelPackage != null && modelPackage.getName().startsWith("net.minecraft.client.model")) {
            return false;
        }

        return BINDINGS.computeIfAbsent(model, GenericHumanoidModelCompat::resolve)
                .hasCompleteHumanoidSkeleton();
    }

    public static void apply(LivingEntity entity, EntityModel<?> model, float partialTick) {
        if (!(entity instanceof MobAnimationAccess access)
                || model instanceof HumanoidModel<?>
                || model instanceof IllagerModel<?>
                || OptionalEmfCompat.isEmfModel(model)
                || !supportsModel(model)) {
            return;
        }

        AnimationApplier animation = EmbeddedPlayerAnimator.getAnimation(entity);
        if (animation == null || !animation.isActive()) {
            return;
        }

        EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
        ensureWeaponArms(entity, access, animated);
        if (animated.isEmpty()) {
            return;
        }

        PartBinding binding = BINDINGS.computeIfAbsent(model, GenericHumanoidModelCompat::resolve);
        if (!binding.hasCompleteHumanoidSkeleton() || !binding.hasAny(animated)) {
            logMissingOnce(entity, model, animated, binding);
            return;
        }

        animation.setTickDelta(partialTick);
        if (!animation.isActive()) {
            return;
        }

        apply(animation, "head", binding.get(EmbeddedPlayerAnimator.AnimatedPart.HEAD),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD));
        apply(animation, "torso", binding.get(EmbeddedPlayerAnimator.AnimatedPart.TORSO),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO));
        apply(animation, "leftArm", binding.get(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM));
        apply(animation, "rightArm", binding.get(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM));
        apply(animation, "leftLeg", binding.get(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG));
        apply(animation, "rightLeg", binding.get(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG),
                animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG));

        String key = "resolved|" + entity.getType() + "|" + model.getClass().getName();
        if (LOGGED.add(key)) {
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC custom-model] mob={} model={} resolvedParts={} activeChannels={}",
                    entity.getType(), model.getClass().getName(), binding.names(), animated
            );
        }
    }

    private static void apply(AnimationApplier animation, String channel, ModelPart part, boolean active) {
        if (active && part != null) {
            animation.updatePart(channel, part);
        }
    }

    private static void ensureWeaponArms(
            LivingEntity entity,
            MobAnimationAccess access,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated
    ) {
        if (access.bmc$isTwoHandedArmAnimationActive()) {
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        } else if (access.bmc$isAttackAnimationActive()) {
            if (entity instanceof Mob mob && mob.isLeftHanded()) {
                animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            } else {
                animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            }
        }
    }

    private static PartBinding resolve(EntityModel<?> model) {
        EnumMap<EmbeddedPlayerAnimator.AnimatedPart, ModelPart> parts =
                new EnumMap<>(EmbeddedPlayerAnimator.AnimatedPart.class);
        EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String> names =
                new EnumMap<>(EmbeddedPlayerAnimator.AnimatedPart.class);

        // Generated models generally retain useful field names even when their root hierarchy is
        // nested under a generic "bone" node, so inspect fields first.
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                EmbeddedPlayerAnimator.AnimatedPart channel = match(field.getName());
                if (channel == null || parts.containsKey(channel)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(model);
                    if (value instanceof ModelPart part) {
                        parts.put(channel, part);
                        names.put(channel, "field:" + field.getName());
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fall through to root-tree lookup below.
                }
            }
        }

        if (model instanceof HierarchicalModel<?> hierarchical) {
            ModelPart root = hierarchical.root();
            if (root != null) {
                resolveFromTree(root, parts, names);
            }
        }

        return new PartBinding(parts, names);
    }

    private static void resolveFromTree(
            ModelPart root,
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, ModelPart> parts,
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String> names
    ) {
        // Prefer HierarchicalModel's public descendant lookup for standard aliases.
        for (Map.Entry<EmbeddedPlayerAnimator.AnimatedPart, String[]> entry : ALIASES.entrySet()) {
            if (parts.containsKey(entry.getKey())) {
                continue;
            }
            for (String alias : entry.getValue()) {
                ModelPart found = findExactDescendant(root, alias);
                if (found != null) {
                    parts.put(entry.getKey(), found);
                    names.put(entry.getKey(), "part:" + alias);
                    break;
                }
            }
        }

        // Blockbench names can differ only by case, spaces or underscores. Walk the actual children
        // and compare normalized names to cover RightArm/right_arm/right arm without hard-depending
        // on another mod's generated model class.
        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<NamedPart> queue = new ArrayDeque<>();
            queue.add(new NamedPart("root", root));
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                NamedPart current = queue.removeFirst();
                if (!visited.add(current.part())) {
                    continue;
                }
                EmbeddedPlayerAnimator.AnimatedPart channel = match(current.name());
                if (channel != null && !parts.containsKey(channel)) {
                    parts.put(channel, current.part());
                    names.put(channel, "part:" + current.name());
                }
                Object raw = childrenField.get(current.part());
                if (raw instanceof Map<?, ?> children) {
                    for (Map.Entry<?, ?> child : children.entrySet()) {
                        if (child.getKey() instanceof String name && child.getValue() instanceof ModelPart part) {
                            queue.addLast(new NamedPart(name, part));
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Exact lookup and reflected model fields are still sufficient for most generated mobs.
        }
    }

    private static ModelPart findExactDescendant(ModelPart root, String name) {
        if (root.hasChild(name)) {
            return root.getChild(name);
        }
        // getAnyDescendantWithName is available on HierarchicalModel, not ModelPart; recursively
        // inspect direct children using the same reflected map used by the fallback resolver.
        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<ModelPart> queue = new ArrayDeque<>();
            queue.add(root);
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                ModelPart current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                Object raw = childrenField.get(current);
                if (!(raw instanceof Map<?, ?> children)) {
                    continue;
                }
                Object direct = children.get(name);
                if (direct instanceof ModelPart part) {
                    return part;
                }
                for (Object child : children.values()) {
                    if (child instanceof ModelPart part) {
                        queue.addLast(part);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static EmbeddedPlayerAnimator.AnimatedPart match(String name) {
        String normalized = normalize(name);
        for (Map.Entry<EmbeddedPlayerAnimator.AnimatedPart, String[]> entry : ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalized.equals(normalize(alias))) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static Map<EmbeddedPlayerAnimator.AnimatedPart, String[]> aliases() {
        EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String[]> map =
                new EnumMap<>(EmbeddedPlayerAnimator.AnimatedPart.class);
        map.put(EmbeddedPlayerAnimator.AnimatedPart.HEAD,
                new String[]{"head", "head_root", "headroot", "skull"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.TORSO,
                new String[]{"body", "torso", "chest", "upper_body", "upperbody"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM,
                new String[]{"left_arm", "leftarm", "arm_left", "armleft", "left_upper_arm", "leftupperarm", "larm"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM,
                new String[]{"right_arm", "rightarm", "arm_right", "armright", "right_upper_arm", "rightupperarm", "rarm"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG,
                new String[]{"left_leg", "leftleg", "leg_left", "legleft", "left_upper_leg", "leftupperleg", "lleg"});
        map.put(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG,
                new String[]{"right_leg", "rightleg", "leg_right", "legright", "right_upper_leg", "rightupperleg", "rleg"});
        return Collections.unmodifiableMap(map);
    }

    private static void logMissingOnce(
            LivingEntity entity,
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            PartBinding binding
    ) {
        String key = "missing|" + entity.getType() + "|" + model.getClass().getName();
        if (LOGGED.add(key)) {
            BetterMobCombatReimagined.LOGGER.warn(
                    "[BMC custom-model] Could not resolve the requested animation parts for mob={} model={} "
                            + "activeChannels={} resolvedParts={}",
                    entity.getType(), model.getClass().getName(), animated, binding.names()
            );
        }
    }

    private record NamedPart(String name, ModelPart part) {
    }

    private record PartBinding(
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, ModelPart> parts,
            EnumMap<EmbeddedPlayerAnimator.AnimatedPart, String> names
    ) {
        ModelPart get(EmbeddedPlayerAnimator.AnimatedPart part) {
            return parts.get(part);
        }

        boolean hasAny(EnumSet<EmbeddedPlayerAnimator.AnimatedPart> requested) {
            for (EmbeddedPlayerAnimator.AnimatedPart part : requested) {
                if (parts.containsKey(part)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasCompleteHumanoidSkeleton() {
            return parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.HEAD)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.TORSO)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)
                    && parts.containsKey(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG);
        }
    }
}
