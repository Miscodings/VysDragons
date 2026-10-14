# VysDragons handover

Where the project stands, how it's deployed, and what's left. See [README.md](README.md)
for the feature list, commands and build steps, and [SPECS.md](SPECS.md) for the original
brief.

## Status

Live on the Vys server (Paper 1.21.11) and verified in game:

- Eggs: give, place, heat incubation, hit to pick up (progress kept), `/vd grow` to hatch.
- Growth baby → young → adult with no leftover models; saddling with no leftover models.
- Riding young/adult, flying, fire breath, and the adult passenger seat (`seat2`).
- `/dragons` summon / put away, one dragon out at a time.

**Remaining:** young dragon models. Young dragons work, but their models are unfinished:

- No `robust_dragon_<skin>_young_saddled` models yet. Until they exist, saddling a young
  dragon falls back to the unsaddled model (logged as a warning, see
  `Dragon.resolveModelId`).
- The young models only have `idle`, `walk` and `sit`. They need the flight set
  (`idle_fly_raw`, `walk_fly_raw`, `ascend`, `descend`, `drift_left_raw`,
  `drift_right_raw`, `land`) and `fire_breath`, plus `seat` and `firebreath` bones.

Nothing in the plugin needs to change for those. Drop the models into
`plugins/ModelEngine/blueprints/vysdragons/young/` (and a new `young_saddled/`), then run
`meg reload` and `nexo reload pack`.

Optional:

- Baby and young models have no `hitbox` bone, so they use the mob's default size.
- `land` is about half of each adult model (baked to ~6,400 keyframes). Re-exporting it
  with fewer keyframes would shrink the resource pack a lot.

## Egg types (1.3.0)

Every skin has its own egg: Nexo item `dragon_egg_<skin>`, MythicMobs mob `dragon_egg_<skin>`
(`Mobs/VysDragons/eggs_skins.yml`), ModelEngine model `dragon_egg_<skin>` in
`blueprints/vysdragons/egg/`, and an item model/texture in the Nexo pack. The egg hatches
into that skin. `/vd give <player> [type] [amount]`, no type = random. `black_and_red` and
`red_and_black` share the red egg texture. The old `dragon_egg_fire` item/mob stay for
eggs from before; they roll a random skin when placed (DB column `eggs.skin`).
Egg models only keep two animations: `warm` (loops while heated) and `hatch` (plays on
hatching; the baby spawns at 60 ticks, the shell goes at 100, see `egg.animations`).

## What's in this repo, and what isn't

Only the plugin's own source and default `config.yml` / `lang.yml`. **Nothing from
MythicMobs, ModelEngine or Nexo is committed**: no jars, configs, mob/skill files, models
or blueprints. `.gitignore` blocks `configs/`, `*.bbmodel` and those plugin folders. Keep
it that way: the repo is public and those are paid or server-owned.

The server-side files the plugin expects are described (by name, not content) in the
README under "What the server has to provide".

## How it works (short)

- `VysDragons.kt`: boot. Loads `config.yml` + `lang.yml` into `Settings`, opens SQLite,
  starts the managers.
- `Settings.kt`: every config/lang value. `config.yml` is gameplay; `lang.yml` is all
  text and menu looks, with the jar's copy as fallback for missing keys.
- `data/Database.kt`: SQLite at `plugins/VysDragons/data.db` (`dragons`, `eggs` tables).
  Writes go through a single background thread. The `stored` column is migrated in on
  startup for 1.0.0 databases.
- `dragon/Dragon.kt`: one dragon. Bodies are non-persistent MythicMobs mobs that only
  exist while their chunk is loaded (and the dragon isn't stored); they're respawned from
  the database, so worlds never collect duplicates. The plugin attaches the ModelEngine
  model itself and sets `caster.model/saddled/element/state` for the MythicMobs flight
  skills. Growth runs on the real wall clock (`stageStartedAt`).
- `dragon/DragonManager.kt`: registry, 1-second tick (growth, follow, quirk animation),
  chunk load/unload, summon/store.
- `dragon/Mounts.java`: ModelEngine mount calls that Kotlin can't type (passenger seat).
- `egg/`: placed eggs. Progress only counts while enough heat blocks are nearby; an
  unloaded egg keeps the heat state it had when it unloaded.
- `listener/DragonListener.kt`: placing/hitting eggs (including ModelEngine hitbox hits
  via `BaseEntityInteractEvent`), owner interactions (sit, saddle, unsaddle), passenger
  seat, deaths, chunk events.
- `menu/DragonMenu.kt`: the `/dragons` GUI.

### Things that bit us (don't undo these)

- **Leftover models:** `ModeledEntity.removeModel()` only detaches a model. Each removed
  `ActiveModel` must be `destroy()`ed, and bodies are removed 2 ticks later so ModelEngine
  can tell players first. Shutdown uses the immediate path because scheduled tasks don't
  run after disable.
- **One mob type for every stage** (`cksmp_dragon_adult`), so growing up swaps the model on
  the same mob instead of respawning it. Babies share it safely: they can't be saddled, and
  mounting requires `caster.saddled=true`.
- **Egg clicks** land on the egg model's ModelEngine hitbox, not on the hidden wolf, so
  pickup listens to `BaseEntityInteractEvent`. `/vd grow` and `/vd info` fall back to the
  nearest egg/dragon within 8 blocks for the same reason.
- **Egg model centering:** the original `fire_dragon_egg.bbmodel` was a block-format model
  (0–16 px, centered on 8,8), so it drew half a block off its hitbox. The server copy is
  shifted −8 px on X/Z to be centered on 0,0.
- **`ob_` bones on adults are ModelEngine sub-hitboxes** (body, neck, tail, wings). They're
  hidden in Blockbench but required; don't strip them.

## Server setup

Paper 1.21.11 on Java 21, converted from Fabric 1.21.1. A full backup of the Fabric server
("before-paper-switch") is in the panel's backups, and the Fabric mods and incompatible
plugins (WorldGuard, voxy, seeu, Geyser-Velocity) are parked in `_fabric_disabled/`.

Plugins: MythicMobs Premium 5.13.1, ModelEngine R4.1.1, Nexo 1.27, VysDragons, Simple
Voice Chat.

Layout:

```
plugins/VysDragons/config.yml, lang.yml, data.db
plugins/MythicMobs/Mobs/VysDragons/eggs.yml, dragons.yml
plugins/MythicMobs/Skills/VysDragons/flight.yml, breath.yml
plugins/ModelEngine/blueprints/vysdragons/{egg,baby,young,adult,adult_saddled}/
plugins/Nexo/items/vysdragons.yml                         (egg item dragon_egg_fire)
plugins/Nexo/pack/assets/minecraft/models/vysdragons/     (egg item model)
plugins/Nexo/pack/assets/minecraft/textures/block/        (egg item texture)
```

The resource pack is generated by Nexo, which imports the ModelEngine pack and hosts it on
Nexo's own service, so players get it on join.

### Deploying

- **Plugin update:** build (see README), upload the jar to `plugins/` through the panel,
  delete the old jar, restart. If `config.yml` is still untouched defaults it can be
  regenerated by renaming it away; otherwise merge changes by hand.
- **Text/menu changes:** edit `plugins/VysDragons/lang.yml`, run `/vd reload`.
- **Models:** upload `.bbmodel` files into the right `vysdragons/<stage>/` folder, run
  `meg reload`, then `nexo reload pack`. Players rejoin (or accept the pack prompt).
- The panel's file upload takes about 10 MB at a time; zip models and use the panel's
  unarchive for bulk uploads.

### Trimming models before upload

The server copies are slimmed versions of the originals: only the animations below are
kept, animation tracks with no keyframes are dropped, and Blockbench's editor-only
keyframe fields (`uuid`, `color`) are removed. That took the dragon models from 386 MB to
79 MB. Keep the originals; trim copies.

| Stage | Animations kept |
| --- | --- |
| baby | idle, walk, sit, quirk |
| young, adult, adult saddled | idle, walk, sit, idle_fly_raw, walk_fly_raw, ascend, descend, drift_left_raw, drift_right_raw, land, fire_breath |

Do the same to the new young/young_saddled models once they exist.
