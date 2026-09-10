package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.database.models.Track
import tachiyomi.core.common.util.system.logcat

fun Track.toApiStatus() = when (status) {
    MangaBaka.CONSIDERING -> "considering"
    MangaBaka.COMPLETED -> "completed"
    MangaBaka.DROPPED -> "dropped"
    MangaBaka.PAUSED -> "paused"
    MangaBaka.PLAN_TO_READ -> "plan_to_read"
    MangaBaka.READING -> "reading"
    MangaBaka.REREADING -> "rereading"
    else -> {
        logcat { "MangaBaka: Unknown status: $status, defaulting to reading" }
        "reading"
    }
}
