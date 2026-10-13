package com.vys.vysdragons.command

import com.vys.vysdragons.Settings
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

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "give" -> give(sender, args)
            "reload" -> {
                plugin.reloadSettings()
                sender.sendMessage(plugin.settings.message("reloaded"))
            }
            "info" -> info(sender)
            "grow" -> grow(sender)
            else -> sender.sendMessage(Settings.MINI.deserialize("<gray>/$label <give <player> [amount]|reload|info|grow>"))
        }
        return true
    }

    private fun give(sender: CommandSender, args: Array<out String>) {
        val target = args.getOrNull(1)?.let { Bukkit.getPlayerExact(it) }
        if (target == null) {
            sender.sendMessage(Settings.MINI.deserialize("<red>Player not found."))
            return
        }
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
        if (plugin.eggs.give(target, amount)) {
            sender.sendMessage(plugin.settings.message("give-success", "%amount%" to amount.toString(), "%player%" to target.name))
        } else {
            sender.sendMessage(plugin.settings.message("give-failed", "%item%" to plugin.settings.eggItemId))
        }
    }

    private fun info(sender: CommandSender) {
        val player = sender as? Player ?: return
        val target = target(player) ?: return player.sendMessage(Settings.MINI.deserialize("<red>Look at a dragon or egg."))
        val now = System.currentTimeMillis()
        plugin.dragons.byEntity(target.uniqueId)?.let { dragon ->
            val left = dragon.remainingStageMillis(now)?.let { formatDuration(it) } ?: "fully grown"
            val ownerName = Bukkit.getOfflinePlayer(dragon.owner).name ?: dragon.owner.toString()
            player.sendMessage(
                Settings.MINI.deserialize(
                    "<gray>Dragon <white>${dragon.uuid}</white> owner <white>$ownerName</white> skin <white>${dragon.skin}</white> " +
                        "stage <white>${dragon.stage.key}</white> next stage in <white>$left</white> saddled <white>${dragon.saddled}</white>"
                )
            )
            return
        }
        plugin.eggs.byEntity(target.uniqueId)?.let { egg ->
            val left = formatDuration((plugin.settings.eggHatchMillis - egg.progressMillis).coerceAtLeast(0L))
            player.sendMessage(Settings.MINI.deserialize("<gray>Egg heated <white>${egg.heated}</white>, hatches after <white>$left</white> more heat"))
            return
        }
        player.sendMessage(Settings.MINI.deserialize("<red>Look at a dragon or egg."))
    }

    private fun grow(sender: CommandSender) {
        val player = sender as? Player ?: return
        val target = target(player)
        val dragon = target?.let { plugin.dragons.byEntity(it.uniqueId) }
        if (dragon != null) {
            val ok = dragon.forceGrow()
            player.sendMessage(Settings.MINI.deserialize(if (ok) "<green>It will grow on the next tick." else "<red>Already an adult."))
            return
        }
        val egg = target?.let { plugin.eggs.byEntity(it.uniqueId) }
        if (egg != null) {
            plugin.eggs.forceHatch(egg)
            player.sendMessage(Settings.MINI.deserialize("<green>Hatched."))
            return
        }
        player.sendMessage(Settings.MINI.deserialize("<red>Look at a dragon or egg."))
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
