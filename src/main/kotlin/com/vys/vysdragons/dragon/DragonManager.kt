package com.vys.vysdragons.dragon

import com.vys.vysdragons.VysDragons
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.floor

class DragonManager(private val plugin: VysDragons) {

    private val dragons = mutableMapOf<UUID, Dragon>()
    private val byEntity = mutableMapOf<UUID, Dragon>()
    private var tickTask: BukkitTask? = null
    private var saveTask: BukkitTask? = null

    val all: Collection<Dragon> get() = dragons.values

    fun load() {
        for (row in plugin.db.loadDragons()) {
            val dragon = Dragon.fromRow(row)
            if (dragon == null) {
                plugin.logger.warning("Skipping dragon ${row.uuid} with unknown stage '${row.stage}'")
                continue
            }
            dragons[dragon.uuid] = dragon
        }
        plugin.logger.info("Loaded ${dragons.size} dragon(s)")
        dragons.values.forEach { it.activate() }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()
            dragons.values.toList().forEach {
                // Respawns a body that vanished while its chunk stayed loaded.
                if (it.isActive || it.activate()) it.tick(now)
            }
        }, 20L, 20L)
        // Positions change constantly while following; persist them every minute.
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            dragons.values.filter { it.isActive }.forEach { it.save() }
        }, 1200L, 1200L)
    }

    fun shutdown() {
        tickTask?.cancel()
        saveTask?.cancel()
        dragons.values.forEach {
            it.deactivate(immediate = true)
            it.save()
        }
        dragons.clear()
        byEntity.clear()
    }

    /** Creates a fresh baby of [skin] at [location] for [owner], e.g. from a hatched egg. */
    fun hatch(owner: UUID, location: Location, skin: String): Dragon {
        val dragon = Dragon(
            uuid = UUID.randomUUID(),
            owner = owner,
            skin = skin,
            stage = DragonStage.BABY,
            stageStartedAt = System.currentTimeMillis(),
            saddled = false,
            sitting = false,
            stored = false,
            world = location.world.name,
            x = location.x,
            y = location.y,
            z = location.z,
            yaw = location.yaw,
        )
        // One dragon out at a time: the newborn replaces whatever was out.
        owned(owner).forEach { if (!it.stored) it.store() }
        dragons[dragon.uuid] = dragon
        dragon.save()
        dragon.activate()
        return dragon
    }

    fun owned(owner: UUID): List<Dragon> = dragons.values.filter { it.owner == owner }

    fun byId(uuid: UUID): Dragon? = dragons[uuid]

    enum class SummonResult { OK, RIDDEN, FAILED }

    /** Brings [dragon] to [player], putting away any other dragon of theirs that's out. */
    fun summon(dragon: Dragon, player: Player): SummonResult {
        val others = owned(dragon.owner).filter { it !== dragon && !it.stored }
        if (dragon.isRidden() || others.any { it.isRidden() }) return SummonResult.RIDDEN
        others.forEach { it.store() }
        if (!dragon.summon(player.location)) {
            dragon.store()
            return SummonResult.FAILED
        }
        return SummonResult.OK
    }

    /** False if someone is riding it. */
    fun store(dragon: Dragon): Boolean {
        if (dragon.isRidden()) return false
        dragon.store()
        return true
    }

    /** Permanently forgets a dragon (death). */
    fun remove(dragon: Dragon) {
        dragon.deactivate()
        dragons.remove(dragon.uuid)
        plugin.db.deleteDragon(dragon.uuid)
    }

    fun byEntity(entityId: UUID): Dragon? = byEntity[entityId]

    fun indexEntity(entityId: UUID, dragon: Dragon) {
        byEntity[entityId] = dragon
    }

    fun unindexEntity(entityId: UUID) {
        byEntity.remove(entityId)
    }

    fun onChunkLoad(chunk: Chunk) {
        dragons.values
            .filter { !it.isActive && it.isIn(chunk) }
            .forEach { it.activate() }
    }

    fun onChunkUnload(chunk: Chunk) {
        dragons.values
            .filter { dragon -> dragon.entity?.let { it.chunk == chunk } == true }
            .forEach {
                it.deactivate()
                it.save()
            }
    }

    private fun Dragon.isIn(chunk: Chunk): Boolean =
        world == chunk.world.name && (floor(x).toInt() shr 4) == chunk.x && (floor(z).toInt() shr 4) == chunk.z
}
