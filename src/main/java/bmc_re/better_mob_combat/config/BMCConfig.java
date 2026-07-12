package bmc_re.better_mob_combat.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Config for Better Mob Combat: Reimagined.
 *
 * <p>Split into two specs on purpose:</p>
 * <ul>
 *   <li>{@link #SERVER_SPEC} holds everything that decides <em>what actually happens</em> - the master
 *       switch, the entity blacklist and all combat tuning. NeoForge syncs SERVER configs to every
 *       connected client, which is what lets the client-side animation code honour the server's
 *       blacklist. A COMMON config would NOT be synced: each side would silently read its own local
 *       file, so a server-side blacklist could never suppress a client-side pose or bow animation.</li>
 *   <li>{@link #CLIENT_SPEC} holds purely cosmetic, per-player preferences that never affect gameplay.</li>
 * </ul>
 *
 * <p>Note for existing users: SERVER configs live in {@code <world>/serverconfig/}, not {@code config/}.
 * The old {@code better_mob_combat_reimagined-common.toml} is no longer read.</p>
 */
public final class BMCConfig {

    // ------------------------------------------------------------------
    // SERVER spec - authoritative, synced to clients
    // ------------------------------------------------------------------

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = SERVER_BUILDER
            .comment("Master switch for Better Combat attacks on mobs.")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue ENABLE_FALLBACK_MELEE_ANIMATIONS = SERVER_BUILDER
            .comment("Animate vanilla melee attacks for empty-handed mobs and mobs holding items without Better Combat attributes.")
            .define("enableFallbackMeleeAnimations", true);

    public static final ModConfigSpec.BooleanValue REQUIRE_LINE_OF_SIGHT = SERVER_BUILDER
            .comment("Require line of sight for every target caught in an attack arc.")
            .define("requireLineOfSight", true);

    public static final ModConfigSpec.BooleanValue ALLOW_FRIENDLY_FIRE = SERVER_BUILDER
            .comment("Allow a mob's attack arc to hit entities allied to that mob.")
            .define("allowFriendlyFire", false);

    public static final ModConfigSpec.IntValue MAX_TARGETS = SERVER_BUILDER
            .comment("Maximum number of living entities one mob swing may hit.")
            .defineInRange("maxTargets", 8, 1, 64);

    public static final ModConfigSpec.DoubleValue ATTACK_START_RANGE_MULTIPLIER = SERVER_BUILDER
            .comment("How much of the selected Better Combat attack range is used by the AI reach check. "
                    + "The original 1.20.1 mod used 1.0; the separate start delay lets the mob close distance before the visible swing begins.")
            .defineInRange("attackStartRangeMultiplier", 1.0D, 0.25D, 1.0D);

    public static final ModConfigSpec.IntValue ATTACK_START_DELAY = SERVER_BUILDER
            .comment("Ticks between the AI deciding it is in range and the Better Combat upswing beginning. "
                    + "Matches the original 1.20.1 Better Mob Combat default and prevents attacks from visibly starting at maximum reach.")
            .defineInRange("attackStartDelayTicks", 10, 0, 100);

    public static final ModConfigSpec.IntValue ADDITIONAL_ATTACK_COOLDOWN = SERVER_BUILDER
            .comment("Extra recovery ticks after a Better Combat attack interval. The original 1.20.1 default was 7.")
            .defineInRange("additionalAttackCooldownTicks", 7, 0, 100);

    public static final ModConfigSpec.BooleanValue TRACK_TARGET_DURING_WINDUP = SERVER_BUILDER
            .comment("Continuously force a mob to face its intended target during the entire windup. "
                    + "Disabled by default so telegraphed swings can be sidestepped.")
            .define("trackTargetDuringWindup", false);

    public static final ModConfigSpec.IntValue MINIMUM_ATTACK_INTERVAL = SERVER_BUILDER
            .comment("Fastest allowed attack interval, in ticks. Prevents extreme attack-speed values from creating hit spam.")
            .defineInRange("minimumAttackIntervalTicks", 5, 1, 40);

    public static final ModConfigSpec.IntValue COMBO_RESET_TICKS = SERVER_BUILDER
            .comment("Ticks without starting another attack before the mob returns to the first combo animation.")
            .defineInRange("comboResetTicks", 30, 1, 400);

    public static final ModConfigSpec.DoubleValue ADDITIONAL_UPSWING_MULTIPLIER = SERVER_BUILDER
            .comment("Amount added to Better Combat's own upswing multiplier for mob attacks. "
                    + "This matches the original 1.20.1 Better Mob Combat behavior: 0.0 uses Better Combat's normal timing, "
                    + "while positive values make the telegraphed upswing longer.")
            .defineInRange("additionalUpswingMultiplier", 0.15D, 0.0D, 0.8D);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENTITY_BLACKLIST = SERVER_BUILDER
            .comment("Entity types that keep vanilla combat and vanilla animations entirely.",
                    "Accepts entity type ids and entity type tags (tags are prefixed with '#').",
                    "A bare id is assumed to be in the 'minecraft' namespace, so \"warden\" and",
                    "\"minecraft:warden\" both work. Entries are case-sensitive and must be lowercase.",
                    "Examples: [\"minecraft:warden\", \"warden\", \"#minecraft:raiders\"]")
            .defineListAllowEmpty("entityBlacklist", List.of(), BMCConfig::validEntityEntry);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = SERVER_BUILDER
            .comment("Log one-off diagnostics about resolved weapon poses and attack animations. "
                    + "Useful when reporting a bug; noisy otherwise.")
            .define("debugLogging", false);

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    // ------------------------------------------------------------------
    // CLIENT spec - cosmetic only, never affects damage or AI
    // ------------------------------------------------------------------

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_RANGED_ANIMATIONS = CLIENT_BUILDER
            .comment("Add enhanced bow, crossbow, spear, and generic projectile-weapon animations to humanoid mobs.")
            .define("enableRangedAnimations", true);

    public static final ModConfigSpec.BooleanValue ENABLE_WEAPON_IDLE_POSES = CLIENT_BUILDER
            .comment("Apply Better Combat weapon idle poses to humanoid mobs, including two-handed bow and crossbow stances.")
            .define("enableWeaponIdlePoses", true);

    public static final ModConfigSpec.DoubleValue CLIENT_HIT_TIMING_NUDGE_TICKS = CLIENT_BUILDER
            .comment("Fine visual-only adjustment (in ticks, 1 tick = 0.05s) to when the swing animation "
                    + "appears to make contact, relative to the server's actual damage tick. This does NOT "
                    + "change when damage is applied - the server tick rate can't be adjusted more finely "
                    + "than whole ticks - it only re-paces the client animation playback. Positive values "
                    + "make the visible swing reach its hit pose later (sound/damage happens first); "
                    + "negative values make it reach the hit pose earlier. Try small values like 0.1-0.3.")
            .defineInRange("clientHitTimingNudgeTicks", 0.0D, -5.0D, 5.0D);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ------------------------------------------------------------------
    // Blacklist cache
    // ------------------------------------------------------------------

    /**
     * Parsed, normalized view of {@link #ENTITY_BLACKLIST}. Rebuilt from
     * ModConfigEvent.Loading / ModConfigEvent.Reloading (see BetterMobCombatReimagined), which also
     * fires on the client when a server pushes its synced config down on login.
     *
     * <p>This exists for two reasons. Correctness: the raw config strings are compared as-is by
     * String.equals in the old code, so "warden" never matched the canonical "minecraft:warden" that
     * the registry hands back, even though the validator happily accepted it. Performance: the
     * eligibility check runs from Mob#isWithinMeleeAttackRange, i.e. once per tracked mob per tick,
     * and the old path allocated a String and did a linear List scan every single call.</p>
     */
    private static volatile Set<ResourceLocation> blacklistIds = Set.of();
    private static volatile Set<TagKey<EntityType<?>>> blacklistTags = Set.of();

    private BMCConfig() {
    }

    public static void rebuildCaches() {
        Set<ResourceLocation> ids = new HashSet<>();
        Set<TagKey<EntityType<?>>> tags = new HashSet<>();

        for (String raw : ENTITY_BLACKLIST.get()) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            boolean isTag = entry.startsWith("#");
            // tryParse defaults a namespace-less id to "minecraft", which is exactly the
            // normalization we want: "warden" and "minecraft:warden" must behave identically.
            ResourceLocation id = ResourceLocation.tryParse(isTag ? entry.substring(1) : entry);
            if (id == null) {
                continue;
            }
            if (isTag) {
                tags.add(TagKey.create(Registries.ENTITY_TYPE, id));
            } else {
                ids.add(id);
            }
        }

        blacklistIds = Set.copyOf(ids);
        blacklistTags = Set.copyOf(tags);
    }

    /** True if this entity type must keep vanilla combat and vanilla animations. */
    public static boolean isBlacklisted(EntityType<?> type) {
        Set<ResourceLocation> ids = blacklistIds;
        Set<TagKey<EntityType<?>>> tags = blacklistTags;
        if (ids.isEmpty() && tags.isEmpty()) {
            return false;
        }

        if (!ids.isEmpty()) {
            // getKey can return null for a type that was never registered (some mods build ad-hoc
            // EntityTypes). The old code called toString() on it unguarded.
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id != null && ids.contains(id)) {
                return true;
            }
        }

        for (TagKey<EntityType<?>> tag : tags) {
            if (type.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validEntityEntry(Object value) {
        if (!(value instanceof String raw)) {
            return false;
        }
        String entry = raw.trim();
        if (entry.isEmpty()) {
            return false;
        }
        String id = entry.startsWith("#") ? entry.substring(1) : entry;
        return ResourceLocation.tryParse(id) != null;
    }
}