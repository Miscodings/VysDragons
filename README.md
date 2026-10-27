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
| **Young** | 60 MC days (20 real hours) | Saddle it to ride, fly and breathe fire. While the owner is riding, any other player can right-click to take the passenger seat (`seat2`, one passenger at a time). |
| **Adult** | permanent | Same as young, stronger. |

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

### Summon / put away (`/dragons`, everyone)

`/dragons` opens a menu listing every dragon you own. Click a stored dragon to summon it
next to you, or click one that's out to put it away. Only one dragon can be out at a time:
summoning one puts away whichever is already out, and so does hatching a new egg. Stored
dragons keep growing. You can't summon or store while someone is riding.

### Admin commands (`vysdragons.admin`, op by default)

- `/vysdragons give <player> [type] [amount]`: give egg items. `type` is a skin name (tab-completes) or `random`; leave it out for a random egg each. `/vd give <player> 3` still works.
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
- `egg.nexo-item-pattern` (default `dragon_egg_%skin%`): one egg item per entry in `dragon.skins`. `egg.nexo-item` (`dragon_egg_fire`) is the old untyped egg; placing one rolls a random skin.

**MythicMobs mobs**
- `egg.mob-type-pattern` (default `dragon_egg_%skin%`): the placed egg, one per skin; `egg.mob-type` (`dragon_egg_fire`) is the old untyped one. Each attaches its own model
  (e.g. `model{...}` on `~onSpawn`/`~onLoad`). `Type: WOLF` is recommended, because tamed
  wolves never despawn.
- `dragon.mob-types.baby|young|adult` (default `cksmp_dragon_adult` for baby and adult, `cksmp_dragon_young` for young): the dragon
  body. `Type: WOLF`, AI goals cleared. The plugin attaches the ModelEngine model itself, so
  the mob should **not** attach one. It needs the mount/flight controller and breath skills
  on `~onInteract`. Babies share it safely, since they can never be saddled. With one shared
  mob type, growing up swaps the model in place instead of respawning.

The server layout used on the Vys server:

```
plugins/MythicMobs/Mobs/VysDragons/eggs.yml        dragon_egg_fire
plugins/MythicMobs/Mobs/VysDragons/dragons.yml     cksmp_dragon_adult
plugins/MythicMobs/Skills/VysDragons/flight.yml    mount + flight controller
plugins/MythicMobs/Skills/VysDragons/breath.yml    fire breath
plugins/ModelEngine/blueprints/vysdragons/{egg,baby,young,young_saddled,adult,adult_saddled}/
```

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
  Young (saddled) and adult also need a `seat2` bone. All models need `sit`, and babies need `quirk`.
- Nothing else is used. Animations the plugin and skills never play only bloat the
  resource pack: baby needs `idle walk sit quirk`; young/adult need `idle walk sit
  idle_fly_raw walk_fly_raw ascend descend drift_left_raw drift_right_raw land fire_breath`.

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

The output is `build/libs/VysDragons-1.3.0.jar`. Kotlin is shaded in. SQLite comes from Paper.

## Config files

- `plugins/VysDragons/config.yml`: gameplay (timers, heat rules, mob types, models,
  health/damage, follow distances, animations).
- `plugins/VysDragons/lang.yml`: every chat message, the `/dragons` menu (title, items,
  names, lore), the egg hologram text, the dragon nameplate and stage names. MiniMessage
  formatting. Keys missing from your copy fall back to the defaults.

Apply edits to either file with `/vysdragons reload`.

## Data

`plugins/VysDragons/data.db` (SQLite) holds two tables, `dragons` and `eggs`. Dragon and egg
entities are non-persistent: they are never saved into world files. They're respawned from
the database when their chunk loads, so the world can't end up with duplicates.
