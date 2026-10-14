package com.vys.vysdragons.egg

import com.nexomc.nexo.api.NexoItems
import com.vys.vysdragons.VysDragons
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.floor

class EggManager(private val plugin: VysDragons) {

    private val eggs = mutableMapOf<UUID, DragonEgg>()
    private val byEntity = mutableMapOf<UUID, DragonEgg>()
    private val droppedProgress = mutableMapOf<UUID, Long>()
    private var tickTask: BukkitTask? = null

    val all: Collection<DragonEgg> get() = eggs.values

    fun load() {
        plugin.db.loadEggs().forEach { eggs[it.uuid] = DragonEgg.fromRow(it) }
        plugin.logger.info("Loaded ${eggs.size} egg(s)")
        eggs.values.forEach { it.activate() }
        startTicking()
    }

    /** Restarted on reload so a changed check-interval-ticks applies. */
    fun startTicking() {
        tickTask?.cancel()
        val interval = plugin.settings.eggCheckIntervalTicks
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickAll() }, interval, interval)
    }

    private fun tickAll() {
        val now = System.currentTimeMillis()
        eggs.values.toList().forEach { egg ->
            // Respawns a body that vanished while its chunk stayed loaded.
            if (egg.hatching) return@forEach
            if (!egg.isActive && !egg.activate()) return@forEach
            val wasHeated = egg.heated
            if (egg.tick(now)) hatch(egg)
            else if (egg.heated != wasHeated) egg.save()
        }
    }

    fun shutdown() {
        tickTask?.cancel()
        val now = System.currentTimeMillis()
        eggs.values.forEach {
            if (it.isActive) it.tick(now)
            it.deactivate(immediate = true)
            it.save()
        }
        eggs.clear()
        byEntity.clear()
    }

    /**
     * Places an egg for [owner]; [progressMillis] is carried over from a picked-up egg
     * item. Null if the egg mob couldn't be spawned (nothing is stored then).
     */
    fun place(owner: UUID, location: Location, skin: String, progressMillis: Long): DragonEgg? {
        val egg = DragonEgg(
            UUID.randomUUID(), owner, location.world.name, location.x, location.y, location.z, skin,
            progressMillis, heated = false, checkedAt = System.currentTimeMillis(),
        )
        if (!egg.activate()) return null
        eggs[egg.uuid] = egg
        egg.tick(System.currentTimeMillis())
        egg.save()
        return egg
    }

    /** A player hit the egg: remove it and drop the egg item, keeping its progress. */
    fun pickUp(egg: DragonEgg): Boolean {
        if (egg.hatching) return false
        val loc = egg.entity?.location ?: egg.location()
        egg.tick(System.currentTimeMillis())
        remove(egg)
        val item = createItem(egg.skin, 1, egg.progressMillis) ?: return true
        val dropped = loc?.let { it.world.dropItemNaturally(it, item) } ?: return true
        if (egg.progressMillis > 0L) droppedProgress[dropped.uniqueId] = egg.progressMillis
        return true
    }

    /**
     * Some item plugins swap a picked-up item for a fresh copy, wiping its PDC. The
     * progress of eggs dropped by [pickUp] is remembered per item entity and stamped
     * back on at pickup. Returns true if [stack] was changed.
     */
    fun restoreDroppedProgress(itemEntityId: UUID, stack: ItemStack): Boolean {
        val progress = droppedProgress.remove(itemEntityId) ?: return false
        if (progressOf(stack) == progress) return false
        stack.editMeta { it.persistentDataContainer.set(VysDragons.EGG_PROGRESS_KEY, PersistentDataType.LONG, progress) }
        return true
    }

    fun forgetDroppedItem(itemEntityId: UUID) {
        droppedProgress.remove(itemEntityId)
    }

    /** Admin: hatch now regardless of progress. */
    fun forceHatch(egg: DragonEgg) = hatch(egg)

    private fun hatch(egg: DragonEgg) {
        if (egg.hatching) return
        val loc = egg.location() ?: return
        val settings = plugin.settings
        // The egg stays registered (and saved) while it cracks, so a restart mid-animation
        // just hatches it again. The dragon appears partway through, the shell goes after.
        if (egg.isActive && egg.startHatching()) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (eggs[egg.uuid] !== egg) return@Runnable
                remove(egg, immediate = false)
                spawnDragon(egg, loc)
            }, settings.eggHatchSpawnTicks)
            return
        }
        remove(egg)
        spawnDragon(egg, loc)
    }

    private fun spawnDragon(egg: DragonEgg, loc: Location) {
        plugin.dragons.hatch(egg.owner, loc, egg.skin)
        Bukkit.getPlayer(egg.owner)?.sendMessage(plugin.settings.message("egg-hatched"))
    }

    /**
     * Forgets [egg]. A hatching egg keeps its shell on screen ([immediate] false) until the
     * hatch animation is done, then the shell goes.
     */
    fun remove(egg: DragonEgg, immediate: Boolean = true) {
        eggs.remove(egg.uuid)
        plugin.db.deleteEgg(egg.uuid)
        if (immediate || !egg.hatching) {
            egg.deactivate()
            return
        }
        val wait = (plugin.settings.eggHatchRemoveTicks - plugin.settings.eggHatchSpawnTicks).coerceAtLeast(1L)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { egg.deactivate() }, wait)
    }

    fun byEntity(entityId: UUID): DragonEgg? = byEntity[entityId]

    fun indexEntity(entityId: UUID, egg: DragonEgg) {
        byEntity[entityId] = egg
    }

    fun unindexEntity(entityId: UUID) {
        byEntity.remove(entityId)
    }

    fun onChunkLoad(chunk: Chunk) {
        eggs.values.filter { !it.isActive && it.isIn(chunk) }.forEach { it.activate() }
    }

    fun onChunkUnload(chunk: Chunk) {
        val now = System.currentTimeMillis()
        eggs.values.filter { it.isActive && it.isIn(chunk) }.forEach {
            it.tick(now)
            it.deactivate()
            it.save()
        }
    }

    private fun DragonEgg.isIn(chunk: Chunk): Boolean =
        world == chunk.world.name && (floor(x).toInt() shr 4) == chunk.x && (floor(z).toInt() shr 4) == chunk.z

    // ==================== item ====================

    /** The Nexo egg item for [skin], with [progressMillis] stamped on if non-zero. */
    fun createItem(skin: String, amount: Int, progressMillis: Long = 0L): ItemStack? {
        val item = NexoItems.itemFromId(plugin.settings.eggItemId(skin))?.build() ?: return null
        item.amount = amount
        if (progressMillis > 0L) {
            item.editMeta { it.persistentDataContainer.set(VysDragons.EGG_PROGRESS_KEY, PersistentDataType.LONG, progressMillis) }
        }
        return item
    }

    /** True for the per-skin egg items and for the old untyped one (which hatches a random skin). */
    fun isEggItem(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        val id = NexoItems.idFromItem(item) ?: return false
        return id.equals(plugin.settings.eggItemId, ignoreCase = true) || plugin.settings.skinOfEggItem(id) != null
    }

    /** The skin of an egg item; a random one for the old untyped item. */
    fun skinOf(item: ItemStack): String =
        plugin.settings.skinOfEggItem(NexoItems.idFromItem(item)) ?: plugin.settings.skins.random()

    fun progressOf(item: ItemStack): Long =
        item.persistentDataContainer.get(VysDragons.EGG_PROGRESS_KEY, PersistentDataType.LONG) ?: 0L

    /** Gives [amount] eggs of [skin], or of a random skin each when [skin] is null. */
    fun give(player: Player, amount: Int, skin: String?): Boolean {
        repeat(amount) {
            val item = createItem(skin ?: plugin.settings.skins.random(), 1) ?: return false
            player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        return true
    }
}
