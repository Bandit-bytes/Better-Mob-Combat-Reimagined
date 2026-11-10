# Better Mob Combat: Reimagined

Gives humanoid mobs real [Better Combat](https://github.com/ZsoltMolnarrr/BetterCombat) attacks. Zombies, skeletons, illagers, drowned, and other humanoid mobs swing, combo, and aim with the same weapon animations players use — plus enhanced unarmed, bow, crossbow, and thrown-weapon animations.

*Minecraft mod — not affiliated with Mojang or Microsoft.*

## Features

- **Better Combat melee for mobs** — mobs use their weapon's Better Combat attacks: combos, swing arcs, reach, and multi-target hits
- **Fallback melee animations** — empty-handed mobs and mobs holding non-Better Combat items still get proper attack animations
- **Ranged animations** — enhanced bow, crossbow, spear, and thrown-weapon animations for humanoid mobs
- **Weapon idle poses** — Better Combat weapon stances, including two-handed bow and crossbow poses
- **Fair hit detection** — line-of-sight checks, no friendly fire by default, and a cap on targets per swing
- **Tunable timing** — windup, cooldown, combo reset, reach, and upswing are all configurable
- **Entity blacklist** — keep vanilla combat for specific mobs, tags, or whole mods
- **Fresh Animations support** — works with Entity Model Features / Fresh Animations models

## This branch

| | |
|---|---|
| Minecraft | **1.21.1** |
| Loader | NeoForge |
| Java | 21 |

**Required:**

- [Better Combat](https://github.com/ZsoltMolnarrr/BetterCombat) 2.3.2 – 2.4.x
- [Player Animator](https://github.com/KosmX/minecraftPlayerAnimator) 2.0.4+
- Cloth Config 15.0.130+

**Optional:** Entity Model Features + Entity Texture Features (for Fresh Animations models)

## Configuration

**Server / world** — `serverconfig/better_mob_combat_reimagined-server.toml` in each world (modpacks: put a copy in `defaultconfigs/`):

| Option | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch for Better Combat attacks on mobs |
| `enableFallbackMeleeAnimations` | `true` | Animate attacks for empty-handed mobs and non-Better Combat items |
| `requireLineOfSight` | `true` | Every target in an attack arc must be visible |
| `allowFriendlyFire` | `false` | Let swings hit the mob's allies |
| `maxTargets` | `8` | Most entities one swing can hit |
| `attackStartDelayTicks` | `10` | Windup before the swing starts |
| `additionalAttackCooldownTicks` | `7` | Extra recovery after each attack |
| `comboResetTicks` | `30` | Idle time before combos restart |
| `additionalAttackRange` | `0.0` | Extra reach in blocks |
| `entityBlacklist` | `[]` | Mobs that keep vanilla combat |

The blacklist accepts entity ids, `#tags`, and whole mods:

```toml
entityBlacklist = ["minecraft:warden", "#minecraft:raiders", "minecolonies:*"]
```

**Client** — `config/better_mob_combat_reimagined-client.toml`: `enableRangedAnimations`, `enableWeaponIdlePoses`, and `clientHitTimingNudgeTicks` (visual-only swing timing tweak).

Every option is documented in the config files themselves.

## Branches

| Branch | Minecraft | Loader |
|---|---|---|
| [`master`](https://github.com/Bandit-bytes/Better-Mob-Combat-Reimagined/tree/master) | 1.21.1 | NeoForge |
| [`forge-1.20.1`](https://github.com/Bandit-bytes/Better-Mob-Combat-Reimagined/tree/forge-1.20.1) | 1.20.1 | Forge |

## Building

Requires JDK 21.

```sh
./gradlew build
```

The built jar ends up in `build/libs`.

## Reporting bugs & suggestions

Please use the [issue tracker](https://github.com/Bandit-bytes/Better-Mob-Combat-Reimagined/issues/new/choose) and pick the bug report or feature request template. For crashes, include a link to your crash report or `latest.log` (via [mclo.gs](https://mclo.gs)).

## Credits

- **[Mob Player Animator](https://github.com/Thelnfamous1/Mob-Player-Animator)** by Thelnfamous1 (1.21.1 port by Tfarcenim) — parts of the humanoid-mob animation bridge are embedded and adapted from it under CC0 1.0. See [`THIRD_PARTY_NOTICES.md`](src/main/resources/META-INF/THIRD_PARTY_NOTICES.md) and [`LICENSES/`](LICENSES).
- **[Better Combat](https://github.com/ZsoltMolnarrr/BetterCombat)** and **[Player Animator](https://github.com/KosmX/minecraftPlayerAnimator)** — required at runtime, not bundled; they keep their own licenses.
- **Entity Model Features**, **Entity Texture Features**, and **Fresh Animations** — optional, not bundled.

## License

Licensed under the [MIT License](LICENSE).
