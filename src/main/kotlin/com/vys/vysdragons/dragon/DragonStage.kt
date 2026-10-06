package com.vys.vysdragons.dragon

enum class DragonStage(val key: String, val rideable: Boolean) {
    BABY("baby", false),
    YOUNG("young", true),
    ADULT("adult", true);

    val next: DragonStage? get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromKey(key: String): DragonStage? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}
