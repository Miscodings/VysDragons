package com.vys.vysdragons

import com.vys.vysdragons.dragon.DragonStage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration

/** Typed read of config.yml. Rebuilt on /vysdragons reload. */
class Settings(private val cfg: FileConfiguration, private val log: (String) -> Unit) {

    private val minuteMillis = 60_000L
    private val dayMillis = (cfg.getDouble("time.real-minutes-per-minecraft-day", 20.0) * minuteMillis).toLong()

    val eggHatchMillis = (cfg.getDouble("time.egg-hatch-days", 3.0) * dayMillis).toLong()
    private val babyMillis = (cfg.getDouble("time.baby-days", 30.0) * dayMillis).toLong()
    private val youngMillis = (cfg.getDouble("time.young-days", 60.0) * dayMillis).toLong()

    /** How long a dragon spends in [stage] before growing, or null for ADULT. */
    fun stageMillis(stage: DragonStage): Long? = when (stage) {
        DragonStage.BABY -> babyMillis
        DragonStage.YOUNG -> youngMillis
        DragonStage.ADULT -> null
    }

    // ---- egg ----
    val eggItemId: String = cfg.getString("egg.nexo-item", "dragon_egg_fire")!!
    val eggMobType: String = cfg.getString("egg.mob-type", "dragon_egg_fire")!!
    val heatBlocks: Set<Material> = cfg.getStringList("egg.heat.blocks").mapNotNull { name ->
        Material.matchMaterial(name) ?: null.also { log("Unknown heat block '$name' in config.yml") }
    }.toSet()
    val heatRequiredCount = cfg.getInt("egg.heat.required-count", 8).coerceAtLeast(1)
    val heatScanRadius = cfg.getInt("egg.heat.scan-radius", 2).coerceIn(0, 8)
    val eggCheckIntervalTicks = cfg.getLong("egg.check-interval-ticks", 20L).coerceAtLeast(1L)
    val eggDisplayEnabled = cfg.getBoolean("egg.display.enabled", true)
    val eggDisplayHeight = cfg.getDouble("egg.display.height", 0.9)
    val eggDisplayWarming: String = cfg.getString("egg.display.warming", "")!!
    val eggDisplayIdle: String = cfg.getString("egg.display.idle", "")!!

    // ---- dragon ----
    fun mobType(stage: DragonStage): String = cfg.getString("dragon.mob-types.${stage.key}", "")!!
    fun setupSkill(stage: DragonStage): String = cfg.getString("dragon.setup-skills.${stage.key}", "")!!
    val orphanResetSkill: String = cfg.getString("dragon.orphan-reset-skill", "")!!
    val breathElement: String = cfg.getString("dragon.breath-element", "fire")!!
    val skins: List<String> = cfg.getStringList("dragon.skins").ifEmpty { listOf("scarlet_black") }
    private val modelPattern = cfg.getString("dragon.model", "robust_dragon_%skin%_%stage%")!!
    private val saddledModelPattern = cfg.getString("dragon.saddled-model", "robust_dragon_%skin%_%stage%_saddled")!!

    fun modelId(skin: String, stage: DragonStage, saddled: Boolean): String =
        (if (saddled && stage.rideable) saddledModelPattern else modelPattern)
            .replace("%skin%", skin)
            .replace("%stage%", stage.key)

    fun maxHealth(stage: DragonStage) = cfg.getDouble("dragon.max-health.${stage.key}", 20.0).coerceAtLeast(1.0)
    fun attackDamage(stage: DragonStage) = cfg.getDouble("dragon.attack-damage.${stage.key}", 0.0)

    val saddleItem: Material = material("dragon.saddle-item", Material.SADDLE)
    val unsaddleItem: Material = material("dragon.unsaddle-item", Material.SHEARS)
    val passengerSeat: String = cfg.getString("dragon.passenger-seat", "seat2")!!

    fun teleportDistance(stage: DragonStage) = cfg.getDouble("dragon.follow.teleport-distance.${stage.key}", 15.0)
    val followStopDistance = cfg.getDouble("dragon.follow.stop-distance", 3.0)
    val followSpeed = cfg.getDouble("dragon.follow.speed", 1.0)

    val animLerpIn = cfg.getDouble("dragon.animations.lerp-in", 0.2)
    val animLerpOut = cfg.getDouble("dragon.animations.lerp-out", 0.2)
    val animSpeed = cfg.getDouble("dragon.animations.speed", 1.0)
    val sitAnimation: String = cfg.getString("dragon.animations.sit", "sit")!!
    val quirkAnimation: String = cfg.getString("dragon.animations.baby-quirk", "quirk")!!
    val quirkMinSeconds = cfg.getInt("dragon.animations.baby-quirk-min-seconds", 30).coerceAtLeast(1)
    val quirkMaxSeconds = cfg.getInt("dragon.animations.baby-quirk-max-seconds", 90).coerceAtLeast(quirkMinSeconds)

    val nameplate: String = cfg.getString("dragon.nameplate", "")!!
    fun stageName(stage: DragonStage): String = cfg.getString("dragon.stage-names.${stage.key}", stage.key)!!

    // ---- messages ----
    private val prefix = cfg.getString("messages.prefix", "")!!

    fun message(key: String, vararg replacements: Pair<String, String>): Component =
        MINI.deserialize(prefix + format(cfg.getString("messages.$key", key)!!, *replacements))

    private fun material(path: String, fallback: Material): Material {
        val name = cfg.getString(path) ?: return fallback
        return Material.matchMaterial(name) ?: fallback.also { log("Unknown item '$name' at $path, using $fallback") }
    }

    companion object {
        val MINI: MiniMessage = MiniMessage.miniMessage()

        fun format(text: String, vararg replacements: Pair<String, String>): String =
            replacements.fold(text) { acc, (k, v) -> acc.replace(k, v) }
    }
}
