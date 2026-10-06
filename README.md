# VysDragons

A small, standalone Paper plugin for raising dragons, from egg to adult. It's a stripped-down
spin-off of CKSMPPets that keeps only the dragon lifecycle. There are no elements, stats,
breeding, generations, GUIs or cross-server sync, and all data is stored locally in SQLite.

See [SPECS.md](SPECS.md) for the design brief.

## Lifecycle

| Stage | How long | What it does |
| --- | --- | --- |
| **Egg** | 3 MC days of *heated* time (1 real hour by default) | Place the egg item on a block. It only warms up while at least `required-count` heat blocks (fire, soul fire, lava, magma, lit campfires) are within `scan-radius`. Without heat it sits idle and keeps its progress. Hit the egg to pick it back up. Its progress is saved on the item. |
| **Baby** | 30 MC days (10 real hours) | Follows its owner around like a sidekick and plays its `quirk` animation now and then. |
| **Young** | 60 MC days (20 real hours) | Same as an adult except for the second seat: saddle it to ride, fly and breathe fire. |
| **Adult** | permanent | Rides, flies and breathes fire. While the owner is riding, any other player can right-click to take the passenger seat (`seat2`). |

All timers use the real wall clock. They keep running while the owner is offline, the chunk
is unloaded, or the server is down. Egg warmth is credited across unloads too, based on
whether the egg was heated when it unloaded.

A dragon that dies is gone permanently. Players can own any number of dragons.

### Controls (owner)

- **Shift + right-click**: sit / follow toggle.
- **Right-click with a saddle** (young/adult): saddles the dragon and swaps in its `_saddled` model.
- **Shift + right-click with shears** (saddled): removes the saddle and gives it back.
- **Right-click** (saddled, on the ground): mount. From there the MythicMobs flight controller
  handles takeoff, flying, breath and landing.

### Commands (`vysdragons.admin`, op by default)

- `/vysdragons give <player> [amount]`: give egg items.
- `/vysdragons reload`: reload `config.yml`. Model/mob changes apply to dragons the next time they respawn.
- `/vysdragons info`: show stage and time left for the dragon or egg you're looking at.
- `/vysdragons grow`: finish the current stage (dragon) or hatch now (egg) for the thing you're looking at. Meant for testing.

Alias: `/vd`.

## Requirements

- Paper 1.21.11 and Java 21+
- MythicMobs 5.13.x, ModelEngine R4.1.1, Nexo

## What the server has to provide (MythicMobs / ModelEngine / Nexo side)

The plugin only references these by name, and every name can be changed in `config.yml`.

**Nexo**
- `egg.nexo-item` (default `dragon_egg_fire`): the egg item.

**MythicMobs mobs**
- `egg.mob-type` (default `dragon_egg_fire`): the placed egg. It attaches its own model
  (e.g. `model{...}` on `~onSpawn`/`~onLoad`). `Type: WOLF` is recommended, because tamed
  wolves never despawn.
- `dragon.mob-types.baby|young|adult` (defaults `cksmp_dragon_baby` / `cksmp_dragon_adult` /
  `cksmp_dragon_adult`): the dragon body. `Type: WOLF`, AI goals cleared. The plugin attaches
  the ModelEngine model itself, so these mobs should **not** attach one. Young and adult need
  the mount/flight controller and breath skills on `~onInteract`. The existing
  `cksmp_dragon_adult` + `dragon_adult-skills.yml` setup already does this. If young and
  adult share a mob type, growing up swaps the model in place instead of respawning.

Variables the plugin sets on every dragon mob:

| Variable | Value |
| --- | --- |
| `caster.model` | current ModelEngine model id |
| `caster.saddled` | `true` / `false` |
| `caster.element` | `dragon.breath-element` (default `fire`, which picks `dragon-flamethrower`) |
| `caster.state` | `ground` (reset again if a rider disappears mid-flight) |
| `caster.ownername` | owner's name |

The mob's attack damage attribute is set from `dragon.attack-damage.<stage>`. Breath skills read it
through `<caster.damage>`.

**ModelEngine models**
- `robust_dragon_<skin>_<stage>` for baby, young and adult.
- `robust_dragon_<skin>_young_saddled` and `robust_dragon_<skin>_adult_saddled`.
- Young and adult need a `seat` bone, a `firebreath` bone, and the flight controller's
  animations (`idle_fly_raw`, `walk_fly_raw`, `ascend`, `descend`, `land`, `fire_breath`, ...).
  Adult also needs a `seat2` bone. All models need `sit`, and babies need `quirk`.

## Building

MythicMobs, ModelEngine and Nexo are paid or unpublished, so they aren't in this repo. Put
these exact jars in a folder:

- `MythicMobsPremium-5.13.1-SNAPSHOT.jar`
- `ModelEngine-R4.1.1.jar`
- `nexo-1.27.jar`

Then run:

```
./gradlew build -PapiDir=/path/to/that/folder
```

The output is `build/libs/VysDragons-1.0.0.jar`. Kotlin is shaded in. SQLite comes from Paper.

## Data

`plugins/VysDragons/data.db` (SQLite) holds two tables, `dragons` and `eggs`. Dragon and egg
entities are non-persistent: they are never saved into world files. They're respawned from
the database when their chunk loads, so the world can't end up with duplicates.
