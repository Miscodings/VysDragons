package com.vys.vysdragons

import com.vys.vysdragons.command.VysDragonsCommand
import com.vys.vysdragons.data.Database
import com.vys.vysdragons.dragon.DragonManager
import com.vys.vysdragons.egg.EggManager
import com.vys.vysdragons.listener.DragonListener
import com.vys.vysdragons.menu.DragonMenu
import org.bukkit.entity.Player
import org.bukkit.NamespacedKey
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class VysDragons : JavaPlugin() {

    lateinit var settings: Settings
        private set
    lateinit var db: Database
        private set
    lateinit var dragons: DragonManager
        private set
    lateinit var eggs: EggManager
        private set

    override fun onEnable() {
        instance = this
        saveDefaultConfig()
        reloadSettings()

        dataFolder.mkdirs()
        db = Database(File(dataFolder, "data.db"), logger)
        dragons = DragonManager(this)
        eggs = EggManager(this)

        server.pluginManager.registerEvents(DragonListener(this), this)
        server.pluginManager.registerEvents(DragonMenu.Listener, this)
        getCommand("dragons")?.setExecutor { sender, _, _, _ ->
            (sender as? Player)?.let { DragonMenu(it).open() }
            true
        }
        getCommand("vysdragons")?.let {
            val command = VysDragonsCommand(this)
            it.setExecutor(command)
            it.tabCompleter = command
        }

        eggs.load()
        dragons.load()
    }

    override fun onDisable() {
        if (::eggs.isInitialized) eggs.shutdown()
        if (::dragons.isInitialized) dragons.shutdown()
        if (::db.isInitialized) db.close()
    }

    fun reloadSettings() {
        reloadConfig()
        settings = Settings(config) { logger.warning(it) }
        if (::eggs.isInitialized) eggs.startTicking()
    }

    companion object {
        lateinit var instance: VysDragons
            private set

        val DRAGON_KEY by lazy { NamespacedKey(instance, "dragon") }
        val EGG_KEY by lazy { NamespacedKey(instance, "egg") }
        val EGG_PROGRESS_KEY by lazy { NamespacedKey(instance, "egg_progress") }
    }
}
