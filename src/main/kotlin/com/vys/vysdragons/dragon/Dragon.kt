package com.vys.vysdragons.dragon

import com.ticxo.modelengine.api.ModelEngineAPI
import com.ticxo.modelengine.api.animation.BlueprintAnimation
import com.ticxo.modelengine.api.model.ActiveModel
import com.vys.vysdragons.Settings
import com.vys.vysdragons.VysDragons
import com.vys.vysdragons.data.DragonRow
import com.vys.vysdragons.util.SpawnBypass
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.bukkit.MythicBukkit
import io.lumine.mythic.core.mobs.ActiveMob
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import kotlin.random.Random

/**
 * One owned dragon. The body only exists while its chunk is loaded: [activate]
 * spawns a non-persistent MythicMobs mob and attaches the stage's ModelEngine model,
 * [deactivate] removes it again. Everything that must survive lives in [toRow].
 */
class Dragon(
    val uuid: UUID,
    val owner: UUID,
    val skin: String,
    stage: DragonStage,
    stageStartedAt: Long,
    saddled: Boolean,
    sitting: Boolean,
    stored: Boolean,
    var world: String,
    var x: Double,
    var y: Double,
    var z: Double,
    var yaw: Float,
) {
    var stage = stage
        private set
    var stageStartedAt = stageStartedAt
        private set
    var saddled = saddled
        private set
    var sitting = sitting
        private set

    /** Put away by the owner: no body anywhere, but growth keeps running. */
    var stored = stored
        private set

    var entity: LivingEntity? = null
        private set

    /** Mob type the live body was spawned as, to know if a stage change needs a respawn. */
    private var spawnedMobType: String? = null
    private var nextQuirkAt = 0L
    private var riderlessTicks = 0

    private val plugin get() = VysDragons.instance
    private val settings get() = plugin.settings

    val isActive: Boolean get() = entity?.isValid == true

    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, 0f) }

    // ==================== lifecycle ====================

    /** Spawns the body if its chunk is loaded. Returns true if the dragon is live afterwards. */
    fun activate(): Boolean {
        if (isActive) return true
        if (stored) return false
        val loc = location() ?: return false
        if (!loc.world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return false

        // Wall-clock growth that happened while unloaded, before anything is spawned.
        catchUpGrowth()

        val mobType = settings.mobType(stage)
        val mythicMob = MythicBukkit.inst().mobManager.getMythicMob(mobType).orElse(null)
        if (mythicMob == null) {
            plugin.logger.warning("MythicMobs mob '$mobType' not found - dragon $uuid can't spawn")
            return false
        }
        val activeMob = SpawnBypass.run { mythicMob.spawn(BukkitAdapter.adapt(loc), 1.0) }
        val body = activeMob?.entity?.bukkitEntity as? LivingEntity
        if (activeMob == null || body == null) {
            plugin.logger.warning("Failed to spawn dragon $uuid as '$mobType' at $loc")
            activeMob?.remove()
            return false
        }

        body.isPersistent = false
        body.removeWhenFarAway = false
        body.persistentDataContainer.set(VysDragons.DRAGON_KEY, PersistentDataType.STRING, uuid.toString())
        body.setRotation(yaw, 0f)
        activeMob.setOwnerUUID(owner)
        (body as? Tameable)?.let {
            it.owner = Bukkit.getOfflinePlayer(owner)
            it.isTamed = true
        }

        entity = body
        spawnedMobType = mobType
        plugin.dragons.indexEntity(body.uniqueId, this)
        applyStage(activeMob, body, heal = true)
        scheduleQuirk(System.currentTimeMillis())
        return true
    }

    /**
     * Removes the body (chunk unload, shutdown, respawn). Position is captured first.
     * [immediate] skips the two-tick grace; only for shutdown, when tasks no longer run.
     */
    fun deactivate(immediate: Boolean = false) {
        val body = entity ?: return
        capturePosition(body)
        entity = null
        spawnedMobType = null
        plugin.dragons.unindexEntity(body.uniqueId)
        destroyBody(body, immediate)
    }

    private fun destroyBody(body: LivingEntity, immediate: Boolean) {
        runCatching { destroyModels(body) }
            .onFailure { plugin.logger.warning("ModelEngine cleanup failed for dragon $uuid: ${it.message}") }
        // Removing the body in the same tick can stop ModelEngine from telling players the
        // model is gone, leaving a frozen copy behind. Give it two ticks to send that first.
        val remove = Runnable {
            runCatching { MythicBukkit.inst().mobManager.getActiveMob(body.uniqueId).orElse(null)?.remove() }
            if (body.isValid) body.remove()
        }
        if (immediate) return remove.run()
        body.isInvisible = true
        Bukkit.getScheduler().runTaskLater(plugin, remove, 2L)
    }

    /** Model, attributes and MythicMobs variables for the current stage/saddle state. */
    private fun applyStage(activeMob: ActiveMob, body: LivingEntity, heal: Boolean) {
        val modelId = resolveModelId()

        body.getAttribute(Attribute.MAX_HEALTH)?.baseValue = settings.maxHealth(stage)
        body.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = settings.attackDamage(stage)
        if (heal) body.health = settings.maxHealth(stage)
        else body.health = body.health.coerceAtMost(settings.maxHealth(stage))

        activeMob.variables.apply {
            putString("model", modelId)
            putString("saddled", (saddled && stage.rideable).toString())
            putString("element", settings.breathElement)
            putString("state", "ground")
            putString("ownername", Bukkit.getOfflinePlayer(owner).name ?: "Unknown")
        }
        activeMob.setStance("idling")

        attachModel(body, modelId)

        settings.setupSkill(stage).takeIf { it.isNotBlank() }?.let { skill ->
            if (!MythicBukkit.inst().apiHelper.castSkill(body, skill)) {
                plugin.logger.warning("Setup skill '$skill' failed for dragon $uuid")
            }
        }

        updateNameplate(body)
        // ModelEngine finishes attaching on its next tick; animations sent before that are lost.
        if (sitting) later(5L) { playAnimation(settings.sitAnimation, loop = true) }
    }

    /**
     * The saddled model if it exists, else the plain one. A missing model would leave
     * the dragon invisible.
     */
    private fun resolveModelId(): String {
        val plain = settings.modelId(skin, stage, saddled = false)
        if (!saddled || !stage.rideable) return plain
        val saddledId = settings.modelId(skin, stage, saddled = true)
        if (ModelEngineAPI.getBlueprint(saddledId) != null) return saddledId
        plugin.logger.warning("Saddled model '$saddledId' doesn't exist, using '$plain' for dragon $uuid")
        return plain
    }

    private fun attachModel(body: LivingEntity, modelId: String) {
        val modeled = ModelEngineAPI.getOrCreateModeledEntity(body)
        modeled.setSaved(false)
        modeled.setBaseEntityVisible(false)
        // removeModel only detaches the model; destroy() is what removes it for players.
        modeled.models.keys.toList().forEach { id -> modeled.removeModel(id).ifPresent { it.destroy() } }

        if (ModelEngineAPI.getBlueprint(modelId) == null) {
            plugin.logger.warning("ModelEngine model '$modelId' doesn't exist (dragon $uuid)")
            return
        }
        val model = ModelEngineAPI.createActiveModel(modelId) ?: run {
            plugin.logger.warning("Couldn't create ModelEngine model '$modelId' (dragon $uuid)")
            return
        }
        modeled.addModel(model, true)
        Mounts.enable(model, stage.rideable, stage == DragonStage.ADULT)
    }

    private fun updateNameplate(body: LivingEntity) {
        val format = settings.nameplate
        if (format.isBlank()) {
            body.customName(null)
            return
        }
        val text = Settings.format(
            format,
            "%owner%" to (Bukkit.getOfflinePlayer(owner).name ?: "Unknown"),
            "%stage%" to settings.stageName(stage),
        )
        body.customName(Settings.MINI.deserialize(text))
        body.isCustomNameVisible = true
    }

    /** Dismounts riders and destroys every ModelEngine model on [body], then its ModeledEntity. */
    private fun destroyModels(body: LivingEntity) {
        val modeled = ModelEngineAPI.getModeledEntity(body.uniqueId) ?: return
        modeled.models.values.forEach { Mounts.of(it)?.dismountAll() }
        modeled.models.keys.toList().forEach { id -> modeled.removeModel(id).ifPresent { it.destroy() } }
        modeled.destroy()
        ModelEngineAPI.removeModeledEntity(body.uniqueId)
    }

    // ==================== ticking ====================

    /** Called once a second for every live dragon. */
    fun tick(now: Long) {
        val body = entity ?: return
        if (!body.isValid) {
            // Removed by something else (e.g. /kill); respawn on next chunk load.
            plugin.dragons.unindexEntity(body.uniqueId)
            entity = null
            return
        }
        capturePosition(body)

        if (isRidden(body)) {
            riderlessTicks = 0
            if (sitting) setSitting(false)
            return
        }
        if (stage.rideable) repairOrphanedFlightState(body)

        if (advanceStageIfDue(now)) {
            onGrown(body)
            return
        }

        if (stage == DragonStage.BABY && !sitting && now >= nextQuirkAt) {
            playAnimation(settings.quirkAnimation, loop = false)
            scheduleQuirk(now)
        }

        followOwner(body)
    }

    private fun followOwner(body: LivingEntity) {
        if (sitting) return
        val player = Bukkit.getPlayer(owner) ?: return
        if (player.world.uid != body.world.uid) return
        val distance = body.location.distance(player.location)

        if (distance > settings.teleportDistance(stage)) {
            // Don't follow an owner mid-air or mid-teleport onto unsafe ground.
            if (player.isFlying || player.isGliding || !player.location.subtract(0.0, 0.1, 0.0).block.type.isSolid) return
            body.teleport(player.location)
            grantTeleportGrace(body)
            return
        }
        if (distance <= settings.followStopDistance) return

        val activeMob = MythicBukkit.inst().mobManager.getActiveMob(body.uniqueId).orElse(null) ?: return
        MythicBukkit.inst().volatileCodeHandler.aiHandler.navigateToLocation(
            activeMob.entity, BukkitAdapter.adapt(player.location), settings.followSpeed,
        )
    }

    private fun grantTeleportGrace(body: LivingEntity) {
        body.isInvulnerable = true
        later(60L) { if (body.isValid) body.isInvulnerable = false }
    }

    // ==================== growth ====================

    /** Moves to the next stage if this one's time is up. Leftover time carries over. */
    private fun advanceStageIfDue(now: Long): Boolean {
        val duration = settings.stageMillis(stage) ?: return false
        val next = stage.next ?: return false
        if (now - stageStartedAt < duration) return false
        stageStartedAt += duration
        stage = next
        save()
        return true
    }

    private fun onGrown(body: LivingEntity) {
        Bukkit.getPlayer(owner)?.sendMessage(
            settings.message(if (stage == DragonStage.ADULT) "grew-adult" else "grew-young")
        )
        refreshBody(body)
    }

    /** Re-applies the current stage: in place if the mob type is unchanged, else respawns. */
    private fun refreshBody(body: LivingEntity) {
        val activeMob = MythicBukkit.inst().mobManager.getActiveMob(body.uniqueId).orElse(null)
        if (activeMob != null && spawnedMobType == settings.mobType(stage)) {
            applyStage(activeMob, body, heal = false)
        } else {
            deactivate()
            activate()
        }
    }

    /** Applies growth that happened while there was no body (unloaded or stored). */
    fun catchUpGrowth() {
        val now = System.currentTimeMillis()
        while (advanceStageIfDue(now)) continue
    }

    // ==================== store / summon ====================

    /** Removes the body and marks the dragon stored. */
    fun store() {
        deactivate()
        stored = true
        save()
    }

    /** Brings the dragon out at [loc] (normally its owner). False if it couldn't spawn there. */
    fun summon(loc: Location): Boolean {
        deactivate()
        world = loc.world.name
        x = loc.x
        y = loc.y
        z = loc.z
        yaw = loc.yaw
        stored = false
        sitting = false
        save()
        if (!activate()) return false
        entity?.let { grantTeleportGrace(it) }
        return true
    }

    /** Admin: skip the rest of the current stage. */
    fun forceGrow(): Boolean {
        val duration = settings.stageMillis(stage) ?: return false
        stageStartedAt = System.currentTimeMillis() - duration
        return true
    }

    fun remainingStageMillis(now: Long): Long? =
        settings.stageMillis(stage)?.let { (it - (now - stageStartedAt)).coerceAtLeast(0L) }

    // ==================== interactions ====================

    fun setSitting(value: Boolean) {
        if (sitting == value) return
        sitting = value
        if (value) {
            playAnimation(settings.sitAnimation, loop = true)
            entity?.let { MythicBukkit.inst().mobManager.getActiveMob(it.uniqueId).orElse(null)?.resetTarget() }
        } else {
            stopAnimation(settings.sitAnimation)
        }
        save()
    }

    fun setSaddled(value: Boolean) {
        if (saddled == value) return
        saddled = value
        save()
        entity?.let { refreshBody(it) }
    }

    /** True while anyone is sitting on the dragon. */
    fun isRidden(body: LivingEntity? = entity): Boolean {
        body ?: return false
        val manager = Mounts.of(activeModel(body)) ?: return false
        return manager.driver != null || manager.hasPassengers()
    }

    /**
     * The flight controller leaves caster.state at flying/occupied_ground if the rider
     * vanishes without dismounting (logout, death), and its mount skill only accepts
     * "ground" - so the dragon could never be ridden again. Two riderless ticks in a
     * row (the controller remounts within a single tick) put it back on the ground.
     */
    private fun repairOrphanedFlightState(body: LivingEntity) {
        val activeMob = MythicBukkit.inst().mobManager.getActiveMob(body.uniqueId).orElse(null) ?: return
        val state = activeMob.variables.getString("state")
        if (state == null || state == "ground") {
            riderlessTicks = 0
            return
        }
        if (++riderlessTicks < 2) return
        riderlessTicks = 0
        // The MythicMobs reset skill also restores the ground idle/walk animations.
        settings.orphanResetSkill.takeIf { it.isNotBlank() }?.let { MythicBukkit.inst().apiHelper.castSkill(body, it) }
        body.setGravity(true)
        activeMob.variables.putString("state", "ground")
        activeMob.setStance("idling")
    }

    fun hasDriver(): Boolean = Mounts.of(activeModel(entity ?: return false))?.driver != null

    /** Adult only: puts [player] on the passenger seat. */
    fun seatPassenger(player: Player): Boolean {
        val model = activeModel(entity ?: return false) ?: return false
        return Mounts.mountPassenger(model, settings.passengerSeat, player)
    }

    // ==================== animation ====================

    private fun activeModel(body: LivingEntity): ActiveModel? =
        ModelEngineAPI.getModeledEntity(body.uniqueId)?.models?.values?.firstOrNull()

    private fun playAnimation(name: String, loop: Boolean) {
        if (name.isBlank()) return
        val model = activeModel(entity ?: return) ?: return
        model.animationHandler
            .playAnimation(name, settings.animLerpIn, settings.animLerpOut, settings.animSpeed, loop)
            ?.setForceOverride(BlueprintAnimation.OverrideMode.OVERRIDE)
    }

    private fun stopAnimation(name: String) {
        if (name.isBlank()) return
        activeModel(entity ?: return)?.animationHandler?.stopAnimation(name)
    }

    private fun scheduleQuirk(now: Long) {
        nextQuirkAt = now + Random.nextLong(settings.quirkMinSeconds * 1000L, settings.quirkMaxSeconds * 1000L + 1)
    }

    // ==================== persistence ====================

    private fun capturePosition(body: LivingEntity) {
        val loc = body.location
        world = loc.world.name
        x = loc.x
        y = loc.y
        z = loc.z
        yaw = loc.yaw
    }

    fun save() = plugin.db.saveDragon(toRow())

    fun toRow() = DragonRow(uuid, owner, skin, stage.key, stageStartedAt, saddled, sitting, stored, world, x, y, z, yaw)

    /** Delayed task that drops itself if this body was replaced meanwhile. */
    private fun later(ticks: Long, block: () -> Unit) {
        val body = entity
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (body != null && entity === body) block() }, ticks)
    }

    companion object {
        fun fromRow(row: DragonRow): Dragon? {
            val stage = DragonStage.fromKey(row.stage) ?: return null
            return Dragon(
                row.uuid, row.owner, row.skin, stage, row.stageStartedAt, row.saddled, row.sitting, row.stored,
                row.world, row.x, row.y, row.z, row.yaw,
            )
        }
    }
}
