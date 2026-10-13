package com.vys.vysdragons.command

import com.vys.vysdragons.VysDragons
import com.vys.vysdragons.util.formatDuration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

/**
 * /vysdragons give <player> [amount] - give egg items
 * /vysdragons reload                 - reload config.yml
 * /vysdragons info                   - stage/timer of the dragon or egg you're looking at
 * /vysdragons grow                   - finish the current stage/incubation of what you're looking at
 */
class VysDragonsCommand(private val plugin: VysDragons) : TabExecutor {

    private val settings get() = plugin.settings

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "give" -> give(sender, args)
            "reload" -> {
                plugin.reloadSettings()
                sender.sendMessage(settings.message("reloaded"))
            }
            "info" -> info(sender)
            "grow" -> grow(sender)
            else -> sender.sendMessage(settings.message("usage", "%label%" to label))
        }
        return true
    }

    private fun give(sender: CommandSender, args: Array<out String>) {
        val target = args.getOrNull(1)?.let { Bukkit.getPlayerExact(it) }
            ?: return sender.sendMessage(settings.message("player-not-found"))
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
        if (plugin.eggs.give(target, amount)) {
            sender.sendMessage(settings.message("give-success", "%amount%" to amount.toString(), "%player%" to target.name))
        } else {
            sender.sendMessage(settings.message("give-failed", "%item%" to settings.eggItemId))
        }
    }

    private fun info(sender: CommandSender) {
        val player = sender as? Player ?: return sender.sendMessage(settings.message("players-only"))
        val target = target(player) ?: return player.sendMessage(settings.message("no-target"))
        val now = System.currentTimeMillis()
        plugin.dragons.byEntity(target.uniqueId)?.let { dragon ->
            val next = dragon.remainingStageMillis(now)?.let { formatDuration(it) } ?: settings.text("info-fully-grown")
            player.sendMessage(
                settings.message(
                    "info-dragon",
                    "%uuid%" to dragon.uuid.toString(),
                    "%owner%" to (Bukkit.getOfflinePlayer(dragon.owner).name ?: dragon.owner.toString()),
                    "%skin%" to dragon.skin,
                    "%stage%" to settings.stageName(dragon.stage),
                    "%next%" to next,
                    "%saddled%" to dragon.saddled.toString(),
                    "%stored%" to dragon.stored.toString(),
                )
            )
            return
        }
        plugin.eggs.byEntity(target.uniqueId)?.let { egg ->
            val left = formatDuration((settings.eggHatchMillis - egg.progressMillis).coerceAtLeast(0L))
            player.sendMessage(settings.message("info-egg", "%heated%" to egg.heated.toString(), "%time%" to left))
            return
        }
        player.sendMessage(settings.message("no-target"))
    }

    private fun grow(sender: CommandSender) {
        val player = sender as? Player ?: return sender.sendMessage(settings.message("players-only"))
        val target = target(player)
        target?.let { plugin.dragons.byEntity(it.uniqueId) }?.let { dragon ->
            player.sendMessage(settings.message(if (dragon.forceGrow()) "grow-dragon" else "grow-already-adult"))
            return
        }
        target?.let { plugin.eggs.byEntity(it.uniqueId) }?.let { egg ->
            plugin.eggs.forceHatch(egg)
            player.sendMessage(settings.message("grow-egg"))
            return
        }
        player.sendMessage(settings.message("no-target"))
    }

    /**
     * The dragon or egg the player is looking at. ModelEngine models replace the mob's
     * hitbox, so the vanilla ray trace often misses them; falls back to the nearest
     * dragon or egg within 8 blocks.
     */
    private fun target(player: Player): org.bukkit.entity.Entity? {
        player.getTargetEntity(10)?.takeIf { isTracked(it) }?.let { return it }
        return player.getNearbyEntities(8.0, 8.0, 8.0)
            .filter { isTracked(it) }
            .minByOrNull { it.location.distanceSquared(player.location) }
    }

    private fun isTracked(entity: org.bukkit.entity.Entity) =
        plugin.dragons.byEntity(entity.uniqueId) != null || plugin.eggs.byEntity(entity.uniqueId) != null

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        when (args.size) {
            1 -> listOf("give", "reload", "info", "grow").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("give", true)) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) } else emptyList()
            else -> emptyList()
        }
}
