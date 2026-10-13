package com.vys.vysdragons

import com.vys.vysdragons.command.VysDragonsCommand
import com.vys.vysdragons.data.Database
import com.vys.vysdragons.dragon.DragonManager
import com.vys.vysdragons.egg.EggManager
import com.vys.vysdragons.listener.DragonListener
import com.vys.vysdragons.menu.DragonMenu
import org.bukkit.entity.Player
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
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
            if (sender is Player) DragonMenu(sender).open() else sender.sendMessage(settings.message("players-only"))
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
        settings = Settings(config, loadLang()) { logger.warning(it) }
        if (::eggs.isInitialized) eggs.startTicking()
    }

    /** plugins/VysDragons/lang.yml, with the jar's copy as defaults for any missing keys. */
    private fun loadLang(): YamlConfiguration {
        val file = File(dataFolder, "lang.yml")
        if (!file.exists()) saveResource("lang.yml", false)
        val lang = YamlConfiguration.loadConfiguration(file)
        getResource("lang.yml")?.reader(Charsets.UTF_8)?.use { lang.setDefaults(YamlConfiguration.loadConfiguration(it)) }
        return lang
    }

    companion object {
        lateinit var instance: VysDragons
            private set

        val DRAGON_KEY by lazy { NamespacedKey(instance, "dragon") }
        val EGG_KEY by lazy { NamespacedKey(instance, "egg") }
        val EGG_PROGRESS_KEY by lazy { NamespacedKey(instance, "egg_progress") }
    }
}
