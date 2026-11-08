package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.data.database.models.Track
import tachiyomi.core.common.util.system.logcat

fun Track.toApiStatus() = when (status) {
    Bangumi.PLAN_TO_READ -> 1
    Bangumi.COMPLETED -> 2
    Bangumi.READING -> 3
    Bangumi.ON_HOLD -> 4
    Bangumi.DROPPED -> 5
    else -> {
        logcat { "Bangumi: Unknown status: $status, defaulting to PLAN_TO_READ" }
        Bangumi.PLAN_TO_READ
    }
}
