package com.pause.app.data.local

/** Projection returned by the "most triggered app" aggregate query. */
data class AppTriggerCount(
    val packageName: String,
    val appLabel: String,
    val count: Int,
)
