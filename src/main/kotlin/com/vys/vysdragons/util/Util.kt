package com.vys.vysdragons.util

/**
 * Marks a MythicMobs spawn as an owned egg/dragon coming back (chunk load, growth)
 * rather than a wild spawn, so region plugins' mob-spawn flags can't strand it.
 * [com.vys.vysdragons.listener.DragonListener.onSpawn] un-cancels the spawn while set.
 */
object SpawnBypass {
    var active = false
        private set

    fun <T> run(block: () -> T): T {
        active = true
        try {
            return block()
        } finally {
            active = false
        }
    }
}

/** "1h 5m", "3m 20s", "45s". */
fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
