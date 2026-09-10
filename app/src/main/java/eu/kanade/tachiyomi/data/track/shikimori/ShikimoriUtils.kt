package eu.kanade.tachiyomi.data.track.shikimori

import eu.kanade.tachiyomi.data.database.models.Track
import tachiyomi.core.common.util.system.logcat

fun Track.toShikimoriStatus() = when (status) {
    Shikimori.READING -> "watching"
    Shikimori.COMPLETED -> "completed"
    Shikimori.ON_HOLD -> "on_hold"
    Shikimori.DROPPED -> "dropped"
    Shikimori.PLAN_TO_READ -> "planned"
    Shikimori.REREADING -> "rewatching"
    else -> {
        logcat { "Shikimori: Unknown status: $status, defaulting to watching" }
        "watching"
    }
}

fun toTrackStatus(status: String) = when (status) {
    "watching" -> Shikimori.READING
    "completed" -> Shikimori.COMPLETED
    "on_hold" -> Shikimori.ON_HOLD
    "dropped" -> Shikimori.DROPPED
    "planned" -> Shikimori.PLAN_TO_READ
    "rewatching" -> Shikimori.REREADING
    else -> {
        logcat { "Shikimori: Unknown track status: $status, defaulting to READING" }
        Shikimori.READING
    }
}
