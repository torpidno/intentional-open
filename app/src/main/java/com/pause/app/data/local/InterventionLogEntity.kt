package com.pause.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome

/**
 * One row per intercepted app launch.
 *
 * The app label is denormalised on purpose: history stays readable even if the
 * app is later uninstalled, and the home screen does not need to hit
 * PackageManager to show "most triggered app" for the day.
 */
@Entity(
    tableName = "intervention_logs",
    indices = [
        Index(value = ["timestampMillis"]),
        Index(value = ["packageName"]),
    ],
)
data class InterventionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val timestampMillis: Long,
    val outcome: InterventionOutcome,
    val frictionMode: FrictionMode,
    val intention: String? = null,
    val sessionLimitMinutes: Int? = null,
)
