package com.vys.vysdragons.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

data class DragonRow(
    val uuid: UUID,
    val owner: UUID,
    val skin: String,
    val stage: String,
    val stageStartedAt: Long,
    val saddled: Boolean,
    val sitting: Boolean,
    val stored: Boolean,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
)

data class EggRow(
    val uuid: UUID,
    val owner: UUID,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val progressMillis: Long,
    val heated: Boolean,
    val checkedAt: Long,
    val skin: String?,
)

/**
 * Local SQLite store (plugins/VysDragons/data.db). Loads run on the calling thread;
 * writes are queued onto one background thread so the main thread never waits on
 * disk, and [close] drains that queue before shutdown.
 */
class Database(file: File, private val logger: Logger) {

    private val connection: Connection
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VysDragons-DB").apply { isDaemon = true }
    }

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute(
                """CREATE TABLE IF NOT EXISTS dragons (
                    uuid TEXT PRIMARY KEY, owner TEXT NOT NULL, skin TEXT NOT NULL, stage TEXT NOT NULL,
                    stage_started_at INTEGER NOT NULL, saddled INTEGER NOT NULL, sitting INTEGER NOT NULL,
                    world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL,
                    stored INTEGER NOT NULL DEFAULT 0)"""
            )
            // Added with store/summon; databases from 1.0.0 lack it.
            val hasStored = st.executeQuery("PRAGMA table_info(dragons)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == "stored" }
            }
            if (!hasStored) st.execute("ALTER TABLE dragons ADD COLUMN stored INTEGER NOT NULL DEFAULT 0")
            st.execute(
                """CREATE TABLE IF NOT EXISTS eggs (
                    uuid TEXT PRIMARY KEY, owner TEXT NOT NULL, world TEXT NOT NULL,
                    x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
                    progress_millis INTEGER NOT NULL, heated INTEGER NOT NULL, checked_at INTEGER NOT NULL,
                    skin TEXT)"""
            )
            // Added with egg types; eggs from before have none and get a random skin on load.
            val hasEggSkin = st.executeQuery("PRAGMA table_info(eggs)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == "skin" }
            }
            if (!hasEggSkin) st.execute("ALTER TABLE eggs ADD COLUMN skin TEXT")
        }
    }

    fun loadDragons(): List<DragonRow> = query("SELECT * FROM dragons") { rs ->
        DragonRow(
            UUID.fromString(rs.getString("uuid")), UUID.fromString(rs.getString("owner")),
            rs.getString("skin"), rs.getString("stage"), rs.getLong("stage_started_at"),
            rs.getInt("saddled") != 0, rs.getInt("sitting") != 0, rs.getInt("stored") != 0, rs.getString("world"),
            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"),
        )
    }

    fun loadEggs(): List<EggRow> = query("SELECT * FROM eggs") { rs ->
        EggRow(
            UUID.fromString(rs.getString("uuid")), UUID.fromString(rs.getString("owner")),
            rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
            rs.getLong("progress_millis"), rs.getInt("heated") != 0, rs.getLong("checked_at"), rs.getString("skin"),
        )
    }

    fun saveDragon(row: DragonRow) = write {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO dragons (uuid, owner, skin, stage, stage_started_at, saddled, sitting, " +
                "world, x, y, z, yaw, stored) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, row.uuid.toString()); ps.setString(2, row.owner.toString())
            ps.setString(3, row.skin); ps.setString(4, row.stage); ps.setLong(5, row.stageStartedAt)
            ps.setInt(6, if (row.saddled) 1 else 0); ps.setInt(7, if (row.sitting) 1 else 0)
            ps.setString(8, row.world); ps.setDouble(9, row.x); ps.setDouble(10, row.y)
            ps.setDouble(11, row.z); ps.setFloat(12, row.yaw); ps.setInt(13, if (row.stored) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun saveEgg(row: EggRow) = write {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO eggs (uuid, owner, world, x, y, z, progress_millis, heated, checked_at, skin) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, row.uuid.toString()); ps.setString(2, row.owner.toString())
            ps.setString(3, row.world); ps.setDouble(4, row.x); ps.setDouble(5, row.y); ps.setDouble(6, row.z)
            ps.setLong(7, row.progressMillis); ps.setInt(8, if (row.heated) 1 else 0); ps.setLong(9, row.checkedAt)
            ps.setString(10, row.skin)
            ps.executeUpdate()
        }
    }

    fun deleteDragon(uuid: UUID) = delete("dragons", uuid)
    fun deleteEgg(uuid: UUID) = delete("eggs", uuid)

    /** Waits for queued writes, then closes the connection. */
    fun close() {
        writer.shutdown()
        if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
            logger.warning("Timed out waiting for database writes to finish")
        }
        connection.close()
    }

    private fun delete(table: String, uuid: UUID) = write {
        connection.prepareStatement("DELETE FROM $table WHERE uuid = ?").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeUpdate()
        }
    }

    private fun write(block: () -> Unit) {
        writer.execute {
            try {
                block()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Database write failed", e)
            }
        }
    }

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> {
        val out = mutableListOf<T>()
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> while (rs.next()) out += map(rs) }
        }
        return out
    }
}
