package com.vys.vysdragons.egg

import com.vys.vysdragons.Settings
import com.vys.vysdragons.VysDragons
import com.vys.vysdragons.data.EggRow
import com.vys.vysdragons.util.SpawnBypass
import com.vys.vysdragons.util.formatDuration
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
    progressMillis: Long,
    heated: Boolean,
    checkedAt: Long,
) {
    var progressMillis = progressMillis
        private set
    var heated = heated
        private set
    private var checkedAt = checkedAt

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

        val mythicMob = MythicBukkit.inst().mobManager.getMythicMob(settings.eggMobType).orElse(null)
        if (mythicMob == null) {
            plugin.logger.warning("MythicMobs mob '${settings.eggMobType}' not found - egg $uuid can't spawn")
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

        if (settings.eggDisplayEnabled) {
            display = loc.world.spawn(loc.clone().add(0.0, settings.eggDisplayHeight, 0.0), TextDisplay::class.java) {
                it.isPersistent = false
                it.billboard = Display.Billboard.CENTER
                it.persistentDataContainer.set(VysDragons.EGG_KEY, PersistentDataType.STRING, uuid.toString())
            }
        }
        return true
    }

    fun deactivate() {
        val body = entity
        entity = null
        if (body != null) {
            plugin.eggs.unindexEntity(body.uniqueId)
            runCatching { MythicBukkit.inst().mobManager.getActiveMob(body.uniqueId).orElse(null)?.remove() }
            if (body.isValid) body.remove()
        }
        display?.remove()
        display = null
    }

    /** Credits time since the last check, then re-checks heat. True once ready to hatch. */
    fun tick(now: Long): Boolean {
        if (heated) progressMillis += (now - checkedAt).coerceAtLeast(0L)
        checkedAt = now
        location()?.let { heated = countHeat(it) >= settings.heatRequiredCount }
        refreshDisplay()
        return progressMillis >= settings.eggHatchMillis
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

    fun toRow() = EggRow(uuid, owner, world, x, y, z, progressMillis, heated, checkedAt)

    companion object {
        fun fromRow(row: EggRow) = DragonEgg(
            row.uuid, row.owner, row.world, row.x, row.y, row.z, row.progressMillis, row.heated, row.checkedAt,
        )
    }
}
