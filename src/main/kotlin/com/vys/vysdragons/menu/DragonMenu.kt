package com.vys.vysdragons.menu

import com.vys.vysdragons.Settings
import com.vys.vysdragons.VysDragons
import com.vys.vysdragons.dragon.Dragon
import com.vys.vysdragons.dragon.DragonManager.SummonResult
import com.vys.vysdragons.util.formatDuration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** /dragons: one icon per owned dragon. Click a stored one to summon it, an out one to put it away. */
class DragonMenu(private val player: Player) : InventoryHolder {

    private val plugin get() = VysDragons.instance
    private val settings get() = plugin.settings
    private val slots = mutableMapOf<Int, UUID>()
    private val inv: Inventory

    init {
        val dragons = plugin.dragons.owned(player.uniqueId)
            .onEach { it.catchUpGrowth() }
            .sortedWith(compareBy<Dragon>({ it.stored }, { -it.stage.ordinal }))
        val rows = ((dragons.size + 8) / 9).coerceIn(1, 6)
        inv = Bukkit.createInventory(this, rows * 9, Settings.MINI.deserialize(settings.menuTitle))
        render(dragons.take(rows * 9))
    }

    override fun getInventory(): Inventory = inv

    fun open() {
        player.openInventory(inv)
    }

    private fun render(dragons: List<Dragon>) {
        inv.clear()
        slots.clear()
        if (dragons.isEmpty()) {
            inv.setItem(4, icon(settings.menuEmptyItem, settings.menuEmptyName, emptyList()))
            return
        }
        val now = System.currentTimeMillis()
        dragons.forEachIndexed { slot, dragon ->
            val growth = dragon.remainingStageMillis(now)
                ?.let { Settings.format(settings.menuGrowsIn, "%time%" to formatDuration(it)) }
                ?: settings.menuFullyGrown
            val replacements = arrayOf(
                "%skin%" to dragon.skin.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
                "%stage%" to settings.stageName(dragon.stage),
                "%growth%" to growth,
                "%saddled%" to if (dragon.saddled) settings.menuSaddledYes else settings.menuSaddledNo,
            )
            val lore = if (dragon.stored) settings.menuLoreStored else settings.menuLoreOut
            inv.setItem(
                slot,
                icon(
                    if (dragon.stored) settings.menuItemStored else settings.menuItemOut,
                    Settings.format(settings.menuName, *replacements),
                    lore.map { Settings.format(it, *replacements) },
                ),
            )
            slots[slot] = dragon.uuid
        }
    }

    private fun icon(material: org.bukkit.Material, name: String, lore: List<String>): ItemStack =
        ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(plain(name))
                meta.lore(lore.map { plain(it) })
            }
        }

    /** MiniMessage without the default italic item-name styling. */
    private fun plain(text: String): Component =
        Settings.MINI.deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    fun click(slot: Int) {
        val dragon = slots[slot]?.let { plugin.dragons.byId(it) } ?: return
        if (dragon.owner != player.uniqueId) return
        if (dragon.stored) {
            when (plugin.dragons.summon(dragon, player)) {
                SummonResult.OK -> player.sendMessage(settings.message("summoned"))
                SummonResult.RIDDEN -> player.sendMessage(settings.message("cant-while-ridden"))
                SummonResult.FAILED -> player.sendMessage(settings.message("summon-failed"))
            }
        } else {
            if (plugin.dragons.store(dragon)) player.sendMessage(settings.message("stored"))
            else player.sendMessage(settings.message("cant-while-ridden"))
        }
        player.closeInventory()
    }

    object Listener : org.bukkit.event.Listener {
        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            val menu = event.view.topInventory.holder as? DragonMenu ?: return
            event.isCancelled = true
            if (event.clickedInventory != event.view.topInventory) return
            menu.click(event.rawSlot)
        }

        @EventHandler
        fun onDrag(event: InventoryDragEvent) {
            if (event.view.topInventory.holder is DragonMenu) event.isCancelled = true
        }
    }
}
