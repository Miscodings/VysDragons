package com.vys.vysdragons.egg

import com.vys.vysdragons.Settings
import com.vys.vysdragons.VysDragons
import com.vys.vysdragons.data.EggRow
import com.vys.vysdragons.util.SpawnBypass
import com.vys.vysdragons.util.formatDuration
import com.ticxo.modelengine.api.ModelEngineAPI
import com.ticxo.modelengine.api.animation.BlueprintAnimation
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.bukkit.MythicBukkit
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.data.Lightable
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Tameable
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * A placed egg. Hatch progress only grows while [heated]; the heat check runs while
 * the chunk is loaded, and an unloaded egg keeps whatever heat state it last had
 * (its surroundings can't change while unloaded), so wall-clock time is credited
 * across unloads and restarts too.
 */
class DragonEgg(
    val uuid: UUID,
    val owner: UUID,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val skin: String,
    progressMillis: Long,
    heated: Boolean,
    checkedAt: Long,
) {
    var progressMillis = progressMillis
        private set
    var heated = heated
        private set
    private var checkedAt = checkedAt

    /** True while the hatch animation plays; the egg is already spent and ignores everything. */
    var hatching = false
        private set

    var entity: Entity? = null
        private set
    private var display: TextDisplay? = null

    private val plugin get() = VysDragons.instance
    private val settings get() = plugin.settings

    val isActive: Boolean get() = entity?.isValid == true

    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z) }

    fun activate(): Boolean {
        if (isActive) return true
        val loc = location() ?: return false
        if (!loc.world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return false

        val mythicMob = MythicBukkit.inst().mobManager.getMythicMob(settings.eggMobType(skin)).orElse(null)
        if (mythicMob == null) {
            plugin.logger.warning("MythicMobs mob '${settings.eggMobType(skin)}' not found - egg $uuid can't spawn")
            return false
        }
        val activeMob = SpawnBypass.run { mythicMob.spawn(BukkitAdapter.adapt(loc), 1.0) }
        val body = activeMob?.entity?.bukkitEntity
        if (body == null) {
            plugin.logger.warning("Failed to spawn egg $uuid at $loc")
            return false
        }
        body.isPersistent = false
        (body as? org.bukkit.entity.LivingEntity)?.removeWhenFarAway = false
        // Tamed wolves never despawn; the egg mobs are WOLF-based for exactly this.
        activeMob.setOwnerUUID(owner)
        (body as? Tameable)?.let {
            it.owner = Bukkit.getOfflinePlayer(owner)
            it.isTamed = true
        }
        body.persistentDataContainer.set(VysDragons.EGG_KEY, PersistentDataType.STRING, uuid.toString())
        entity = body
        plugin.eggs.indexEntity(body.uniqueId, this)
        // ModelEngine finishes attaching on its next tick; animations sent before that are lost.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { syncWarmAnimation() }, 10L)

        if (settings.eggDisplayEnabled) {
            display = loc.world.spawn(loc.clone().add(0.0, settings.eggDisplayHeight, 0.0), TextDisplay::class.java) {
                it.isPersistent = false
                it.billboard = Display.Billboard.CENTER
                it.persistentDataContainer.set(VysDragons.EGG_KEY, PersistentDataType.STRING, uuid.toString())
            }
        }
        return true
    }

    /** [immediate] skips the two-tick removal grace; only for shutdown. */
    fun deactivate(immediate: Boolean = false) {
        val body = entity
        entity = null
        if (body != null) {
            plugin.eggs.unindexEntity(body.uniqueId)
            runCatching {
                ModelEngineAPI.getModeledEntity(body.uniqueId)?.let { modeled ->
                    modeled.models.keys.toList().forEach { id -> modeled.removeModel(id).ifPresent { it.destroy() } }
                    modeled.destroy()
                }
                ModelEngineAPI.removeModeledEntity(body.uniqueId)
            }
            // Same two-tick grace as dragons, so the model's removal reaches players.
            val remove = Runnable {
                runCatching { MythicBukkit.inst().mobManager.getActiveMob(body.uniqueId).orElse(null)?.remove() }
                if (body.isValid) body.remove()
            }
            if (immediate) remove.run() else Bukkit.getScheduler().runTaskLater(plugin, remove, 2L)
        }
        display?.remove()
        display = null
    }

    /** Credits time since the last check, then re-checks heat. True once ready to hatch. */
    fun tick(now: Long): Boolean {
        val wasHeated = heated
        if (heated) progressMillis += (now - checkedAt).coerceAtLeast(0L)
        checkedAt = now
        location()?.let { heated = countHeat(it) >= settings.heatRequiredCount }
        if (heated != wasHeated) syncWarmAnimation()
        refreshDisplay()
        return progressMillis >= settings.eggHatchMillis
    }

    private fun activeModel() =
        entity?.let { ModelEngineAPI.getModeledEntity(it.uniqueId)?.models?.values?.firstOrNull() }

    /** The model's `warm` loop plays while the egg is heated. */
    private fun syncWarmAnimation() {
        if (hatching) return
        val name = settings.eggWarmAnimation
        if (name.isBlank()) return
        val handler = activeModel()?.animationHandler ?: return
        if (heated) handler.playAnimation(name, 0.2, 0.2, 1.0, true)?.setForceOverride(BlueprintAnimation.OverrideMode.OVERRIDE)
        else handler.stopAnimation(name)
    }

    /** Starts the hatch animation. False if the egg isn't spawned or has no such animation. */
    fun startHatching(): Boolean {
        val name = settings.eggHatchAnimation
        val handler = activeModel()?.animationHandler ?: return false
        if (name.isBlank()) return false
        handler.stopAnimation(settings.eggWarmAnimation)
        val playing = handler.playAnimation(name, 0.0, 0.0, 1.0, true) ?: return false
        playing.setForceOverride(BlueprintAnimation.OverrideMode.OVERRIDE)
        hatching = true
        display?.remove()
        display = null
        return true
    }

    private fun countHeat(center: Location): Int {
        val r = settings.heatScanRadius
        val heatBlocks = settings.heatBlocks
        val world = center.world
        var count = 0
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val block = world.getBlockAt(center.blockX + dx, center.blockY + dy, center.blockZ + dz)
            if (block.type !in heatBlocks) continue
            // Unlit campfires don't count.
            val data = block.blockData
            if (data is Lightable && !data.isLit) continue
            count++
        }
        return count
    }

    private fun refreshDisplay() {
        if (hatching) return
        val textDisplay = display ?: return
        val hatchMillis = settings.eggHatchMillis.coerceAtLeast(1L)
        val percent = (progressMillis * 100 / hatchMillis).coerceIn(0, 100)
        val template = if (heated) settings.eggDisplayWarming else settings.eggDisplayIdle
        val text = Settings.format(
            template,
            "%percent%" to percent.toString(),
            "%time%" to formatDuration((hatchMillis - progressMillis).coerceAtLeast(0L)),
            "%count%" to settings.heatRequiredCount.toString(),
        )
        textDisplay.text(Settings.MINI.deserialize(text))
    }

    fun save() = plugin.db.saveEgg(toRow())

    fun toRow() = EggRow(uuid, owner, world, x, y, z, progressMillis, heated, checkedAt, skin)

    companion object {
        fun fromRow(row: EggRow) = DragonEgg(
            row.uuid, row.owner, row.world, row.x, row.y, row.z, row.skin ?: VysDragons.instance.settings.skins.random(),
            row.progressMillis, row.heated, row.checkedAt,
        )
    }
}
