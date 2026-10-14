package com.vys.vysdragons.listener

import com.vys.vysdragons.VysDragons
import com.vys.vysdragons.dragon.Dragon
import com.vys.vysdragons.dragon.DragonStage
import com.vys.vysdragons.util.SpawnBypass
import com.ticxo.modelengine.api.events.BaseEntityInteractEvent
import io.lumine.mythic.bukkit.events.MythicMobInteractEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID

class DragonListener(private val plugin: VysDragons) : Listener {

    private val settings get() = plugin.settings

    /** Holding right-click repeats the interact ~4x/sec; one toggle per click. */
    private val lastInteract = mutableMapOf<UUID, Long>()

    private fun debounce(entityId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val last = lastInteract.put(entityId, now)
        return last == null || now - last >= 350
    }

    // ==================== lifecycle ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSpawn(event: CreatureSpawnEvent) {
        if (event.isCancelled && SpawnBypass.active) event.isCancelled = false
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        // Spawning inside the load event itself is unreliable; wait a tick.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!chunk.isLoaded) return@Runnable
            plugin.eggs.onChunkLoad(chunk)
            plugin.dragons.onChunkLoad(chunk)
        })
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        plugin.eggs.onChunkUnload(event.chunk)
        plugin.dragons.onChunkUnload(event.chunk)
    }

    // ==================== eggs ====================

    @EventHandler
    fun onPlaceEgg(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        if (!plugin.eggs.isEggItem(item)) return
        val block = event.clickedBlock ?: return
        event.isCancelled = true

        val target = block.getRelative(event.blockFace).location.add(0.5, 0.0, 0.5)
        if (plugin.eggs.place(event.player.uniqueId, target, plugin.eggs.skinOf(item), plugin.eggs.progressOf(item)) == null) {
            event.player.sendMessage(settings.message("egg-place-failed"))
            return
        }
        item.amount -= 1
        event.player.sendMessage(settings.message("egg-placed", "%count%" to settings.heatRequiredCount.toString()))
    }

    /** Eggs sit in fire and lava, so they ignore all damage; a player hit picks one up. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onEggDamage(event: EntityDamageEvent) {
        val egg = plugin.eggs.byEntity(event.entity.uniqueId) ?: return
        event.isCancelled = true
        val damager = (event as? EntityDamageByEntityEvent)?.damager
        val player = damager as? Player ?: (damager as? Projectile)?.shooter as? Player ?: return
        if (plugin.eggs.pickUp(egg)) player.sendMessage(settings.message("egg-picked-up"))
    }

    /**
     * The egg's model has its own hitbox, so ModelEngine handles clicks on it and the
     * vanilla damage event above may never fire. This catches those hits. ModelEngine
     * can fire it off the main thread, so the pickup is moved onto it.
     */
    @EventHandler
    fun onEggModelHit(event: BaseEntityInteractEvent) {
        if (event.action != BaseEntityInteractEvent.Action.ATTACK) return
        val entityId = event.baseEntity.uuid
        val player = event.player
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val egg = plugin.eggs.byEntity(entityId) ?: return@Runnable
            if (plugin.eggs.pickUp(egg)) player.sendMessage(settings.message("egg-picked-up"))
        })
    }

    @EventHandler
    fun onEggCombust(event: EntityCombustEvent) {
        if (plugin.eggs.byEntity(event.entity.uniqueId) != null) event.isCancelled = true
    }

    /** HIGHEST so it runs after item plugins that replace picked-up items with fresh copies. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEggItemPickup(event: PlayerAttemptPickupItemEvent) {
        val stack: ItemStack = event.item.itemStack
        if (plugin.eggs.restoreDroppedProgress(event.item.uniqueId, stack)) event.item.itemStack = stack
    }

    @EventHandler
    fun onEggItemDespawn(event: ItemDespawnEvent) {
        plugin.eggs.forgetDroppedItem(event.entity.uniqueId)
    }

    // ==================== dragons ====================

    /**
     * MythicMobs fires this before running the mob's ~onInteract skills (mount,
     * breath). Cancelling it stops those skills, so it's only cancelled when the plugin
     * handled the click itself.
     */
    @EventHandler
    fun onDragonInteract(event: MythicMobInteractEvent) {
        val entityId = event.activeMob.entity.bukkitEntity.uniqueId
        val dragon = plugin.dragons.byEntity(entityId) ?: return
        val player = event.player

        if (player.uniqueId != dragon.owner) {
            event.setCancelled()
            if (!debounce(entityId)) return
            handleStranger(dragon, player)
            return
        }

        // Riding: breath and dismount belong to MythicMobs.
        if (dragon.isRidden()) return

        val item = player.inventory.itemInMainHand
        when {
            item.type == settings.saddleItem && !dragon.saddled -> {
                event.setCancelled()
                if (!debounce(entityId)) return
                if (!dragon.stage.rideable) {
                    player.sendMessage(settings.message("too-young-to-saddle"))
                    return
                }
                item.amount -= 1
                dragon.setSaddled(true)
                player.sendMessage(settings.message("saddled"))
            }

            player.isSneaking && dragon.saddled && item.type == settings.unsaddleItem -> {
                event.setCancelled()
                if (!debounce(entityId)) return
                dragon.setSaddled(false)
                player.inventory.addItem(ItemStack(settings.saddleItem)).values
                    .forEach { player.world.dropItemNaturally(player.location, it) }
                player.sendMessage(settings.message("unsaddled"))
            }

            player.isSneaking -> {
                event.setCancelled()
                if (!debounce(entityId)) return
                dragon.setSitting(!dragon.sitting)
                player.sendMessage(settings.message(if (dragon.sitting) "sitting" else "following"))
            }

            dragon.stage.rideable && !dragon.saddled -> {
                if (debounce(entityId)) player.sendMessage(settings.message("needs-saddle"))
            }
            // Otherwise MythicMobs' mount skill takes it from here.
        }
    }

    /** Anyone but the owner: adults take a passenger while the owner is riding. */
    private fun handleStranger(dragon: Dragon, player: Player) {
        if (dragon.stage == DragonStage.ADULT && dragon.saddled && dragon.hasDriver()) {
            if (!dragon.seatPassenger(player)) player.sendMessage(settings.message("seat-taken"))
            return
        }
        player.sendMessage(settings.message("not-your-dragon"))
    }

    /** Dragons and eggs are WOLF-based; block vanilla sit/feed/breed on them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVanillaInteract(event: PlayerInteractEntityEvent) {
        val id = event.rightClicked.uniqueId
        if (plugin.dragons.byEntity(id) != null || plugin.eggs.byEntity(id) != null) event.isCancelled = true
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val id = event.entity.uniqueId
        plugin.dragons.byEntity(id)?.let { dragon ->
            event.drops.clear()
            event.droppedExp = 0
            plugin.dragons.remove(dragon)
            Bukkit.getPlayer(dragon.owner)?.sendMessage(settings.message("died"))
            return
        }
        // Eggs ignore damage, but /kill and the like still get through: hand the egg back.
        plugin.eggs.byEntity(id)?.let { egg ->
            event.drops.clear()
            plugin.eggs.pickUp(egg)
        }
    }
}
