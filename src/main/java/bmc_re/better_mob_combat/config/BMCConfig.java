package bmc_re.better_mob_combat.config;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class BMCConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for Better Combat attacks on mobs.")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue ENABLE_RANGED_ANIMATIONS = BUILDER
            .comment("Add enhanced bow, crossbow, spear, and generic projectile-weapon animations to humanoid mobs.")
            .define("enableRangedAnimations", true);

    public static final ModConfigSpec.BooleanValue ENABLE_WEAPON_IDLE_POSES = BUILDER
            .comment("Apply Better Combat weapon idle poses to humanoid mobs, including two-handed bow and crossbow stances.")
            .define("enableWeaponIdlePoses", true);

    public static final ModConfigSpec.BooleanValue ENABLE_FALLBACK_MELEE_ANIMATIONS = BUILDER
            .comment("Animate vanilla melee attacks for empty-handed mobs and mobs holding items without Better Combat attributes.")
            .define("enableFallbackMeleeAnimations", true);

    public static final ModConfigSpec.BooleanValue REQUIRE_LINE_OF_SIGHT = BUILDER
            .comment("Require line of sight for every target caught in an attack arc.")
            .define("requireLineOfSight", true);

    public static final ModConfigSpec.BooleanValue ALLOW_FRIENDLY_FIRE = BUILDER
            .comment("Allow a mob's attack arc to hit entities allied to that mob.")
            .define("allowFriendlyFire", false);

    public static final ModConfigSpec.IntValue MAX_TARGETS = BUILDER
            .comment("Maximum number of living entities one mob swing may hit.")
            .defineInRange("maxTargets", 8, 1, 64);

    public static final ModConfigSpec.DoubleValue FALLBACK_BASE_RANGE = BUILDER
            .comment("Base melee range used when a mob has no ENTITY_INTERACTION_RANGE attribute. Better Combat range_bonus is added to this.")
            .defineInRange("fallbackBaseRange", 3.0D, 0.5D, 16.0D);

    public static final ModConfigSpec.DoubleValue ATTACK_START_RANGE_MULTIPLIER = BUILDER
            .comment("How much of the selected Better Combat attack range is used by the AI reach check. "
                    + "The original 1.20.1 mod used 1.0; the separate start delay lets the mob close distance before the visible swing begins.")
            .defineInRange("attackStartRangeMultiplier", 1.0D, 0.25D, 1.0D);

    public static final ModConfigSpec.IntValue ATTACK_START_DELAY = BUILDER
            .comment("Ticks between the AI deciding it is in range and the Better Combat upswing beginning. "
                    + "Matches the original 1.20.1 Better Mob Combat default and prevents attacks from visibly starting at maximum reach.")
            .defineInRange("attackStartDelayTicks", 10, 0, 100);

    public static final ModConfigSpec.IntValue ADDITIONAL_ATTACK_COOLDOWN = BUILDER
            .comment("Extra recovery ticks after a Better Combat attack interval. The original 1.20.1 default was 7.")
            .defineInRange("additionalAttackCooldownTicks", 7, 0, 100);

    public static final ModConfigSpec.BooleanValue TRACK_TARGET_DURING_WINDUP = BUILDER
            .comment("Continuously force a mob to face its intended target during the entire windup. "
                    + "Disabled by default so telegraphed swings can be sidestepped.")
            .define("trackTargetDuringWindup", false);

    public static final ModConfigSpec.IntValue MINIMUM_ATTACK_INTERVAL = BUILDER
            .comment("Fastest allowed attack interval, in ticks. Prevents extreme attack-speed values from creating hit spam.")
            .defineInRange("minimumAttackIntervalTicks", 5, 1, 40);

    public static final ModConfigSpec.IntValue COMBO_RESET_TICKS = BUILDER
            .comment("Ticks without starting another attack before the mob returns to the first combo animation.")
            .defineInRange("comboResetTicks", 30, 1, 400);

    public static final ModConfigSpec.DoubleValue UPSWING_MULTIPLIER = BUILDER
            .comment("Additional multiplier applied after Better Combat computes AttackHand.upswingRate(), including Better Combat's own upswing setting.")
            .defineInRange("upswingMultiplier", 1.0D, 0.05D, 3.0D);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENTITY_BLACKLIST = BUILDER
            .comment("Entity type ids that should keep vanilla melee combat. Example: minecraft:warden")
            .defineListAllowEmpty("entityBlacklist", List.of(), BMCConfig::validResourceLocation);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private BMCConfig() {
    }

    private static boolean validResourceLocation(Object value) {
        return value instanceof String id && ResourceLocation.tryParse(id) != null;
    }
}
