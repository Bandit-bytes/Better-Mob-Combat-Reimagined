# Third-Party Notices

## Mob Player Animator — embedded/adapted implementation

Parts of the humanoid-mob animation bridge are embedded and adapted from **Mob Player Animator**:

- Original project: `Thelnfamous1/Mob-Player-Animator`
- NeoForge 1.21.1 branch referenced for this port: `Tfarcenim/Mob-Player-Animator`, branch `1.21.1`
- Original purpose: extend Player Animator support to humanoid mobs
- License on the referenced GitHub branch: **CC0 1.0 Universal**

The adapted portions include the mob animation stack/model handoff, bend-aware humanoid and illager
rendering, model subclass protections, armor propagation, optional Entity Model Features animation
pause/resume behavior, and the default Fresh Animations vindicator model-part corrections.

The full CC0 legal text is included at:

`LICENSES/Mob-Player-Animator-CC0-1.0.txt`

The embedded code has been moved into the `bmc_re.better_mob_combat.internal.mobanim` and
`bmc_re.better_mob_combat.mixin.client.embedded` namespaces and trimmed to the functionality used
by Better Mob Combat: Reimagined. Credit is preserved even though CC0 does not require attribution.

## Player Animator

This mod still depends on Player Animator at runtime. Player Animator is not bundled into this
source tree and retains its own license and copyright.

## Better Combat

This mod still depends on Better Combat at runtime. Better Combat is not bundled into this source
tree and retains its own license and copyright.

## Entity Model Features / Fresh Animations

Entity Model Features and Fresh Animations are not bundled and are not required dependencies. The
optional compatibility bridge calls EMF's public animation API reflectively when EMF is installed.
